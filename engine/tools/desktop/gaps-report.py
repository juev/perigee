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

A run with `gaps --cap-em` leaves the lines whose gap would be wider than the ceiling ragged; the
hook marks them `capped`. Such lines still count in `lines`, `chars` and `hyph`, the `ragged`
column gives their share, and the gap columns describe only the lines that stay justified. Rows
written before the column existed count as not capped. A run with `gaps --lookahead on` marks the
lines whose break the lookahead moved `lookahead`; `moved` gives their share, and `widest` is the
widest gap left justified. A run with `gaps --paragraph on` marks the lines of paragraphs the
paragraph breaker broke `paragraph`; `para` gives their share. The columns `>0.75em` and `>0.5em`
(the gaps of --over) give the share of all the lines counted whose gap, justified, is wider.

With --costs the report reads the log of `gaps --paragraph-log` instead: the paragraphs planned in
Render(), the pass that planned them, the distribution of their cost and where the time went:

    gaps-report.py --costs runs/paragraph.log

The CLI writes a marker row `#page,N` before drawing page N; the rows after it are the lines
formatted while that page was drawn. With --by-page the report lists the pages with the most lines
over the first gap of --over, then the second, and so on:

    gaps-report.py --by-page --over 0.75 0.5 --top 10 runs/Roboto-28-1032-on.csv
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


def rows_with_pages(handle):
    """The CSV's rows with the page of the last `#page,N` marker before them (0 before any)."""
    page = [0]

    def lines():
        for line in handle:
            if line.startswith("#page,"):
                page[0] = int(line.split(",")[1])
            else:
                yield line

    for row in csv.DictReader(lines()):
        row["page"] = page[0]
        row["capped"] = row.get("capped") or "0"
        row["lookahead"] = row.get("lookahead") or "0"
        row["paragraph"] = row.get("paragraph") or "0"
        yield row


def gap_em(row, size):
    """The gap the line gets when justified: the space plus its share of the extra width, in em."""
    return (int(row["space_px"]) + int(row["extra_px"]) / int(row["gaps"])) / size


def read(path):
    """Rows of one CSV grouped by face and size, repeated lines dropped, with what was left out."""
    groups = defaultdict(list)
    seen = set()
    repeated = short = 0
    with open(path, newline="", encoding="utf-8") as handle:
        for row in rows_with_pages(handle):
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
    parser.add_argument("--by-page", action="store_true", help="list the pages with the most wide gaps instead")
    parser.add_argument("--costs", action="store_true", help="read paragraph logs of gaps --paragraph-log instead")
    parser.add_argument("--over", type=float, nargs="+", default=[0.75, 0.5],
                        help="gaps in em the table and --by-page count lines over, the first one ranking first in --by-page")
    parser.add_argument("--top", type=int, default=10, help="pages --by-page lists")
    args = parser.parse_args()
    if args.by_page:
        by_page(args)
        return
    if args.costs:
        costs(args)
        return

    header = (f"{'font':14s} {'lang':6s} {'width':>5s} {'space':>6s} {'lines':>6s} "
              f"{'p50':>6s} {'p90':>6s} {'p99':>6s}")
    header += "".join(f">{int(cap * 100)}%".rjust(8) for cap in args.caps)
    header += f" {'chars':>6s} {'hyph':>6s} {'ragged':>6s} {'moved':>6s} {'para':>6s} {'widest':>6s}"
    header += "".join(f">{em:g}em".rjust(8) for em in args.over) + "  setting"
    print("gap widths in em of the lines left justified; the last columns are the share of them needing a wider gap")
    print(header)
    notes = []
    for path in args.csv:
        groups, repeated, short = read(path)
        setting = os.path.splitext(os.path.basename(path))[0]
        for (face, size), rows in sorted(groups.items()):
            space = Counter(int(r["space_px"]) for r in rows).most_common(1)[0][0]
            usable = Counter(int(r["usable_px"]) for r in rows).most_common(1)[0][0]
            justified = [r for r in rows if r["capped"] == "0"]
            ragged = (len(rows) - len(justified)) / len(rows)
            moved = sum(1 for r in rows if r["lookahead"] == "1") / len(rows)
            para = sum(1 for r in rows if r["paragraph"] == "1") / len(rows)
            gaps = [gap_em(r, size) for r in justified]
            spaces = [int(r["space_px"]) / size for r in justified]
            condensed = sum(1 for r in rows if int(r["extra_px"]) < 0)
            chars = sum(int(r["chars"]) for r in rows) / len(rows)
            hyphens = sum(int(r["hyphen"]) for r in rows) / len(rows)
            line = (f"{face[:14]:14s} {args.lang:6s} {usable / size:5.0f} {space / size:6.3f} {len(rows):6d} "
                    + " ".join(f"{pct(gaps, p):6.2f}" for p in (50, 90, 99)))
            line += "".join(
                f"{100 * sum(1 for gap, s in zip(gaps, spaces) if gap > cap * s) / max(len(gaps), 1):6.1f}%"
                for cap in args.caps
            )
            line += (f" {chars:6.1f} {100 * hyphens:5.1f}% {100 * ragged:5.1f}% {100 * moved:5.1f}% {100 * para:5.1f}%"
                     f" {max(gaps, default=float('nan')):6.2f}")
            ems = [gap * size for gap in gaps]
            line += "".join(f"{100 * sum(1 for g in ems if g > em * size) / len(rows):7.1f}%" for em in args.over)
            line += f"  {setting}"
            print(line)
            notes.append(f"{setting} {face}: {usable} px over {size} px = {usable / size:.1f} em, "
                         f"{condensed} of {len(rows)} lines condensed below the space")
        notes.append(f"{setting}: {repeated} repeated lines dropped, {short} lines with fewer than two gaps left out")
    print()
    for note in notes:
        print(note)


def costs(args):
    """Per paragraph log, the paragraphs planned in Render(): passes, cost percentiles, time by candidates."""
    for path in args.csv:
        rows, dropped = [], 0
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("#draw"):
                    break
                if line.startswith("#dropped"):
                    dropped += 1
                elif not line.startswith("node"):
                    node, chars, cands, edges, lines, run, cost, us, hyph_us = line.strip().split(",")[:9]
                    rows.append((int(cands), int(edges), int(run), float(cost), int(us), int(hyph_us), int(lines)))
        passes = Counter(row[2] for row in rows)
        print(f"{os.path.basename(path)}: {len(rows)} paragraphs in Render(); first pass {passes[1]}, "
              f"second pass {passes[2]}, repaired {passes[3]}, left greedy {passes[0]}, plans dropped {dropped}")
        for run, name in ((1, "first pass"), (2, "second pass")):
            values = [row[3] for row in rows if row[2] == run]
            if values:
                print(f"  cost, {name}: zero {sum(1 for v in values if v == 0)} of {len(values)}, "
                      + ", ".join(f"p{p} {pct(values, p):.0f}" for p in (50, 90, 99)) + f", max {max(values):.0f}")
        total = sum(row[4] for row in rows)
        print(f"  time {total / 1000:.1f} ms, hyphenation {sum(row[5] for row in rows) / 1000:.1f} ms of it")
        print(f"  {'candidates':>12s} {'paragraphs':>10s} {'edges':>8s} {'ms':>6s}")
        for low, high in ((0, 50), (50, 100), (100, 200), (200, 400), (400, 10 ** 9)):
            part = [row for row in rows if low <= row[0] < high]
            label = f"{low}-{high - 1}" if high < 10 ** 9 else f"{low}+"
            print(f"  {label:>12s} {len(part):10d} {sum(row[1] for row in part):8d} {sum(row[4] for row in part) / 1000:6.1f}")
        print()


def by_page(args):
    """Per CSV, the pages with the most justified lines over the gaps of --over, widest gap and ragged lines."""
    for path in args.csv:
        groups, _, _ = read(path)
        pages = defaultdict(list)
        for (_, size), rows in groups.items():
            for row in rows:
                pages[row["page"]].append((row, gap_em(row, size)))
        ranked = []
        for page, lines in pages.items():
            justified = [gap for row, gap in lines if row["capped"] == "0"]
            over = tuple(sum(1 for gap in justified if gap > em) for em in args.over)
            ranked.append((over, page, len(lines), len(lines) - len(justified), max(justified, default=float("nan"))))
        ranked.sort(key=lambda item: (item[0], -item[1]), reverse=True)
        print(f"{os.path.basename(path)}: {len(pages)} pages; lines formatted while each page was drawn, "
              f"gaps in em of the lines left justified")
        print(f"{'page':>5s} {'lines':>6s} {'ragged':>6s} {'widest':>7s}"
              + "".join(f">{em:g}".rjust(7) for em in args.over))
        for over, page, lines, ragged, widest in ranked[:args.top]:
            print(f"{page:5d} {lines:6d} {ragged:6d} {widest:7.3f}" + "".join(f"{n:7d}" for n in over))
        print()


if __name__ == "__main__":
    main()
