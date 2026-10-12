#!/usr/bin/env python3
"""Generates the book corpus for contract tests against a bookOrbit server.

The books are synthetic, so the corpus is deterministic and free of third-party
texts. Each one targets a place where crengine xpointers and the server's
position converter may disagree:

  plain           ordinary XHTML chapters with inline markup and entities
  spine-nonxhtml  an SVG spine item before the chapters (DocFragment numbering)
  media-css       @media rules that change `display` (whitespace text nodes)
  fb2             a format the server stores positions for but cannot convert

Usage: make_corpus.py <output-dir>
"""

import sys
import zipfile
from pathlib import Path

CHAPTERS = 4
PARAGRAPHS = 40

# A fixed timestamp keeps the archives byte-identical between runs, which keeps
# the partial MD5 the server matches files by stable.
ZIP_DATE = (2026, 1, 1, 0, 0, 0)


def paragraph(chapter: int, n: int) -> str:
    return (
        f"<p>Chapter {chapter}, paragraph {n}. The quick brown fox <em>jumps over</em> the lazy dog; "
        f'see <a href="c1.xhtml">the first chapter</a> for details &amp; context.\n'
        f"    A second sentence follows &#8212; with an entity, a non-breaking&#160;space and "
        f"<span class=\"mark\">marked text number {chapter}-{n}</span> to anchor highlights on. "
        f"The paragraph then keeps going so that it wraps over several lines on a phone screen.</p>"
    )


def chapter_xhtml(chapter: int, css: bool) -> str:
    body = "\n\n  ".join(paragraph(chapter, n) for n in range(1, PARAGRAPHS + 1))
    link = '<link rel="stylesheet" type="text/css" href="style.css"/>' if css else ""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter {chapter}</title>{link}</head>
<body>
  <div class="chapter">
  <h1>Chapter {chapter}</h1>
  <div class="note"> <span class="lead">Lead-in</span> <span class="tail">for chapter {chapter}.</span> </div>
  {body}
  </div>
</body>
</html>
"""


def write_epub(path: Path, title: str, *, svg_first: bool = False, media_css: bool = False) -> None:
    items, spine, files = [], [], {}
    if svg_first:
        files["cover.svg"] = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 150">'
            '<rect width="100" height="150" fill="#ddd"/><text x="10" y="80">Cover</text></svg>'
        )
        items.append('<item id="cover" href="cover.svg" media-type="image/svg+xml"/>')
        spine.append('<itemref idref="cover"/>')
    if media_css:
        files["style.css"] = (
            "span.lead { display: inline; }\n"
            "@media (min-width: 1px) {\n"
            "  span.lead { display: block; }\n"
            "  span.tail { display: block; }\n"
            "}\n"
            "@media (max-width: 1px) { p { display: none; } }\n"
        )
        items.append('<item id="css" href="style.css" media-type="text/css"/>')
    for c in range(1, CHAPTERS + 1):
        files[f"c{c}.xhtml"] = chapter_xhtml(c, media_css)
        items.append(f'<item id="c{c}" href="c{c}.xhtml" media-type="application/xhtml+xml"/>')
        spine.append(f'<itemref idref="c{c}"/>')

    opf = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>{title}</dc:title>
    <dc:creator>Perigee Contract</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">perigee-contract-{path.stem}</dc:identifier>
  </metadata>
  <manifest>
    {chr(10).join(items)}
  </manifest>
  <spine>
    {chr(10).join(spine)}
  </spine>
</package>
"""
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        def add(name: str, text: str, method: int) -> None:
            info = zipfile.ZipInfo(name, ZIP_DATE)
            info.compress_type = method
            zf.writestr(info, text.encode("utf-8"))

        # The EPUB container requires an uncompressed mimetype entry first.
        add("mimetype", "application/epub+zip", zipfile.ZIP_STORED)
        add("META-INF/container.xml", container, zipfile.ZIP_DEFLATED)
        add("OEBPS/content.opf", opf, zipfile.ZIP_DEFLATED)
        for name, text in files.items():
            add(f"OEBPS/{name}", text, zipfile.ZIP_DEFLATED)


def write_fb2(path: Path, title: str) -> None:
    sections = []
    for c in range(1, CHAPTERS + 1):
        paras = "\n".join(
            f"<p>Chapter {c}, paragraph {n}. The quick brown fox <emphasis>jumps over</emphasis> the lazy dog; "
            f"marked text number {c}-{n} anchors highlights. The paragraph keeps going so that it wraps "
            f"over several lines on a phone screen.</p>"
            for n in range(1, PARAGRAPHS + 1)
        )
        sections.append(f"<section><title><p>Chapter {c}</p></title>\n{paras}\n</section>")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"""<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info>
  <author><first-name>Perigee</first-name><last-name>Contract</last-name></author>
  <book-title>{title}</book-title><lang>en</lang>
</title-info></description>
<body>
{chr(10).join(sections)}
</body>
</FictionBook>
""",
        encoding="utf-8",
    )


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    write_epub(out / "plain" / "plain.epub", "Contract Plain")
    write_epub(out / "spine-nonxhtml" / "spine-nonxhtml.epub", "Contract Spine NonXhtml", svg_first=True)
    write_epub(out / "media-css" / "media-css.epub", "Contract Media Css", media_css=True)
    write_fb2(out / "fb2" / "contract.fb2", "Contract Fb2")
    print(f"corpus written to {out}")


if __name__ == "__main__":
    main()
