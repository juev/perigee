#!/usr/bin/env python3
"""Blog posts (or a plain text file) to an FB2 with one <p> per paragraph.

    mkbook.py --posts DIR --lang ru --limit 1800 --out book.fb2
    mkbook.py --text novel.txt --lang en --limit 1800 --out book.fb2
"""
import argparse
import glob
import html
import os
import re


def posts(directory):
    for path in sorted(glob.glob(os.path.join(directory, "*.markdown")) + glob.glob(os.path.join(directory, "*.md"))):
        text = open(path, encoding="utf-8").read()
        text = re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S)
        text = re.sub(r"```.*?```", "", text, flags=re.S)
        text = re.sub(r"\{%.*?%\}", "", text, flags=re.S)
        yield from text.split("\n\n")


def clean(block):
    block = block.strip()
    if not block or block[0] in "#|>\t" or block.startswith(("    ", "[^", "![", "<", "---", "* ", "- ", "1. ")):
        return None
    block = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", block)
    block = re.sub(r"\[\^[^\]]*\]", "", block)
    block = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", block)
    block = re.sub(r"\[([^\]]*)\]\[[^\]]*\]", r"\1", block)
    block = re.sub(r"[*_`]{1,3}", "", block)
    block = re.sub(r"<[^>]+>", "", block)
    block = re.sub(r"\s+", " ", block).strip()
    if len(block) < 80 or "http" in block:
        return None
    return block


def plain(path):
    text = open(path, encoding="utf-8").read().replace("\r\n", "\n")
    yield from text.split("\n\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--posts")
    parser.add_argument("--text")
    parser.add_argument("--lang", required=True)
    parser.add_argument("--limit", type=int, default=1800)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    source = posts(args.posts) if args.posts else plain(args.text)
    paragraphs = []
    for block in source:
        block = clean(block)
        if block:
            paragraphs.append(block)
        if len(paragraphs) >= args.limit:
            break
    body = "\n".join("<p>%s</p>" % html.escape(p, quote=False) for p in paragraphs)
    with open(args.out, "w", encoding="utf-8") as out:
        out.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>'
            "<genre>prose</genre><author><first-name>T</first-name><last-name>T</last-name></author>"
            "<book-title>Corpus</book-title><lang>%s</lang></title-info></description>"
            "<body><section>\n%s\n</section></body></FictionBook>\n" % (args.lang, body)
        )
    print(args.out, len(paragraphs), "paragraphs", os.path.getsize(args.out), "bytes")


if __name__ == "__main__":
    main()
