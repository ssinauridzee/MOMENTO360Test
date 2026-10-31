"""MOMENTO Batch SlowMo — FFmpeg video engine (standard library only)."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import math
import os
import re
import subprocess
import sys
import threading
from typing import Callable, Iterable

SUPPORTED_EXTENSIONS = {'.mp4', '.mov', '.m4v', '.avi', '.mkv'}


@dataclass(frozen=True)
class SlowSegment:
    start: float
    end: float
    speed: float

    @property
    def original_seconds(self) -> float:
        return self.end - self.start


class Cancelled(Exception):
    pass


def parse_seconds(value: str) -> float:
    value = value.strip().replace(',', '.')
    if ':' in value:
        bits = value.split(':')
        if not 2 <= len(bits) <= 3:
            raise ValueError(f'დრო არასწორადაა მითითებული: {value}')
        nums = [float(x) for x in bits]
        result = 0.0
        for n in nums:
            if n < 0 or not math.isfinite(n):
                raise ValueError('დრო არ შეიძლება იყოს უარყოფითი.')
            result = result * 60 + n
        return result
    try:
        result = float(value)
    except ValueError as exc:
        raise ValueError(f'არასწორი დრო: {value}') from exc
    if not math.isfinite(result) or result < 0:
        raise ValueError('დრო უნდა იყოს დადებითი რიცხვი.')
    return result


def parse_segments(spec: str) -> list[SlowSegment]:
    """Read `start-end@speed; ...`; times always refer to original camera MP4."""
    spec = spec.strip()
    if not spec:
        raise ValueError('შეიყვანე მინიმუმ ერთი ინტერვალი, მაგალითად 3-5@0.25')
    result: list[SlowSegment] = []
    for fragment in re.split(r'[;\n]+', spec):
        fragment = fragment.strip()
        if not fragment:
            continue
        match = re.fullmatch(r'\s*([\d:.,]+)\s*-\s*([\d:.,]+)\s*@\s*([\d.,]+)\s*', fragment)
        if not match:
            raise ValueError(f'არასწორი ინტერვალი: {fragment}. მაგალითი: 3-5@0.25')
        start = parse_seconds(match.group(1))
        end = parse_seconds(match.group(2))
        speed = parse_seconds(match.group(3))
        if start >= end:
            raise ValueError(f'ინტერვალის დასაწყისი ბოლოზე ნაკლები უნდა იყოს: {fragment}')
        if speed not in (0.125, 0.25, 0.5):
            raise ValueError('შენელება აირჩიე მხოლოდ 0.5, 0.25 ან 0.125.')
        result.append(SlowSegment(start, end, speed))
    if not result:
        raise ValueError('შეიყვანე მინიმუმ ერთი ინტერვალი.')
    result.sort(key=lambda p: p.start)
    for a, b in zip(result, result[1:]):
        if a.end > b.start + 0.0001:
            raise ValueError('შენელების ინტერვალები ერთმანეთს არ უნდა კვეთდეს.')
    return result


def validate_duration(segments: Iterable[SlowSegment], video_duration: float) -> None:
    if video_duration < 0.5:
        raise ValueError('ვიდეო ძალიან მოკლეა ან ხანგრძლივობა ვერ დადგინდა.')
    for s in segments:
        if s.end > video_duration + 0.15:
            raise ValueError(
                f'შენელება {s.end:g} წმ-მდეა, მაგრამ ორიგინალი ვიდეო მხოლოდ '
                f'{video_duration:.2f} წმ-ია. შეამცირე ინტერვალი.'
            )


def result_duration(original_duration: float, segments: Iterable[SlowSegment]) -> float:
    return original_duration + sum(s.original_seconds * (1 / s.speed - 1) for s in segments)


def ffmpeg_path() -> str:
    if getattr(sys, 'frozen', False):
        base = Path(getattr(sys, '_MEIPASS', Path(sys.executable).parent))
        binary = base / ('ffmpeg.exe' if os.name == 'nt' else 'ffmpeg')
        if binary.is_file():
            return str(binary)
    executable = Path(sys.executable).parent / ('ffmpeg.exe' if os.name == 'nt' else 'ffmpeg')
    if executable.is_file():
        return str(executable)
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except (ImportError, RuntimeError):
        return 'ffmpeg'


def probe_duration(ffmpeg: str, source: Path) -> float:
    try:
        proc = subprocess.run(
            [ffmpeg, '-hide_banner', '-nostdin', '-i', str(source)],
            capture_output=True, text=True, encoding='utf-8', errors='replace',
            startupinfo=_startupinfo(), timeout=35,
        )
    except OSError as e:
        raise RuntimeError('FFmpeg ვერ გაეშვა. გამოიყენე Windows-ის EXE პაკეტი.') from e
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f'ვიდეოს ინფორმაცია ვერ წავიკითხე: {source.name}') from e
    output = (proc.stderr or '') + '\n' + (proc.stdout or '')
    if 'Video:' not in output:
        raise ValueError(f'ვიდეო ნაკადი არ მოიძებნა: {source.name}')
    found = re.search(r'Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)', output)
    if not found:
        raise ValueError(f'ვიდეოს ხანგრძლივობა ვერ განისაზღვრა: {source.name}')
    h, m, s = found.groups()
    return int(h) * 3600 + int(m) * 60 + float(s)


def filter_graph(segments: list[SlowSegment], duration: float, fps: int = 60) -> str:
    """Produce a deterministic trim/PTS/concat graph with independent split pads."""
    validate_duration(segments, duration)
    portions: list[tuple[float, float, float]] = []
    current = 0.0
    for seg in segments:
        if seg.start - current > 0.0001:
            portions.append((current, seg.start, 1.0))
        portions.append((seg.start, min(seg.end, duration), seg.speed))
        current = seg.end
    if duration - current > 0.0001:
        portions.append((current, duration, 1.0))
    count = len(portions)
    split_outputs = ''.join(f'[src{i}]' for i in range(count))
    parts = [f'[0:v:0]split={count}{split_outputs}']
    for i, (start, end, speed) in enumerate(portions):
        pts = 'PTS-STARTPTS' if speed == 1 else f'(PTS-STARTPTS)/{speed:g}'
        parts.append(
            f'[src{i}]trim=start={start:.6f}:end={end:.6f},'
            f'setpts={pts},setsar=1[clip{i}]'
        )
    combined = ''.join(f'[clip{i}]' for i in range(count))
    parts.append(f'{combined}concat=n={count}:v=1:a=0,fps={fps},format=yuv420p[outv]')
    return ';'.join(parts)


def _startupinfo():
    if os.name == 'nt':
        info = subprocess.STARTUPINFO()
        info.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        return info
    return None


def choose_output_name(out_dir: Path, source: Path, occupied: set[str]) -> Path:
    stem = re.sub(r'[<>:"/\\|?*]', '_', source.stem).strip(' .') or 'GOPRO_VIDEO'
    candidate = out_dir / f'{stem}_MOMENTO_SLOWMO.mp4'
    num = 2
    while candidate.name.lower() in occupied or candidate.exists():
        candidate = out_dir / f'{stem}_MOMENTO_SLOWMO_{num:02d}.mp4'
        num += 1
    occupied.add(candidate.name.lower())
    return candidate


def render(
    source: Path,
    output: Path,
    segments: list[SlowSegment],
    *,
    ffmpeg: str | None = None,
    fps: int = 60,
    crf: int = 20,
    preset: str = 'veryfast',
    cancelled: threading.Event | None = None,
    progress: Callable[[float], None] | None = None,
) -> tuple[Path, float, float]:
    ffmpeg = ffmpeg or ffmpeg_path()
    duration = probe_duration(ffmpeg, source)
    validate_duration(segments, duration)
    resulting = result_duration(duration, segments)
    graph = filter_graph(segments, duration, fps)
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.stem + '.rendering.mp4')
    if partial.exists():
        partial.unlink()
    cmd = [
        ffmpeg, '-hide_banner', '-y', '-nostdin', '-loglevel', 'error',
        '-i', str(source), '-filter_complex', graph,
        '-map', '[outv]', '-an', '-c:v', 'libx264', '-preset', preset,
        '-crf', str(crf), '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
        '-progress', 'pipe:1', '-nostats', str(partial),
    ]
    if cancelled and cancelled.is_set():
        raise Cancelled('მომხმარებელმა დამუშავება შეაჩერა')
    errors: list[str] = []
    try:
        creationflags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
        process = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            encoding='utf-8', errors='replace', bufsize=1,
            startupinfo=_startupinfo(), creationflags=creationflags,
        )
        def read_error():
            assert process.stderr is not None
            for line in process.stderr:
                errors.append(line.rstrip())
                if len(errors) > 40:
                    del errors[:10]
        err_thread = threading.Thread(target=read_error, daemon=True)
        err_thread.start()
        try:
            assert process.stdout is not None
            for line in process.stdout:
                if cancelled and cancelled.is_set():
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                    raise Cancelled('დამუშავება შეჩერებულია')
                key, sep, val = line.strip().partition('=')
                if sep and key == 'out_time':
                    try:
                        h, m, s = val.split(':')
                        t = int(h) * 3600 + int(m) * 60 + float(s)
                        if progress:
                            progress(min(99.0, 100 * t / max(resulting, 0.001)))
                    except (ValueError, ZeroDivisionError):
                        pass
            code = process.wait()
            err_thread.join(timeout=1)
            if cancelled and cancelled.is_set():
                raise Cancelled('დამუშავება შეჩერებულია')
            if code != 0 or not partial.is_file() or partial.stat().st_size < 1024:
                tail = '\n'.join(errors[-6:]) or f'FFmpeg exit code {code}'
                raise RuntimeError(f'ვიდეო ვერ დამუშავდა: {tail}')
            partial.replace(output)
            if progress:
                progress(100.0)
            return output, duration, resulting
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
    finally:
        if partial.exists():
            try:
                partial.unlink()
            except OSError:
                pass