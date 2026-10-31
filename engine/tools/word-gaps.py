#!/usr/bin/env python3
"""Gap widths of greedily broken, justified lines, measured with real font advances.

This is a model, not the engine. It answers "how wide do the word gaps get, and how
many lines would a ceiling on the gap send ragged" before any code is written; the
engine's own numbers come from the page images and, later, from a hook over
`lvtextfm.cpp`. Kerning, crengine's second-chance hyphenation and its punctuation
rules are not modelled, so the figures come out a little loose: read them as the
shape and the trend.

It needs fontTools and pyphen, in a scratch environment outside the repository:

    python3 -m venv "$TMPDIR/ft" && "$TMPDIR/ft/bin/pip" -q install fonttools pyphen
    "$TMPDIR/ft/bin/python" -u engine/tools/word-gaps.py \
        --fonts engine/src/main/assets/crengine/fonts \
        --corpus ru_RU='/path/to/posts/*.markdown' \
        --corpus en_US=/path/to/novel.txt \
        --width-em 24 30 --limit-chars 200000

Widths are in em: an advance is divided by the font's unitsPerEm, so `--width-em 30`
is a line 30 ems wide, about 60 characters at 0.5 em a letter. `--width-em` is the
page's text width divided by the font size: 1032 px of text at a 28 px font is
36.9 em. Every line is justified except the last of its paragraph; hyphenation uses
the pyphen dictionary named in the corpus label; a word wider than the line is placed
alone, as an engine would.

The gap is the font's own space plus the line's leftover width divided over its gaps.
A gap's ratio to the font's space is not comparable between families — space widths
run from 0.20 em in Literata to 0.29 em in Comfortaa — so the em figures are the ones
to compare, and the cap columns are printed against the share of the font's space.
"""

import argparse
import functools
import glob
import os
import re
import sys

FRONTMATTER = re.compile(r"\A---\n.*?\n---\n", re.S)
MD_LINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")
MD_MARKS = re.compile(r"[#*_>`|~]")
QUOTED_WORD = re.compile(r"^([\u00ab\u201e\"'(\[]*)([^\W\d_]+)([\u00bb\u201c\"')\],.;:!?]*)$")

_HYPHENATORS = {}


def hyphenator(lang):
    if lang not in _HYPHENATORS:
        import pyphen

        _HYPHENATORS[lang] = pyphen.Pyphen(lang=lang)
    return _HYPHENATORS[lang]


@functools.lru_cache(maxsize=None)
def cuts(word, lang):
    """Hyphenation candidates for a word, longest first, as 'prefix-' pieces."""
    match = QUOTED_WORD.match(word)
    if not match:
        return ()
    lead, core, _ = match.groups()
    if len(core) < 6:
        return ()
    try:
        positions = hyphenator(lang).positions(core)
    except Exception as error:  # an unknown dictionary must not stop the report
        print(f"pyphen: no dictionary for {lang}: {error}", file=sys.stderr)
        return ()
    start = len(lead)
    return tuple(word[: start + i] + "-" for i in positions if 2 <= i <= len(core) - 3)


def load_corpus(specs, min_para, max_para):
    chunks = {}
    for spec in specs:
        lang, _, pattern = spec.partition("=")
        if not pattern:
            raise SystemExit(f"--corpus wants LANG=PATH, got {spec!r}")
        bucket = chunks.setdefault(lang, [])
        names = sorted(glob.glob(pattern)) or [pattern]
        for name in names:
            with open(name, encoding="utf-8", errors="ignore") as handle:
                text = handle.read()
            text = FRONTMATTER.sub("", text)
            text = MD_LINK.sub(r"\1", text)
            bucket.append(MD_MARKS.sub(" ", text))
    corpora = {}
    for lang, parts in chunks.items():
        paragraphs = []
        for chunk in re.split(r"\n\s*\n", "\n\n".join(parts)):
            paragraph = re.sub(r"\s+", " ", chunk).strip()
            if min_para < len(paragraph) < max_para:
                paragraphs.append(paragraph)
        corpora[lang] = paragraphs
    return corpora


def load_fonts(directory):
    from fontTools.ttLib import TTFont

    faces = []
    for path in sorted(glob.glob(os.path.join(directory, "*.ttf")) + glob.glob(os.path.join(directory, "*.otf"))):
        stem = os.path.splitext(os.path.basename(path))[0]
        if re.search(r"italic|bold", stem, re.I):
            continue
        font = TTFont(path, fontNumber=0)
        upm = font["head"].unitsPerEm
        cmap = font.getBestCmap()
        hmtx = font["hmtx"]
        if 32 not in cmap:
            continue
        advances = {code: hmtx[cmap[code]][0] / upm for code in cmap}
        space = hmtx[cmap[32]][0] / upm
        faces.append((stem, advances, space))
    return faces


def measure(paragraphs, lang, advances, space, width_em):
    """Widths, in em, of the gap every justified line needs."""
    cached = {}

    def word_width(word):
        got = cached.get(word)
        if got is None:
            got = sum(advances.get(ord(ch), space) for ch in word)
            cached[word] = got
        return got

    gaps = []
    for paragraph in paragraphs:
        words = paragraph.split(" ")
        index = 0
        while index < len(words):
            line, width = [], 0.0
            while index < len(words):
                word = words[index]
                separator = space if line else 0.0
                if width + separator + word_width(word) <= width_em:
                    line.append(word)
                    width += separator + word_width(word)
                    index += 1
                    continue
                for piece in cuts(word, lang):
                    if width + separator + word_width(piece) <= width_em:
                        line.append(piece)
                        width += separator + word_width(piece)
                        words[index] = word[len(piece) - 1:]
                        break
                else:
                    if not line:  # a word wider than the line, placed alone
                        line.append(word)
                        width = word_width(word)
                        index += 1
                break
            # The last line of a paragraph is not justified, and counting it as one
            # would put a large, unreal leftover into the tail of the distribution.
            if len(line) > 2 and index < len(words):
                leftover = width_em - width
                gaps.append(space + leftover / (len(line) - 1))
    return gaps


def pct(values, share):
    if not values:
        return float("nan")
    ordered = sorted(values)
    position = (len(ordered) - 1) * share / 100.0
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def main():
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--fonts", required=True, help="directory with .ttf/.otf files")
    parser.add_argument("--corpus", action="append", required=True, metavar="LANG=PATH",
                        help="pyphen language and a glob of text files, repeatable")
    parser.add_argument("--width-em", type=float, nargs="+", default=[24.0, 30.0],
                        help="text width divided by the font size, in ems")
    parser.add_argument("--min-para", type=int, default=200)
    parser.add_argument("--max-para", type=int, default=1500)
    parser.add_argument("--limit-chars", type=int, default=200000)
    parser.add_argument("--caps", type=float, nargs="+", default=[1.5, 2.0, 2.5, 3.0])
    args = parser.parse_args()

    corpora = load_corpus(args.corpus, args.min_para, args.max_para)
    corpora = {lang: paras[: max(1, args.limit_chars // 400)] for lang, paras in corpora.items()}
    faces = load_fonts(args.fonts)
    if not faces:
        raise SystemExit(f"no usable fonts in {args.fonts}")

    header = (f"{'font':14s} {'lang':6s} {'width':>5s} {'space':>6s} {'lines':>6s} "
              f"{'p50':>6s} {'p90':>6s} {'p99':>6s}")
    header += "".join(f">{int(cap * 100)}%".rjust(8) for cap in args.caps)
    print("gap widths in em; the last columns are the share of lines needing a wider gap")
    print(header)
    for stem, advances, space in faces:
        for lang, paragraphs in corpora.items():
            for width_em in args.width_em:
                gaps = measure(paragraphs, lang, advances, space, width_em)
                if not gaps:
                    continue
                row = (f"{stem[:14]:14s} {lang:6s} {width_em:5.0f} {space:6.3f} {len(gaps):6d} "
                       + " ".join(f"{pct(gaps, p):6.2f}" for p in (50, 90, 99)))
                row += "".join(
                    f"{100 * sum(1 for gap in gaps if gap > cap * space) / len(gaps):6.1f}%"
                    for cap in args.caps
                )
                print(row)


if __name__ == "__main__":
    main()
