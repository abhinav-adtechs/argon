#!/usr/bin/env python3
# Cuts a seamless, loudness-matched OGG loop out of a longer field recording.
# Usage: tools/make_loop.py in.(ogg|mp3|wav) out.ogg start_s length_s [highpass_hz]
import json
import subprocess
import sys

import numpy as np
import soundfile as sf
from scipy import ndimage, signal

SR = 44100
CROSSFADE_S = 4.0
TARGET_LUFS = -26.0
PEAK_CAP = 10 ** (-1.5 / 20)


def load(path, start, length):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(start), "-t", str(length), "-i", path,
                          "-f", "f32le", "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    info = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                           "stream=channels", "-of", "json", path], capture_output=True, check=True).stdout
    channels = json.loads(info)["streams"][0]["channels"]
    return np.frombuffer(raw, np.float32).reshape(-1, channels).astype(np.float64)


def loudness(x):
    out = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-f", "f32le", "-ar", str(SR),
                          "-ac", str(x.shape[1]), "-i", "-", "-af", "ebur128", "-f", "null", "-"],
                         input=x.astype(np.float32).tobytes(), capture_output=True).stderr.decode()
    return float(out.rsplit("I:", 1)[1].split("LUFS")[0])


def main():
    src, dst, start, length = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])
    highpass = float(sys.argv[5]) if len(sys.argv) > 5 else 60.0
    fade = int(CROSSFADE_S * SR)
    x = load(src, start, length + CROSSFADE_S)
    x = signal.sosfiltfilt(signal.butter(4, highpass, "highpass", fs=SR, output="sos"), x, axis=0)

    body = x[:-fade].copy()
    t = np.linspace(0, np.pi / 2, fade)[:, None]
    body[:fade] = body[:fade] * np.sin(t) + x[-fade:] * np.cos(t)

    # Close bird calls are far louder than the bed, so tame peaks with a 10 ms look-ahead limiter
    # instead of letting one chirp set the gain for the whole loop.
    win = int(0.01 * SR)
    for _ in range(4):
        body *= 10 ** ((TARGET_LUFS - loudness(body)) / 20)
        env = ndimage.maximum_filter1d(np.abs(body).max(1), 2 * win + 1)
        g = ndimage.uniform_filter1d(ndimage.minimum_filter1d(np.minimum(1, PEAK_CAP / env), 2 * win + 1), 2 * win + 1)
        body *= g[:, None]
    # libsndfile's Vorbis encoder segfaults on very large single writes, so feed it in blocks.
    with sf.SoundFile(dst, "w", SR, body.shape[1], format="OGG", subtype="VORBIS", compression_level=0.55) as f:
        for i in range(0, len(body), 8192):
            f.write(body[i:i + 8192].astype(np.float32))
    print(f"{dst}: {len(body) / SR:.1f}s, {loudness(body):.1f} LUFS, peak {20 * np.log10(np.abs(body).max()):.1f} dBFS")


if __name__ == "__main__":
    main()
