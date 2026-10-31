#!/usr/bin/env python3
"""Word gaps of justified lines as crengine laid them out, from the CSV of the `gaps` CLI.

The rows come from the measurement hook of lvtextfm-gaps.patch, one per justified line, taken
before the line's leftover width is spread over its gaps. A gap is the font's space plus that
leftover divided by the number of gaps; on a line the engine had to condense the leftover is
negative and the gap comes out under the space. The table lines up with engine/tools/word-gaps.py
column for column, so the engine's figures and the model's can be read row by row:

    gaps-report.py --lang ru_RU runs/Roboto-28-1032-on.csv runs/Roboto-28-1032-off.csv

As in the model, a line counts when it has three words or more (two gaps); the last line of a
paragraph is not justified and never reaches the hook. The space is the engine's own, in whole
pixels (`space_px`, the font's advance of U+0020 at that size), so the em figures carry up to half
a pixel of rounding in it. Two columns follow the model's: characters a line, spaces included,
and the share of lines ending in a hyphen.
"""

import argparse
import csv
import os
from collections import Counter, defaultdict


def pct(values, share):
    """The model's percentile: linear between the two nearest ranks."""
    if not values:
        return float("nan")
    ordered = sorted(values)
    position = (len(ordered) - 1) * share / 100.0
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def read(path):
    """Rows of one CSV grouped by face and size, repeated lines dropped, with what was left out."""
    groups = defaultdict(list)
    seen = set()
    repeated = short = 0
    with open(path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            key = (row["node"], row["y"], row["face"], row["size_px"])
            if key in seen:
                repeated += 1
                continue
            seen.add(key)
            if int(row["gaps"]) < 2:
                short += 1
                continue
            groups[(row["face"], int(row["size_px"]))].append(row)
    return groups, repeated, short


def main():
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("csv", nargs="+", help="CSV files written by gaps --out")
    parser.add_argument("--lang", default="ru_RU", help="language label of the book, for the lang column")
    parser.add_argument("--caps", type=float, nargs="+", default=[1.5, 2.0, 2.5, 3.0])
    args = parser.parse_args()

    header = (f"{'font':14s} {'lang':6s} {'width':>5s} {'space':>6s} {'lines':>6s} "
              f"{'p50':>6s} {'p90':>6s} {'p99':>6s}")
    header += "".join(f">{int(cap * 100)}%".rjust(8) for cap in args.caps)
    header += f" {'chars':>6s} {'hyph':>6s}  setting"
    print("gap widths in em; the last columns are the share of lines needing a wider gap")
    print(header)
    notes = []
    for path in args.csv:
        groups, repeated, short = read(path)
        setting = os.path.splitext(os.path.basename(path))[0]
        for (face, size), rows in sorted(groups.items()):
            space = Counter(int(r["space_px"]) for r in rows).most_common(1)[0][0]
            usable = Counter(int(r["usable_px"]) for r in rows).most_common(1)[0][0]
            gaps = [(int(r["space_px"]) + int(r["extra_px"]) / int(r["gaps"])) / size for r in rows]
            spaces = [int(r["space_px"]) / size for r in rows]
            condensed = sum(1 for r in rows if int(r["extra_px"]) < 0)
            chars = sum(int(r["chars"]) for r in rows) / len(rows)
            hyphens = sum(int(r["hyphen"]) for r in rows) / len(rows)
            line = (f"{face[:14]:14s} {args.lang:6s} {usable / size:5.0f} {space / size:6.3f} {len(rows):6d} "
                    + " ".join(f"{pct(gaps, p):6.2f}" for p in (50, 90, 99)))
            line += "".join(
                f"{100 * sum(1 for gap, s in zip(gaps, spaces) if gap > cap * s) / len(gaps):6.1f}%"
                for cap in args.caps
            )
            line += f" {chars:6.1f} {100 * hyphens:5.1f}%  {setting}"
            print(line)
            notes.append(f"{setting} {face}: {usable} px over {size} px = {usable / size:.1f} em, "
                         f"{condensed} of {len(rows)} lines condensed below the space")
        notes.append(f"{setting}: {repeated} repeated lines dropped, {short} lines with fewer than two gaps left out")
    print()
    for note in notes:
        print(note)


if __name__ == "__main__":
    main()
