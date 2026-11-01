"""Create original, synth-only, festive instrumentals for MOMENTO.
No third-party samples, copyrighted recordings, MIDI files, or external music.
The 7 deterministic original arrangements are generated during the Windows build.
"""
from __future__ import annotations
import argparse
import math
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

SAMPLE_RATE = 32000
# Original motifs: pitch positions in a folk-dance-flavoured original 8-note scale.
SCALE = (0, 2, 4, 5, 7, 9, 10, 12, 14, 16, 17, 19, 21)
TRACKS = (
    ("Terjoloba_Sunrise", 118, 62, (0, 2, 4, 5, 4, 2, 1, 0, 3, 5, 7, 8, 7, 5, 4, 2)),
    ("Terjoloba_Celebration", 128, 65, (4, 5, 7, 8, 7, 5, 4, 2, 3, 5, 4, 2, 0, 2, 4, 5)),
    ("Terjoloba_Happy_Steps", 132, 60, (0, 3, 5, 4, 2, 4, 5, 7, 8, 7, 5, 3, 2, 0, 2, 4)),
    ("Terjoloba_Town_Square", 122, 67, (7, 5, 4, 2, 4, 5, 7, 8, 5, 3, 2, 0, 2, 4, 5, 3)),
    ("Terjoloba_Folk_Dance", 135, 62, (0, 4, 5, 7, 5, 4, 2, 3, 5, 8, 7, 5, 4, 2, 0, 2)),
    ("Terjoloba_Golden_Day", 125, 64, (2, 4, 5, 7, 8, 7, 5, 4, 2, 0, 2, 5, 4, 2, 1, 0)),
    ("Terjoloba_Grand_Finale", 138, 69, (5, 7, 8, 10, 8, 7, 5, 4, 7, 8, 10, 8, 7, 5, 4, 2)),
)
LICENSE = """MOMENTO ORIGINAL FESTIVAL MUSIC
Music in this app was composed by a custom algorithm specifically for MOMENTO.
All melodies, arrangements and synthesised sounds are generated from the source
code; no external copyrighted music recordings, sound samples, or loops are used.

The user may use and distribute the included instrumentals in their own videos,
including public events, online posts and commercial productions. No separate
music subscription or attribution is required for the included instrumentals.
These are original electronic festive instrumentals, not recordings of existing
Georgian folk songs. No guarantee is made about third-party automated copyright
claim systems.

When adding your OWN MP3/WAV/M4A files, ensure you have appropriate licences.
"""


def _tone(n_samples: int, midi: float, voice: str, sr: int = SAMPLE_RATE):
    if n_samples <= 0:
        return np.empty(0, dtype=np.float32)
    t = np.arange(n_samples, dtype=np.float32) / sr
    freq = 440.0 * 2.0 ** ((midi - 69.0) / 12.0)
    phi = (2.0 * np.pi * freq) * t
    if voice == "pluck":
        tone = (np.sin(phi) + .47 * np.sin(2.0 * phi) +
                .24 * np.sin(3.0 * phi) + .09 * np.sin(5.0 * phi))
        env = (1.0 - np.exp(-95.0 * t)) * np.exp(-4.1 * t)
    elif voice == "reed":
        tone = (.85 * np.sin(phi) + .4 * np.sin(2.0 * phi) +
                .2 * np.sin(3.0 * phi) + .1 * np.sin(4.0 * phi))
        env = (1.0 - np.exp(-32.0 * t)) * np.exp(-1.7 * t)
        tone *= 1.0 + .035 * np.sin(2.0 * np.pi * 5.3 * t)
    elif voice == "bass":
        tone = np.sin(phi) + .20 * np.sin(2 * phi)
        env = (1.0 - np.exp(-70.0 * t)) * np.exp(-5.5 * t)
    elif voice == "pad":
        tone = (.5 * np.sin(phi) + .25 * np.sin(2 * phi) +
                .13 * np.sin(3 * phi))
        env = np.minimum(1.0, t * 10.0) * np.exp(-1.25 * t)
    else:
        raise ValueError(voice)
    return (tone * env).astype(np.float32)


def _note(buf, start_s: float, duration_s: float, midi: float,
          voice: str, volume: float):
    start = int(start_s * SAMPLE_RATE)
    if start >= len(buf):
        return
    end = min(len(buf), start + int(duration_s * SAMPLE_RATE))
    if end <= start:
        return
    buf[start:end] += volume * _tone(end - start, midi, voice)


def _drum(buf, begin_s, kind, rng):
    first = int(begin_s * SAMPLE_RATE)
    if first < 0 or first >= len(buf):
        return
    n = min(int((.21 if kind == "kick" else .13) * SAMPLE_RATE),
            len(buf) - first)
    t = np.arange(n, dtype=np.float32) / SAMPLE_RATE
    if kind == "kick":
        phase = 2*np.pi*(52*t + 57*(1 - np.exp(-24*t))/24)
        sound = np.sin(phase) * np.exp(-25*t)
        gain = .43
    elif kind == "snare":
        sound = rng.normal(0, 1, n).astype(np.float32)
        sound = (sound - np.convolve(sound, np.ones(9)/9, "same"))
        sound *= np.exp(-35*t)
        gain = .13
    elif kind == "hat":
        sound = rng.normal(0, 1, n).astype(np.float32)
        sound = sound - np.convolve(sound, np.ones(6)/6, "same")
        sound *= np.exp(-94*t)
        gain = .045
    else:
        return
    buf[first:first+n] += (sound * gain).astype(np.float32)


def synthesise(idx, bpm, root, motif):
    # 20 four-beat bars create a cohesive original 35–41s instrumental.
    beat = 60.0 / bpm
    bars = 20
    seconds = bars * 4 * beat + 1.0
    total = int(seconds * SAMPLE_RATE)
    music = np.zeros(total, dtype=np.float32)
    rng = np.random.default_rng(55011 + idx * 211)
    progression = (0, 5, 3, 4, 0, 3, 5, 4, 0, 5, 3, 4,
                   5, 3, 4, 0, 0, 5, 4, 0)
    for bar in range(bars):
        start = bar * 4 * beat
        base_index = progression[bar]
        chord_root = root - 12 + SCALE[base_index % len(SCALE)]
        # Lively plucked string ostinato, with a second octave shimmer.
        for eighth in range(8):
            tick = start + eighth * beat / 2
            note = chord_root + (0, 7, 12, 7, 4, 7, 12, 7)[eighth]
            _note(music, tick, .46 * beat, note, "pluck", .19)
            if eighth % 2 == 0:
                _note(music, tick, .43 * beat, note + 12, "pluck", .042)
        for quarter in range(4):
            tick = start + quarter * beat
            bass_note = chord_root - 12 + (0 if quarter != 2 else 7)
            _note(music, tick, .9 * beat, bass_note, "bass", .44)
            _drum(music, tick, "kick", rng)
            if quarter in (1, 3):
                _drum(music, tick, "snare", rng)
            _drum(music, tick + beat / 2, "hat", rng)
        for n in (0, 4, 7):
            _note(music, start, 3.2 * beat, chord_root + 12 + n,
                  "pad", .055)
        for eighth in range(8):
            if (eighth + bar * 3 + idx) % 7 == 0 and bar != bars-1:
                continue
            pattern_i = (bar * 8 + eighth + (bar//4) * 3) % len(motif)
            degree = motif[pattern_i]
            # Pattern differences give all seven pieces distinct melodies.
            octave_lift = 12 if bar % 8 in (6, 7) else 0
            midi = root + SCALE[degree % len(SCALE)] + octave_lift
            _note(music, start + eighth * beat / 2, .48 * beat, midi,
                  "reed" if bar % 8 in (4, 5) else "pluck", .24)
    # Light synthetic room reflections, no external samples.
    delayed = np.zeros_like(music)
    for ms, gain in ((123, .13), (247, .07)):
        shift = int(ms * SAMPLE_RATE / 1000)
        delayed[shift:] += gain * music[:-shift]
    music += delayed
    t_fade = int(.42 * SAMPLE_RATE)
    music[:t_fade] *= np.linspace(0, 1, t_fade, dtype=np.float32)
    final_fade = int(.86 * SAMPLE_RATE)
    music[-final_fade:] *= np.linspace(1, 0, final_fade, dtype=np.float32)
    music = np.tanh(music * 1.3)
    peak = max(.01, np.max(np.abs(music)))
    music = .86 * music / peak
    # Slight stereo field from original phase-independent delay.
    offset = int(.014 * SAMPLE_RATE)
    left = music
    right = .87 * music + .13 * np.concatenate(
        (np.zeros(offset, dtype=np.float32), music[:-offset]))
    stereo = np.stack([left, right], axis=1)
    return np.int16(np.clip(stereo, -1.0, 1.0) * 32767)


def generate(out: Path, ffmpeg: str):
    out.mkdir(parents=True, exist_ok=True)
    for index, (name, bpm, root, motif) in enumerate(TRACKS):
        print("Synthesizing", name, flush=True)
        raw = synthesise(index, bpm, root, motif)
        with tempfile.TemporaryDirectory() as d:
            wav = Path(d) / "music.wav"
            with wave.open(str(wav), "wb") as file:
                file.setnchannels(2)
                file.setsampwidth(2)
                file.setframerate(SAMPLE_RATE)
                file.writeframes(raw.tobytes())
            dest = out / f"{name}.mp3"
            cmd = [ffmpeg, "-hide_banner", "-loglevel", "error",
                   "-nostdin", "-y", "-i", str(wav), "-c:a", "libmp3lame",
                   "-b:a", "192k", str(dest)]
            subprocess.run(cmd, check=True, timeout=180)
            if not dest.is_file() or dest.stat().st_size < 50000:
                raise RuntimeError(f"Output MP3 was not generated: {dest}")
            print("Created", dest.name, dest.stat().st_size, "bytes", flush=True)
    (out / "MOMENTO_MUSIC_LICENSE.txt").write_text(LICENSE, encoding="utf-8")


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--ffmpeg", required=True)
    args=parser.parse_args()
    generate(args.out, args.ffmpeg)
