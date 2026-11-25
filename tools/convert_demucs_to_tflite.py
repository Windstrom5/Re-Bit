#!/usr/bin/env python3
"""
UNTRAINED architecture template for a mask-prediction TFLite model matching
SpectralSeparationEngine.applyTfliteInferenceMask's expected input/output shape.

READ THIS BEFORE RUNNING: despite this file's old name, it does not convert
Demucs (or Spleeter, or anything else) to TFLite. Demucs is a waveform-domain
model with architecture pieces (LSTM/transformer blocks, learned up/downsampling)
that don't map onto a simple spectrogram-in/mask-out TFLite graph, and there is
no supported one-step conversion path for it. What this script actually builds
is a small Conv2D U-Net with the RIGHT INPUT/OUTPUT SHAPE to slot into this
app's existing TFLite hook (SpectralSeparationEngine.loadCustomModel /
applyTfliteInferenceMask) — but with RANDOM, UNTRAINED weights. Running it and
bundling the result will NOT separate vocals; a randomly-initialized network
run through sigmoid activations outputs masks hovering near a constant ~0.5,
which just attenuates everything uniformly. There is no trained model here.

Making this real requires training on paired (mix, vocals, instrumental)
spectrogram data — e.g. adapting the loss/data pipeline from an open dataset
such as MUSDB18 — which is a genuine research project, not a script. If you
just want good separation today, use tools/demucs_separator.py instead: it
wraps the real, already-trained Demucs model, runs on desktop, and its output
is picked up automatically and played back bit-perfectly (zero DSP) by
ExternalStemResolver — see tools/README_DEMUCS.md.

Usage (produces a same-shaped but useless, untrained model — see above):
    python tools/convert_demucs_to_tflite.py --output app/src/main/assets/models/vocal_separator.tflite
"""

import os
import sys
import argparse

def export_tflite_model(output_path: str = "app/src/main/assets/models/vocal_separator.tflite"):
    try:
        import tensorflow as tf
        print(f"[✓] TensorFlow {tf.__version__} loaded.")
    except ImportError:
        print("[!] TensorFlow not installed. Installing tensorflow...")
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "tensorflow"])
        import tensorflow as tf

    out_dir = os.path.dirname(output_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    print("[+] Building UNTRAINED Conv2D U-Net architecture template (random weights)...")
    # Must match SpectralSeparationEngine's fftSize=2048 -> halfFft+1 = 1025 bins.
    # The old version of this script used 513 bins (a 1024-point FFT), which
    # wouldn't even load against the app's actual inference tensor shapes.
    fft_bins = 1025
    time_steps = 1  # Instantaneous frame-by-frame STFT inference

    # Input: [Batch, FreqBins, TimeSteps, Channels(2)] — magnitude(L), magnitude(R)
    inputs = tf.keras.Input(shape=(fft_bins, time_steps, 2), name="spectrogram_input")

    # Encoder
    x = tf.keras.layers.Conv2D(16, (5, 1), padding="same", activation="relu")(inputs)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.Conv2D(32, (5, 1), padding="same", activation="relu")(x)
    x = tf.keras.layers.BatchNormalization()(x)

    # Bottleneck
    x = tf.keras.layers.Conv2D(64, (3, 1), padding="same", activation="relu")(x)

    # Decoder
    x = tf.keras.layers.Conv2D(32, (5, 1), padding="same", activation="relu")(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.Conv2D(16, (5, 1), padding="same", activation="relu")(x)

    # Output soft mask, one per channel: [0.0, 1.0]
    masks = tf.keras.layers.Conv2D(2, (1, 1), padding="same", activation="sigmoid", name="vocal_mask")(x)

    model = tf.keras.Model(inputs=inputs, outputs=masks, name="vocal_separator_unet_UNTRAINED")

    print("[+] Converting to TensorFlow Lite (.tflite) with FP16 weights...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]

    tflite_model = converter.convert()

    with open(output_path, "wb") as f:
        f.write(tflite_model)

    print(f"[✓] Wrote UNTRAINED template model to: {output_path} ({len(tflite_model) / 1024:.1f} KB)")
    print("[!] This model has never seen training data. Bundling it will NOT improve")
    print("    separation quality over the built-in DSP heuristic — see this file's")
    print("    module docstring for what training it for real would actually take.")

def main():
    parser = argparse.ArgumentParser(
        description="Export an untrained mask-prediction model architecture template to TFLite (see module docstring — this does not produce a working separator)."
    )
    parser.add_argument("--output", "-o", default="app/src/main/assets/models/vocal_separator.tflite", help="Output .tflite model file path")
    args = parser.parse_args()

    export_tflite_model(args.output)

if __name__ == "__main__":
    main()
