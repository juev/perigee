#!/usr/bin/env python3
"""Writes the synthetic books of the owner-css check (issue #150) into DIR:

  matrix.epub  one chapter whose elements each meet one case of the precedence matrix: a linked book
               stylesheet, style= attributes, an EPUB 3 footnote and pseudo-elements;
  large.epub   the same chapter and filler chapters after it, stored uncompressed so that the file is
               over the 30000 bytes from which the engine keeps a layout cache;
  fonts.fb2    an FB2 whose stylesheet has `@font-face { font-family: "Book Alias"; src: local("Literata"); }`.
               FB2 has no fragments, so the engine lists that face under the same source and fragment as an
               owner's `@font-face` of the same source; over 30000 bytes, for the layout cache;
  reference-*  the same books under other names: the engine keys its cache by file name and CRC, so
               the references the check compares with never read the cache of the books under check.

Usage: owner-css-books.py DIR
"""
import os
import sys
import zipfile

BOOK_CSS = """
p { text-indent: 2em; margin-right: 1em; color: #222222; letter-spacing: 1px; font-family: "Roboto"; }
p.bimp { text-indent: 4em !important; margin-left: 3em !important; text-align: right !important; }
p.lh { line-height: 1.7; }
p.iu3 { margin-left: 3em; }
#d_unmatched { text-indent: 7em; text-align: right; }
#d_iu { text-align: right; }
#p_ref::before { content: "B" !important; }
p.ba::after { content: "A"; display: none !important; }
p.fl::first-letter { display: none !important; }
p.fline::first-line { display: none !important; }
"""

MATRIX = """
<div id="wrap">
<p id="p_plain">Plain paragraph of the book, styled only by the book stylesheet.</p>
<p id="p_bookimp" class="bimp">Paragraph whose indent, left margin and alignment the book marks important.</p>
<p id="p_inline" style="text-indent: 5em; margin-left: 2em; text-align: left">Inline ordinary declarations.</p>
<p id="p_inlineimp" style="text-indent: 6em !important; margin-left: 4em !important">Inline important declarations.</p>
<p id="p_owncls" class="own">Owner specificity and source order.</p>
<p id="p_ownimp" class="own2">Owner explicit important against a more specific ordinary owner rule.</p>
<p id="p_ownimp2" class="own3">Owner important against owner important.</p>
<p id="p_sh" class="sh">Shorthand then longhand.</p>
<p id="p_sh2" class="sh2">Longhand then shorthand.</p>
<p id="p_lh" class="lh">A paragraph with a line height of the book's own.</p>
<p id="p_ff" class="ff">A paragraph in the face of an owner's font-face rule.</p>
<p id="p_str" class="str">Strings, comments and functions in the owner's rules.</p>
<p id="p_sup" class="sup">Conditional blocks.</p>
<div id="d_unmatched">An unmatched div: none of the owner rules select it.</div>
<p id="p_marker"><i id="i_marker" class="perigee-owner-css">Italic text with the class of the engine's hash rule.</i></p>
<div id="d_inherit"><span id="s_inherit">A span in a div the owner styles.</span></div>
<div id="d_iu"><p id="p_iu1" class="iu1">Inherit.</p><p id="p_iu2" class="iu2">Initial.</p><p id="p_iu3" class="iu3">Unset.</p></div>
</div>
<p id="p_ref">Text with a note<a epub:type="noteref" href="#fn1">1</a>.</p>
<p id="p_ba" class="ba">An ::after the book hides.</p>
<p id="p_oc" class="oc">A ::before only the owner asks for.</p>
<p id="p_oi" class="oi">A ::before of two owner rules.</p>
<p id="p_fl" class="fl">First letter the book hides.</p>
<p id="p_fline" class="fline">First line the book hides.</p>
<aside epub:type="footnote" id="fn1"><p id="p_fn">Footnote paragraph.</p></aside>
"""

FILLER = ("Filler paragraph {n} of chapter {c}: the quick brown fox jumps over the lazy dog, "
          "and the slow grey wolf watches it from the hill above the river bank.")

CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""


def chapter(title, body):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
<head><title>{title}</title><link rel="stylesheet" type="text/css" href="book.css"/></head>
<body>{body}</body>
</html>
"""


def write(path, uid, chapters):
    items = "".join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(chapters)))
    spine = "".join(f'<itemref idref="c{i}"/>' for i in range(len(chapters)))
    toc = "".join(f'<li><a href="c{i}.xhtml">Chapter {i + 1}</a></li>' for i in range(len(chapters)))
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">urn:uuid:{uid}</dc:identifier><dc:title>Owner CSS</dc:title><dc:language>en</dc:language>
<meta property="dcterms:modified">2026-11-02T00:00:00Z</meta>
</metadata>
<manifest>{items}<item id="css" href="book.css" media-type="text/css"/>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
<spine>{spine}</spine>
</package>
"""
    nav = chapter("Contents", f'<nav epub:type="toc"><ol>{toc}</ol></nav>')
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", CONTAINER)
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/nav.xhtml", nav)
        z.writestr("OEBPS/book.css", BOOK_CSS)
        for i, body in enumerate(chapters):
            z.writestr(f"OEBPS/c{i}.xhtml", chapter(f"Chapter {i + 1}", body), compress_type=zipfile.ZIP_STORED)


def fb2(path):
    paragraphs = "".join(f'<p id="f_{n}">{FILLER.format(n=n, c=1)}</p>\n' for n in range(1, 241))
    text = f"""<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<stylesheet type="text/css">@font-face {{ font-family: "Book Alias"; src: local("Literata"); }}</stylesheet>
<description><title-info><genre>prose</genre><author><first-name>Test</first-name><last-name>Author</last-name></author>
<book-title>Fonts</book-title><lang>en</lang></title-info></description>
<body><section><title><p>Fonts</p></title>
{paragraphs}</section></body>
</FictionBook>
"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    filler = ["".join(f"<p>{FILLER.format(n=n, c=c)}</p>\n" for n in range(1, 61)) for c in range(2, 8)]
    for prefix in ("", "reference-"):
        write(os.path.join(out, prefix + "matrix.epub"), "perigee-owner-css-matrix", [MATRIX])
        write(os.path.join(out, prefix + "large.epub"), "perigee-owner-css-large", [MATRIX] + filler)
        fb2(os.path.join(out, prefix + "fonts.fb2"))
    print(f"{os.path.getsize(os.path.join(out, 'large.epub'))} bytes in large.epub")


main()
