#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成多种可循环的报警音 WAV（16-bit PCM，单声道，44100Hz）。
无第三方依赖，纯标准库。输出到 app/src/main/res/raw/。
"""
import math
import os
import struct
import wave

SR = 44100
AMP = 30000  # 16-bit 振幅（留裕量防削波）

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
OUT_DIR = os.path.abspath(OUT_DIR)


def _write(name, samples):
    path = os.path.join(OUT_DIR, name)
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = b"".join(struct.pack("<h", int(max(-32767, min(32767, s)))) for s in samples)
        w.writeframes(frames)
    print("wrote", path, len(samples), "samples")


def _tone(freq, dur, vol=1.0):
    n = int(SR * dur)
    out = []
    for i in range(n):
        # 5ms 淡入淡出，避免爆音（利于无缝循环）
        env = 1.0
        fade = int(SR * 0.005)
        if i < fade:
            env = i / fade
        elif i > n - fade:
            env = (n - i) / fade
        out.append(AMP * vol * env * math.sin(2 * math.pi * freq * i / SR))
    return out


def _silence(dur):
    return [0.0] * int(SR * dur)


def gen_siren():
    """双音警笛：救护车式高低两音交替，循环无缝。"""
    seq = []
    for _ in range(3):
        seq += _tone(960, 0.45)
        seq += _tone(760, 0.45)
    _write("siren.wav", seq)


def gen_beep():
    """急促滴滴：短促高频哔声 + 间隔，节奏紧张。"""
    seq = []
    for _ in range(8):
        seq += _tone(1180, 0.10)
        seq += _silence(0.09)
    _write("beep.wav", seq)


def gen_pulse():
    """脉冲蜂鸣：三连击 + 停顿，类似电子警报。"""
    seq = []
    for _ in range(4):
        for _ in range(3):
            seq += _tone(1040, 0.09)
            seq += _silence(0.05)
        seq += _silence(0.18)
    _write("pulse.wav", seq)


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    gen_siren()
    gen_beep()
    gen_pulse()
    print("done")
