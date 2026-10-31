#!/usr/bin/env python3
"""Converts a binary 8-bit PGM (P5), as `gaps --png` writes it, to a grayscale PNG with the standard library.

    pgm2png.py PAGE.pgm [PAGE.png] [--crop]

The PNG is 8-bit grayscale, one filter-0 scan line per row, compressed with zlib at level 9 and
without any time stamp, so the same PGM always gives the same bytes. With --crop the white rows at
the bottom of the page are left out (one white row is kept under the text); the width and the
pixels are not touched.
"""

import argparse
import struct
import sys
import zlib


def read_pgm(path):
    """Width, height and pixel bytes of a P5 file with maxval 255."""
    with open(path, "rb") as handle:
        data = handle.read()
    fields, pos = [], 0
    while len(fields) < 4:
        while data[pos:pos + 1].isspace():
            pos += 1
        if data[pos:pos + 1] == b"#":
            pos = data.index(b"\n", pos) + 1
            continue
        end = pos
        while not data[end:end + 1].isspace():
            end += 1
        fields.append(data[pos:end])
        pos = end
    magic, width, height, maxval = fields[0], int(fields[1]), int(fields[2]), int(fields[3])
    if magic != b"P5" or maxval != 255:
        sys.exit(f"pgm2png: {path} is not an 8-bit binary PGM ({magic!r}, maxval {maxval})")
    pixels = data[pos + 1:pos + 1 + width * height]
    if len(pixels) != width * height:
        sys.exit(f"pgm2png: {path} holds {len(pixels)} pixel bytes, not {width}x{height}")
    return width, height, pixels


def chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))


def write_png(path, width, height, pixels):
    rows = b"".join(b"\0" + pixels[y * width:(y + 1) * width] for y in range(height))
    with open(path, "wb") as handle:
        handle.write(b"\x89PNG\r\n\x1a\n")
        handle.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)))
        handle.write(chunk(b"IDAT", zlib.compress(rows, 9)))
        handle.write(chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("pgm")
    parser.add_argument("png", nargs="?", help="defaults to the PGM's name with .png")
    parser.add_argument("--crop", action="store_true", help="leave out the white rows at the bottom")
    args = parser.parse_args()
    width, height, pixels = read_pgm(args.pgm)
    if args.crop:
        white = b"\xff" * width
        used = height
        while used > 0 and pixels[(used - 1) * width:used * width] == white:
            used -= 1
        height = min(height, used + 1)
        pixels = pixels[:height * width]
    png = args.png or (args.pgm[:-4] if args.pgm.endswith(".pgm") else args.pgm) + ".png"
    write_png(png, width, height, pixels)
    print(f"{png}: {width}x{height}")


if __name__ == "__main__":
    main()
