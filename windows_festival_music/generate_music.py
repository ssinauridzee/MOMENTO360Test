"""MOMENTO Happy Festival: 60 different ORIGINAL upbeat instrumentals.

All notes, melodic hooks, rhythms and synth sounds are produced here.
No copyrighted commercial recordings, samples, existing song melodies,
external audio loops or unlicensed sound packs are incorporated.

5 distinct sound palettes x 12 independent melodic compositions:
happy dance pop, party dance, summer bounce, festive brass and disco fun.
"""
from __future__ import annotations

import argparse
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np
from catalog import MAJOR, CHORD_TONES, TRACKS

SAMPLE_RATE = 32000
BARS = 16

LICENSE = """MOMENTO HAPPY FESTIVAL MUSIC — ORIGINAL INSTRUMENTALS

All included compositions were generated from new original melodies,
rhythms, arrangements and synthesised sounds written specifically for this
MOMENTO Windows video application. This package contains no external music
recordings, copyrighted sample libraries or reproductions of existing songs.

You are permitted to use the included instrumental audio files, including
their edited versions, in your own videos and commercial event productions
without paying licensing fees or giving attribution.

The tracks are synthetic instrumental compositions. There is no guarantee
that automated copyright detection services will never make a mistaken claim.

For music files you add yourself, you must check your permission or licence.
"""

STYLE_VOICES = {
    "Happy_Pop": ("shine", "pluck", "pad"),
    "Party_Dance": ("supersaw", "shine", "pad"),
    "Summer_Bounce": ("marimba", "bell", "pad"),
    "Festive_Brass": ("brass", "pluck", "pad"),
    "Disco_Fun": ("funk", "shine", "pad"),
}


def tone(n: int, midi: float, instrument: str, seed: int = 0):
    if n <= 0:
        return np.zeros(0, dtype=np.float32)
    t = np.arange(n, dtype=np.float32) / SAMPLE_RATE
    freq = 440.0 * (2.0 ** ((midi - 69) / 12))
    w = np.float32(2 * np.pi * freq) * t
    attack = 1.0 - np.exp(-np.float32(90.0) * t)
    if instrument == "pluck":
        wave_ = np.sin(w) + .45*np.sin(2*w) + .20*np.sin(3*w) + .09*np.sin(4*w)
        env = attack*np.exp(-7.4*t)
    elif instrument == "shine":
        wave_ = np.sin(w + .009*np.sin(5.1*t)) + .36*np.sin(2*w) + .17*np.sin(3*w)
        env = attack*np.exp(-3.6*t)
    elif instrument == "bell":
        wave_ = np.sin(w) + .23*np.sin(2.03*w) + .12*np.sin(3.97*w)
        env = attack*np.exp(-3.3*t)
    elif instrument == "marimba":
        wave_ = np.sin(w)+.22*np.sin(3*w)+.13*np.sin(5*w)
        env = (1.0-np.exp(-135*t))*np.exp(-6.1*t)
    elif instrument == "supersaw":
        # Synthesized wide, bright lead without external samples.
        wave_ = (.65*np.sin(w)+.26*np.sin(2*w)+.16*np.sin(3*w)
                 + .11*np.sin(4*w) + .12*np.sin(w*1.005)
                 + .10*np.sin(w*.995))
        env = (1-np.exp(-72*t))*np.exp(-2.8*t)
    elif instrument == "brass":
        wave_ = (.7*np.sin(w)+.40*np.sin(2*w)+.24*np.sin(3*w)+
                 .13*np.sin(4*w)+.07*np.sin(5*w))
        env = (1-np.exp(-24*t))*np.exp(-1.9*t)
        wave_ *= 1 + .035*np.sin(2*np.pi*5.2*t)
    elif instrument == "funk":
        wave_ = (.85*np.sin(w)+.37*np.sin(2*w)+.22*np.sin(3*w)
                 + .12*np.sin(6*w))
        env = (1-np.exp(-57*t))*np.exp(-5.0*t)
    elif instrument == "bass":
        wave_ = np.sin(w) + .30*np.sin(2*w) + .12*np.sin(3*w)
        env = (1-np.exp(-58*t))*np.exp(-4.1*t)
    elif instrument == "pad":
        wave_ = .5*np.sin(w)+.2*np.sin(2*w)+.10*np.sin(3*w)
        env = (1-np.exp(-12*t))*np.exp(-.9*t)
    else:
        raise ValueError(instrument)
    return np.asarray(wave_*env, dtype=np.float32)


def put(buf: np.ndarray, time_s: float, length_s: float, midi: float,
        instrument: str, volume: float):
    start = max(0, int(time_s*SAMPLE_RATE))
    end = min(len(buf), start+int(length_s*SAMPLE_RATE))
    if end <= start:
        return
    buf[start:end] += volume*tone(end-start, midi, instrument)


def drum(buf: np.ndarray, time_s: float, style: str, rng):
    start = max(0, int(time_s*SAMPLE_RATE))
    if start >= len(buf):
        return
    length_s = dict(kick=.18, snare=.125, clap=.11,
                    hat=.095, shaker=.075, tambourine=.13)[style]
    size = min(int(length_s*SAMPLE_RATE), len(buf)-start)
    t = np.arange(size,dtype=np.float32)/SAMPLE_RATE
    if style=="kick":
        phase=2*np.pi*(50*t + 72*(1-np.exp(-29*t))/29)
        audio=np.sin(phase)*np.exp(-27*t)
        power=.53
    elif style=="snare":
        noise=rng.standard_normal(size,dtype=np.float32)
        audio=(noise+.18*np.sin(2*np.pi*190*t))*np.exp(-37*t)
        power=.15
    elif style=="clap":
        noise=rng.standard_normal(size,dtype=np.float32)
        pulses=(1+.8*np.sin(2*np.pi*36*t)**2)*np.exp(-42*t)
        audio=noise*pulses
        power=.15
    elif style=="hat":
        noise=rng.standard_normal(size,dtype=np.float32)
        audio=(noise-np.convolve(noise,np.ones(6)/6,mode="same"))*np.exp(-100*t)
        power=.057
    elif style=="shaker":
        noise=rng.standard_normal(size,dtype=np.float32)
        audio=(noise-np.convolve(noise,np.ones(11)/11,mode="same"))*np.exp(-68*t)
        power=.038
    else:
        noise=rng.standard_normal(size,dtype=np.float32)
        audio=(noise-np.convolve(noise,np.ones(9)/9,mode="same"))*np.exp(-38*t)
        power=.055
    buf[start:start+size] += audio.astype(np.float32)*power


def synthesise(idx: int, bpm: int, root: int, motif: tuple[int, ...],
               style: str, variant: int, progression: tuple[int, ...]):
    """A coherent new original song with distinct melody, chords and beat.

    16 bars, four distinct sections, functional major-key harmony. Every track
    has an individual tempo, root, syncopation, melodic hook and arrangement.
    """
    beat=60.0/bpm
    seconds=BARS*4*beat+1.5
    n=int(seconds*SAMPLE_RATE)
    lead=np.zeros(n,dtype=np.float32)
    accompaniment=np.zeros(n,dtype=np.float32)
    rhythm=np.zeros(n,dtype=np.float32)
    rng=np.random.default_rng(88112 + 1531*idx)
    lead_voice, arpeggio_voice, pad_voice=STYLE_VOICES[style]
    # Every piece has a recognisable repeating original hook; its second half
    # adds a response variation that makes it more than a cloned backing loop.
    alternate=tuple(max(0,min(18, p+((j+variant)%4-1))) for j,p in enumerate(motif))
    for bar in range(BARS):
        beginning=bar*4*beat
        chord=progression[bar%4]
        degrees=CHORD_TONES[chord]
        chord_notes=[root-12+MAJOR[d] for d in degrees]
        section=bar//4
        # Warm underlying major triads; changes every bar.
        if bar < 15:
            for pitch in chord_notes:
                put(accompaniment,beginning,3.7*beat,pitch+12,
                    pad_voice,.047 if style=="Summer_Bounce" else .038)
        # Bright, varied arpeggiator; offbeats give party groove.
        arpeggio=(0,1,2,1,0,2,1,2)
        if style in ("Party_Dance","Disco_Fun"):
            arpeggio=(0,2,1,2,0,1,2,1)
        elif style=="Festive_Brass":
            arpeggio=(0,1,2,1,2,1,0,1)
        for eighth in range(8):
            when=beginning+eighth*beat/2
            pitch=chord_notes[arpeggio[(eighth+variant)%8]]+12
            put(accompaniment,when,.38*beat,pitch,
                arpeggio_voice,.085 if section==0 else .117)
            if section==3 and eighth%2==0:
                put(accompaniment,when,.23*beat,pitch+12,"shine",.034)
        # Lively bass, never minor or gloomy.
        for quarter in range(4):
            when=beginning+quarter*beat
            if style=="Disco_Fun":
                bass_pitch=chord_notes[0]-12+(7 if quarter==2 else 0)
                put(accompaniment,when,.63*beat,bass_pitch,"bass",.36)
                put(accompaniment,when+.5*beat,.29*beat,
                    chord_notes[0]-12,"bass",.22)
            elif style=="Summer_Bounce":
                bass_pitch=chord_notes[0]-12
                put(accompaniment,when+(.14*beat if quarter in (1,3) else 0),
                    .68*beat,bass_pitch,"bass",.38)
            else:
                bass_pitch=chord_notes[0]-12+(7 if quarter==2 else 0)
                put(accompaniment,when,.75*beat,bass_pitch,"bass",.38)
        # Two motif sections, hook/response, octave sparkling in the finale.
        chosen=motif if section in (0,2,3) else alternate
        for eighth in range(8):
            slot=(bar%2)*8+eighth
            # Distinct syncopation; leave rests for breathing and groove.
            if ((eighth+variant+section)%11==0 and section!=3):
                continue
            when=beginning + eighth*beat/2
            midi=root+MAJOR[chosen[slot]%len(MAJOR)]
            if section==3 and eighth in (0,3,6):
                midi+=12
            put(lead,when,(.42 if eighth%2 else .55)*beat,
                midi,lead_voice,.245 if section!=0 else .185)
            if style=="Festive_Brass" and eighth in (0,4):
                put(lead,when,.43*beat,midi-12,"brass",.08)
        # Bright club grooves with per-genre kick changes.
        for q in range(4):
            when=beginning+q*beat
            if style=="Summer_Bounce":
                if q in (0,2) or ((bar+variant)%2==0 and q==3):
                    drum(rhythm,when,"kick",rng)
                drum(rhythm,when+.5*beat,"shaker",rng)
            elif style=="Festive_Brass":
                drum(rhythm,when,"kick",rng)
                if q in (1,3):
                    drum(rhythm,when,"clap",rng)
            else:
                drum(rhythm,when,"kick",rng)
            if q in (1,3):
                drum(rhythm,when,"snare",rng)
                if section>0:
                    drum(rhythm,when,"clap",rng)
            # Upbeat offbeat closed hats are key to dance-pop rhythm.
            drum(rhythm,when+.5*beat,"hat",rng)
            if style in ("Disco_Fun","Party_Dance"):
                drum(rhythm,when+.75*beat,"shaker",rng)
        if bar in (3,7,11,15):
            # Satisfying short drum fill and cymbal/tambourine-like accents.
            for q in range(4):
                drum(rhythm,beginning+(3+q*.25)*beat,"snare",rng)
            drum(rhythm,beginning+3.75*beat,"tambourine",rng)
    # Original stereo arrangement with a short synthetic room reflection.
    delayed=np.zeros_like(lead)
    offset=int(.145*SAMPLE_RATE)
    delayed[offset:]=lead[:-offset]*.18
    left=lead*.95 + accompaniment*.88 + rhythm*.86 + .07*delayed
    right=lead*.82 + accompaniment*.98 + rhythm*.86 + delayed*.17
    # Subtle dynamics and bright mastering with no external samples.
    mix=np.stack([left,right],axis=-1)
    mix=np.tanh(mix*1.3).astype(np.float32)
    fade=int(.32*SAMPLE_RATE)
    mix[:fade]*=np.linspace(0,1,fade,dtype=np.float32)[:,None]
    outfade=int(.95*SAMPLE_RATE)
    mix[-outfade:]*=np.linspace(1,0,outfade,dtype=np.float32)[:,None]
    peak=max(.01,float(np.max(np.abs(mix))))
    mix*=.92/peak
    return np.int16(np.clip(mix,-1,1)*32767)


def generate(out:Path, ffmpeg:str):
    out.mkdir(parents=True,exist_ok=True)
    for index, track in enumerate(TRACKS):
        print(f"[{index+1:02d}/{len(TRACKS)}] HAPPY {track.name}",
              flush=True)
        pcm=synthesise(index,*track)
        with tempfile.TemporaryDirectory() as temp:
            wav=Path(temp)/"original_music.wav"
            with wave.open(str(wav),"wb") as writer:
                writer.setnchannels(2)
                writer.setsampwidth(2)
                writer.setframerate(SAMPLE_RATE)
                writer.writeframes(pcm.tobytes())
            destination=out/f"{track.name}.mp3"
            subprocess.run(
                [ffmpeg,"-hide_banner","-nostdin","-loglevel","error",
                 "-y","-i",str(wav),"-c:a","libmp3lame","-b:a","160k",
                 str(destination)],check=True,timeout=180)
            if destination.stat().st_size < 110000:
                raise RuntimeError(f"Track missing/too small: {destination.name}")
            print("OK", destination.name,destination.stat().st_size,
                  flush=True)
    (out/"MOMENTO_MUSIC_LICENSE.txt").write_text(LICENSE,encoding="utf-8")


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--out",type=Path,required=True)
    parser.add_argument("--ffmpeg",required=True)
    args=parser.parse_args()
    generate(args.out,args.ffmpeg)
