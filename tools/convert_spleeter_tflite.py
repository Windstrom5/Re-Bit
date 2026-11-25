#!/usr/bin/env python3
"""
Spleeter / Demucs TFLite Model Exporter for Re:Bit Audio Player

This script converts Deezer's pretrained Spleeter 2stems checkpoint (vocals + accompaniment)
into a TensorFlow Lite (.tflite) model optimized for on-device inference on Android.

Requirements:
    pip install tensorflow==2.15.0 spleeter requests

Usage:
    python tools/convert_spleeter_tflite.py --output app/src/main/assets/models/spleeter_2stems.tflite
"""

import os
import sys
import argparse
import tarfile
import urllib.request
import numpy as np

def download_spleeter_checkpoint(target_dir):
    url = "https://github.com/deezer/spleeter/releases/download/v1.4.0/2stems.tar.gz"
    archive_path = os.path.join(target_dir, "2stems.tar.gz")
    extracted_dir = os.path.join(target_dir, "2stems")

    if os.path.exists(extracted_dir) and os.path.exists(os.path.join(extracted_dir, "model.meta")):
        print(f"[+] Found existing Spleeter 2stems checkpoint in {extracted_dir}")
        return extracted_dir

    print(f"[*] Downloading Spleeter 2stems checkpoint from {url}...")
    os.makedirs(target_dir, exist_ok=True)
    urllib.request.urlretrieve(url, archive_path)

    print("[*] Extracting checkpoint...")
    with tarfile.open(archive_path, "r:gz") as tar:
        tar.extractall(extracted_dir)

    print(f"[+] Extracted checkpoint to {extracted_dir}")
    return extracted_dir

def convert_to_tflite(checkpoint_dir, output_path):
    import tensorflow as tf

    print("[*] Initializing TensorFlow session and importing graph...")
    tf.compat.v1.disable_eager_execution()

    with tf.compat.v1.Session() as sess:
        meta_graph_path = os.path.join(checkpoint_dir, "model.meta")
        saver = tf.compat.v1.train.import_meta_graph(meta_graph_path)
        saver.restore(sess, os.path.join(checkpoint_dir, "model"))

        graph = tf.compat.v1.get_default_graph()

        # Spleeter 2-stems input and output tensor names
        # Audio input is typically waveform [batch, samples, 2] or [samples, 2]
        # Outputs are vocals and accompaniment
        input_tensor = graph.get_tensor_by_name("waveform:0")
        vocals_tensor = graph.get_tensor_by_name("strided_slice_13:0") # Or instrument output 0
        accomp_tensor = graph.get_tensor_by_name("strided_slice_23:0") # Or instrument output 1

        print(f"[*] Input: {input_tensor.name}, Shape: {input_tensor.shape}")
        print(f"[*] Vocals Output: {vocals_tensor.name}")
        print(f"[*] Accompaniment Output: {accomp_tensor.name}")

        converter = tf.compat.v1.lite.TFLiteConverter.from_session(
            sess, [input_tensor], [vocals_tensor, accomp_tensor]
        )
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        converter.optimizations = [tf.lite.Optimize.DEFAULT]

        print("[*] Converting graph to TFLite with Select TF Ops (Flex)...")
        tflite_model = converter.convert()

        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(tflite_model)

        print(f"[SUCCESS] Exported Spleeter 2stems TFLite model to {output_path} ({len(tflite_model) / (1024*1024):.2f} MB)")

def main():
    parser = argparse.ArgumentParser(description="Convert Spleeter 2stems to TFLite for Re:Bit Android")
    parser.add_argument("--output", default="app/src/main/assets/models/spleeter_2stems.tflite",
                        help="Target output path for .tflite model")
    parser.add_argument("--cache-dir", default="tools/cache",
                        help="Temporary directory to cache checkpoint downloads")
    args = parser.parse_args()

    checkpoint_dir = download_spleeter_checkpoint(args.cache_dir)
    convert_to_tflite(checkpoint_dir, args.output)

if __name__ == "__main__":
    main()
