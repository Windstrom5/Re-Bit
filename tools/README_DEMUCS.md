# Karaoke stem separation tools

Two tools live here, and they are **not equivalent** — read the difference
before picking one.

| | `demucs_separator.py` | `convert_demucs_to_tflite.py` |
|---|---|---|
| What it runs | The real, pre-trained Meta Demucs (`htdemucs`) | An **untrained** random-weight template network |
| Separation quality | Genuinely state-of-the-art | None — random weights output noise |
| Where it runs | Your desktop (needs PyTorch + a few GB of model weights) | Your desktop, but the *output* is useless |
| Recommended? | **Yes — this is the real workflow** | No, unless you're doing the actual ML research to train a real model from scratch |

## The real workflow: desktop Demucs → bit-perfect playback on the phone

BitPerfectUSB's karaoke mode has two quality tiers:

1. **Studio Quality (recommended)** — a real, offline-separated stem from this
   workflow, played back **bit-perfectly with zero real-time processing**.
   This is as good as separation gets today; it's the same model used by
   professional stem-splitting tools.
2. **Enhanced (real-time DSP)** — used automatically when no stem is found.
   This runs `SpectralSeparationEngine`'s adaptive spectral masking live during
   playback. It's a solid, tuned classic-DSP technique, not a neural network —
   see that class's doc comment for exactly what it can and can't do. It'll
   never match tier 1, because no real-time DSP heuristic can.

### 1. Install dependencies
```bash
pip install demucs torch torchaudio soundfile
```
*(If you have an NVIDIA GPU, install PyTorch with CUDA first — separation is ~10x faster.)*

### 2. Separate a song into instrumental & vocal stems
```bash
python tools/demucs_separator.py --input "D:/Music/Some Song.flac"
```
This produces:
- `separated_stems/htdemucs/Some Song/no_vocals.flac` — instrumental
- `separated_stems/htdemucs/Some Song/vocals.flac` — acapella

### 3. Push straight to your phone
```bash
python tools/demucs_separator.py --input "D:/Music/Some Song.flac" --push-to-phone
```
This separates the track and pushes the stems to `/sdcard/Music/BitPerfect_Stems/`
on a connected device via ADB, renamed to `Some Song_no_vocals.flac` /
`Some Song_vocals.flac` (each pushed file is prefixed with the track's own
name — Demucs itself always names its output the same generic
`vocals.flac`/`no_vocals.flac` regardless of source, and pushing that as-is
into one shared phone folder would silently overwrite the previous song's
stems the next time you run this).

BitPerfectUSB's `ExternalStemResolver` looks for these automatically the next
time you play that track with karaoke mode on — checking both the same folder
as the original file (if you copy stems there by hand) and the fixed
`BitPerfect_Stems` folder above. No import step, no app restart needed —
just make sure the phone has scanned the new files (the `--push-to-phone`
path already triggers that for you).

**Match the filename, not just the title.** The resolver matches by the
original track's own on-device filename (minus extension), not by ID3/Vorbis
title tags. Run `demucs_separator.py` against the exact same file you have on
your phone (or at least the same filename) and it'll match correctly.

## Why not `convert_demucs_to_tflite.py`?

That script builds a small Conv2D network shaped to match
`SpectralSeparationEngine`'s optional custom-model hook, but with **random,
untrained weights** — it does not convert Demucs, Spleeter, or anything else.
Bundling its output changes nothing about separation quality. It's kept as an
architecture-shape starting point in case anyone wants to actually train a
model on labeled data (e.g. MUSDB18) as a research project — see its module
docstring for specifics. For actually good separation today, use
`demucs_separator.py` above.
