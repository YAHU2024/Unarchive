"""Create gz/zip/bz2 copies of model.int8.onnx for decompress benchmarking.

Run:  python make_artifacts.py <onnx-path> <out-dir>
Outputs: model.int8.onnx.gz / .zip / .bz2 (best-effort; sizes logged).
"""
import bz2
import gzip
import os
import sys
import time
import zipfile

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
CHUNK = 4 * 1024 * 1024
base = os.path.basename(SRC)
src_size = os.path.getsize(SRC)
print(f"source {SRC} {src_size / 1e6:.1f} MB", flush=True)


def make(path, opener):
    t0 = time.time()
    with open(SRC, "rb") as fin, opener(path, "wb") as fout:
        while True:
            block = fin.read(CHUNK)
            if not block:
                break
            fout.write(block)
    dt = time.time() - t0
    print(f"{path} {os.path.getsize(path) / 1e6:.1f} MB compress {dt:.1f}s", flush=True)


make(os.path.join(OUT, base + ".gz"), lambda p, w: gzip.open(p, "wb", compresslevel=6))

with zipfile.ZipFile(os.path.join(OUT, base + ".zip"), "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
    t0 = time.time()
    zf.write(SRC, arcname=base)
    print(f"zip done {(time.time() - t0):.1f}s", flush=True)
print(f"zip size {os.path.getsize(os.path.join(OUT, base + '.zip')) / 1e6:.1f} MB", flush=True)

make(os.path.join(OUT, base + ".bz2"), lambda p, w: bz2.open(p, "wb", compresslevel=9))
print("ALL DONE", flush=True)
