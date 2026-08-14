"""Build a ~5.6-minute 16 kHz mono WAV by repeating a sample with gaps.

Usage: python make_long_sample.py <src.wav> <out.wav> [repeat=5]
Reads the source, downmixes to mono, decimates to 16 kHz, and concatenates
`repeat` copies separated by 0.3 s of silence.
"""
import sys
import wave


def main():
    src, out = sys.argv[1], sys.argv[2]
    repeat = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    with wave.open(src, "rb") as w:
        channels = w.getnchannels()
        rate = w.getframerate()
        width = w.getsampwidth()
        frames = w.getnframes()
        data = w.readframes(frames)
    assert width == 2, "expected 16-bit PCM"
    samples = [
        int.from_bytes(data[i:i + 2], "little", signed=True)
        for i in range(0, len(data), 2)
    ]
    mono = []
    for i in range(0, len(samples) - channels + 1, channels):
        mono.append(sum(samples[i:i + channels]) // channels)
    decim = max(1, rate // 16_000)
    mono16k = mono[::decim]
    gap = [0] * (int(0.3 * 16_000))
    blocks = []
    for i in range(repeat):
        blocks.append(mono16k)
        if i < repeat - 1:
            blocks.append(gap)
    out_data = b"".join(
        (s & 0xFFFF).to_bytes(2, "little") for block in blocks for s in block
    )
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16_000)
        w.writeframes(out_data)
    dur = len(blocks) * len(blocks[0]) / 16_000 if blocks else 0
    print(f"wrote {out}: mono 16k, {dur:.1f}s, {len(out_data)/1e6:.1f} MB")


if __name__ == "__main__":
    main()
