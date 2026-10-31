#!/usr/bin/env python3
"""Counts the grey levels of the binary 8-bit PGM pages `gaps --png` writes, with the standard library.

    pgm-levels.py PAGE.pgm [PAGE.pgm ...] [--min-pixels 20]

Per file: the text pixels, which are all pixels that are not white (255), and the levels, the number
of distinct 8-bit values, white included, that occur on at least --min-pixels pixels of the page. The
threshold keeps a value met on a stray pixel or two out of the count. A page drawn without
antialiasing has two levels, white and black; an antialiased one has as many as its edges produce.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pgm2png import read_pgm  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("pgm", nargs="+")
    parser.add_argument("--min-pixels", type=int, default=20,
                        help="pixels a value needs to count as a level (20)")
    args = parser.parse_args()
    if args.min_pixels < 1:
        parser.error("--min-pixels wants 1 or more")
    print(f"{'text_px':>9} {'levels':>6} {'black':>9} {'grey':>9}  file")
    for path in args.pgm:
        _, _, pixels = read_pgm(path)
        histogram = [0] * 256
        for value in pixels:
            histogram[value] += 1
        text = len(pixels) - histogram[255]
        levels = sum(1 for count in histogram if count >= args.min_pixels)
        # Ink pixels that are neither black nor white: the antialiased edges.
        grey = text - histogram[0]
        print(f"{text:>9} {levels:>6} {histogram[0]:>9} {grey:>9}  {path}")


if __name__ == "__main__":
    main()
