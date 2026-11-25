#!/usr/bin/env python3
"""
Demucs Neural Audio Stem Separator for BitPerfectUSB.

Uses Meta AI's Demucs (Hybrid Transformer Demucs / HTDemucs) deep neural network
to separate audio tracks into studio-grade stems:
- Vocals (Acapella)
- Instrumental (No Vocals = Drums + Bass + Other)
- Drums
- Bass
- Other / Synth

Requirements:
    pip install demucs torch torchaudio soundfile

Usage:
    python tools/demucs_separator.py --input "path/to/song.flac" --output "./separated" --model htdemucs
    python tools/demucs_separator.py --input "path/to/song.flac" --push-to-phone
"""

import os
import sys
import argparse
import subprocess
from pathlib import Path

def check_dependencies():
    try:
        import torch
        import demucs.separate
        print(f"[✓] PyTorch {torch.__version__} and Demucs found.")
        if torch.cuda.is_available():
            print(f"[✓] GPU Acceleration Active: {torch.cuda.get_device_name(0)}")
        else:
            print("[!] Running on CPU (install CUDA PyTorch for faster inference).")
    except ImportError:
        print("[!] Demucs not installed. Installing required dependencies...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "demucs", "torch", "torchaudio", "soundfile"])

def separate_audio(input_file: str, output_dir: str = "./separated_stems", model: str = "htdemucs", two_stems: str = "vocals"):
    """
    Runs Demucs neural source separation on the input audio file.
    """
    input_path = Path(input_file).resolve()
    if not input_path.exists():
        print(f"[-] Error: Input audio file not found at '{input_path}'")
        return None

    out_path = Path(output_dir).resolve()
    out_path.mkdir(parents=True, exist_ok=True)

    print(f"\n[+] Starting Demucs Neural Stem Separation...")
    print(f"    Input File : {input_path.name}")
    print(f"    Model      : {model} (State-of-the-Art Hybrid Transformer)")
    print(f"    Output Dir : {out_path}\n")

    cmd = [
        sys.executable, "-m", "demucs.separate",
        "-n", model,
        "--out", str(out_path),
        "--flac"
    ]

    if two_stems:
        cmd.extend(["--two-stems", two_stems]) # Generates vocals and no_vocals (instrumental)

    cmd.append(str(input_path))

    result = subprocess.run(cmd)
    if result.returncode == 0:
        print(f"\n[✓] Separation Complete! High-definition stems saved in '{out_path}/{model}/{input_path.stem}/'")
        return out_path / model / input_path.stem
    else:
        print("[-] Demucs separation encountered an error.")
        return None

def push_to_phone(stem_dir: Path, track_name: str):
    """
    Pushes the separated instrumental and vocal tracks to the connected Android
    device via ADB, into the fixed folder BitPerfectUSB's ExternalStemResolver
    checks (Music/BitPerfect_Stems). Each pushed file is prefixed with the
    source track's own name — e.g. "Song Title_no_vocals.flac" — because Demucs
    always names its own output "vocals.flac"/"no_vocals.flac" regardless of
    the source; pushing those names as-is into one shared phone folder would
    silently overwrite the previous song's stems the next time this runs.
    track_name should match the title BitPerfectUSB shows for the track in its
    library (it's matched against the on-device file's display name minus
    extension) — safest is to pass the source file's own base name, i.e. don't
    rename the source file before running this script.
    """
    print(f"\n[+] Checking ADB connection for Android device...")
    try:
        adb_check = subprocess.run(["adb", "devices"], capture_output=True, text=True)
        if "device\n" not in adb_check.stdout and "\tdevice" not in adb_check.stdout:
            print("[-] No Android device detected via ADB. Please connect your phone with USB Debugging enabled.")
            return

        phone_music_dir = "/sdcard/Music/BitPerfect_Stems"
        subprocess.run(["adb", "shell", "mkdir", "-p", phone_music_dir])

        # Demucs' own two-stems output is always named exactly "vocals.flac" /
        # "no_vocals.flac" — map those to the resolver's expected suffixes.
        rename_map = {
            "no_vocals.flac": f"{track_name}_no_vocals.flac",
            "vocals.flac": f"{track_name}_vocals.flac",
        }

        pushed_paths = []
        for file in stem_dir.glob("*.flac"):
            remote_name = rename_map.get(file.name, f"{track_name}_{file.name}")
            remote_path = f"{phone_music_dir}/{remote_name}"
            print(f"    Pushing {file.name} -> {remote_path} ...")
            subprocess.run(["adb", "push", str(file), remote_path])
            pushed_paths.append(remote_path)

        # Trigger the Android media scanner per file (a folder-level scan
        # intent doesn't reliably index newly pushed files on every OS version;
        # per-file does) so MediaStore picks these up immediately rather than
        # waiting for the next full-device scan.
        for remote_path in pushed_paths:
            subprocess.run([
                "adb", "shell", "am", "broadcast",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", f"file://{remote_path}"
            ])
        print(f"[✓] Successfully transferred studio stems to phone: '{phone_music_dir}'!")
        print(f"    BitPerfectUSB will pick these up automatically next time you play '{track_name}' with karaoke mode on.")
    except FileNotFoundError:
        print("[-] ADB not found in system PATH. Install Android platform-tools to use --push-to-phone.")

def main():
    parser = argparse.ArgumentParser(description="Demucs Neural Stem Separation for BitPerfectUSB")
    parser.add_argument("--input", "-i", required=True, help="Path to input audio file (.flac, .wav, .mp3)")
    parser.add_argument("--output", "-o", default="./separated_stems", help="Output directory for stems")
    parser.add_argument("--model", "-m", default="htdemucs", choices=["htdemucs", "htdemucs_ft", "mdx_extra_q"], help="Demucs neural model")
    parser.add_argument("--all-stems", action="store_true", help="Separate all 4 stems (vocals, drums, bass, other) instead of 2 stems")
    parser.add_argument("--push-to-phone", "-p", action="store_true", help="Push separated stems directly to phone via ADB")

    args = parser.parse_args()
    check_dependencies()

    two_stems_arg = None if args.all_stems else "vocals"
    stems_path = separate_audio(args.input, args.output, args.model, two_stems_arg)

    if stems_path and args.push_to_phone:
        track_name = Path(args.input).stem
        push_to_phone(stems_path, track_name)

if __name__ == "__main__":
    main()
