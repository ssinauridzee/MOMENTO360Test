"""MOMENTO Festival Music — remove original sound; attach licensed festive music.
Video is streamed through unchanged using -c:v copy, so Slow Motion and video
quality are preserved. Only audio is replaced with a fade-in / fade-out.
"""
from __future__ import annotations

import os
from pathlib import Path
import random
import re
import subprocess
import sys
import threading
from typing import Callable, Sequence

ALLOWED_VIDEOS = {".mp4", ".mov", ".m4v", ".mkv", ".avi"}
ALLOWED_MUSIC = {".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac"}

NAMES = {
    "Terjoloba_Sunrise": "თერჯოლობა — მზიანი დილა",
    "Terjoloba_Celebration": "თერჯოლობა — ზეიმი",
    "Terjoloba_Happy_Steps": "თერჯოლობა — მხიარული ნაბიჯები",
    "Terjoloba_Town_Square": "თერჯოლობა — ქალაქის მოედანი",
    "Terjoloba_Folk_Dance": "თერჯოლობა — საცეკვაო რიტმი",
    "Terjoloba_Golden_Day": "თერჯოლობა — ოქროს დღე",
    "Terjoloba_Grand_Finale": "თერჯოლობა — გრანდიოზული ფინალი",
}


def bundled_directory() -> Path:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent)) / "music"
    return Path(__file__).resolve().parent / "music"


def original_tracks() -> list[Path]:
    root = bundled_directory()
    return sorted(p for p in root.glob("*.mp3") if p.is_file() and
                  p.stem in NAMES)


def pretty_music(path: Path) -> str:
    return NAMES.get(path.stem, path.stem.replace("_", " "))


def ffmpeg_path() -> str:
    if getattr(sys, "frozen", False):
        base = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
        binary = base / ("ffmpeg.exe" if os.name == "nt" else "ffmpeg")
        if binary.is_file():
            return str(binary)
    candidate = Path(__file__).resolve().parent / ("ffmpeg.exe" if os.name == "nt" else "ffmpeg")
    if candidate.exists():
        return str(candidate)
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except (ImportError, RuntimeError):
        return "ffmpeg"


def startup_info():
    if os.name == "nt":
        info = subprocess.STARTUPINFO()
        info.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        return info
    return None


def duration_seconds(ffmpeg: str, path: Path, require_video: bool = True) -> float:
    try:
        p = subprocess.run(
            [ffmpeg, "-hide_banner", "-nostdin", "-i", str(path)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=40, startupinfo=startup_info())
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"ფაილი ვერ შემოწმდა: {path.name}") from exc
    log = (p.stderr or "") + "\n" + (p.stdout or "")
    if require_video and "Video:" not in log:
        raise ValueError(f"ვიდეოს კადრები არ მოიძებნა: {path.name}")
    if not require_video and "Audio:" not in log:
        raise ValueError(f"მუსიკის აუდიო არ მოიძებნა: {path.name}")
    found = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", log)
    if not found:
        raise ValueError(f"ხანგრძლივობა ვერ მოიძებნა: {path.name}")
    h, minute, sec = found.groups()
    value = int(h) * 3600 + int(minute) * 60 + float(sec)
    if value <= 0.1:
        raise ValueError(f"ძალიან მოკლე ფაილია: {path.name}")
    return value


def random_assignments(count: int, music: Sequence[Path], rng=None) -> list[Path]:
    if not music:
        raise ValueError("აირჩიე მინიმუმ ერთი მუსიკა.")
    if count < 0:
        raise ValueError("ვიდეოების რაოდენობა არასწორია.")
    # Shuffle bag: play each song before any repeats, without adjacent repeats.
    rng = rng or random.SystemRandom()
    songs = list(dict.fromkeys(music))
    output: list[Path] = []
    while len(output) < count:
        bag = songs.copy()
        rng.shuffle(bag)
        if len(bag) > 1 and output and bag[0] == output[-1]:
            for i in range(1, len(bag)):
                if bag[i] != output[-1]:
                    bag[0], bag[i] = bag[i], bag[0]
                    break
        output.extend(bag)
    return output[:count]


def result_path(folder: Path, source: Path, used: set[str]) -> Path:
    safe = re.sub(r'[<>:"/\\|?*]', "_", source.stem).strip(" .") or "GOPRO"
    i = 1
    while True:
        suffix = "" if i == 1 else f"_{i:02d}"
        name = f"{safe}_MOMENTO_FESTIVAL{suffix}.mp4"
        target = folder / name
        if name.lower() not in used and not target.exists():
            used.add(name.lower())
            return target
        i += 1


class Stopped(Exception):
    pass


def replace_audio(
    source: Path,
    music: Path,
    output: Path,
    *,
    ffmpeg: str | None = None,
    duration: float | None = None,
    music_volume: float = 0.85,
    fade_seconds: float = 0.65,
    stopped: threading.Event | None = None,
    progress: Callable[[float], None] | None = None,
) -> tuple[Path, float]:
    """Use video stream copy; remove ALL old audio; loop/fade new MP3 track."""
    ffmpeg = ffmpeg or ffmpeg_path()
    if not source.is_file() or not music.is_file():
        raise FileNotFoundError("ვიდეო ან მუსიკა ვერ მოიძებნა.")
    duration = duration or duration_seconds(ffmpeg, source)
    if duration <= .1:
        raise ValueError("ვიდეო ძალიან მოკლეა.")
    if not (.0 < music_volume <= 2.0):
        raise ValueError("ხმის დონე არასწორია.")
    if stopped and stopped.is_set():
        raise Stopped()
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.stem + ".working.mp4")
    if partial.exists():
        partial.unlink()
    fade = min(fade_seconds, duration / 3)
    out_start = max(0.0, duration - fade)
    audio_filters = (
        f"volume={music_volume:g},"
        f"afade=t=in:st=0:d={min(.28, fade):.3f},"
        f"afade=t=out:st={out_start:.3f}:d={fade:.3f}"
    )
    cmd = [
        ffmpeg, "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
        "-i", str(source),
        "-stream_loop", "-1", "-i", str(music),
        "-map", "0:v:0", "-map", "1:a:0",
        "-c:v", "copy",  # Preserve previously-edited slow-motion frames.
        "-c:a", "aac", "-b:a", "192k",
        "-af", audio_filters,
        "-t", f"{duration:.6f}",
        "-movflags", "+faststart",
        "-progress", "pipe:1", "-nostats",
        str(partial)
    ]
    logs: list[str] = []
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    proc = None
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True,
                                encoding="utf-8", errors="replace", bufsize=1,
                                creationflags=flags, startupinfo=startup_info())
        def drain_errors():
            assert proc is not None and proc.stderr is not None
            for line in proc.stderr:
                logs.append(line.strip())
                if len(logs) > 45:
                    del logs[:10]
        errthread = threading.Thread(target=drain_errors, daemon=True)
        errthread.start()
        assert proc.stdout is not None
        for line in proc.stdout:
            if stopped is not None and stopped.is_set():
                proc.terminate()
                raise Stopped()
            key, eq, val = line.strip().partition("=")
            if eq and key in ("out_time_us", "out_time_ms"):
                try:
                    # FFmpeg uses microseconds for both fields on current builds.
                    position = int(val) / 1_000_000
                    if progress:
                        progress(min(99.0, 100.0 * position / duration))
                except ValueError:
                    pass
        code = proc.wait()
        errthread.join(timeout=2)
        if stopped is not None and stopped.is_set():
            raise Stopped()
        if code != 0 or not partial.is_file() or partial.stat().st_size < 1024:
            raise RuntimeError("მუსიკის დადება ვერ დასრულდა: " +
                               ("\n".join(logs[-5:]) or f"FFmpeg exit={code}"))
        partial.replace(output)
        if progress:
            progress(100.0)
        return output, duration
    finally:
        if proc is not None and proc.poll() is None:
            proc.kill()
            proc.wait(timeout=6)
        if partial.exists():
            try:
                partial.unlink()
            except OSError:
                pass
