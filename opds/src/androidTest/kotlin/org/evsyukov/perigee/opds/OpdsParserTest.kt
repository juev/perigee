package org.evsyukov.perigee.opds

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pages recorded on 2026-10-13 from bookOrbit 3.3.0 (the contract server) and
 * from Project Gutenberg, as two catalogs that write OPDS differently; and
 * OPDS 2.0 pages recorded on 2026-10-16 from Feedbooks (cut down to a few
 * books) and from the test catalog of the OPDS authors, test.opds.io.
 */
@RunWith(AndroidJUnit4::class)
class OpdsParserTest {
    private fun recorded(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("feeds/$name")

    private fun feed(name: String, base: String) = recorded(name).use { OpdsParser.parse(it, base) }

    private val bookorbit = "http://books.example:3070/api/v1/opds"
    private val gutenberg = "https://www.gutenberg.org/ebooks.opds/"

    @Test
    fun bookorbitRootListsSectionsAndOffersSearch() {
        val root = feed("bookorbit-root.xml", bookorbit)
        assertEquals("bookorbit OPDS Catalog", root.title)
        assertEquals(
            listOf("All Books", "Recent Books", "Random Books", "Libraries", "Collections", "SmartScopes", "Authors", "Series"),
            root.entries.map { it.title },
        )
        val all = root.entries.first()
        assertEquals("links are resolved against the page", "http://books.example:3070/api/v1/opds/catalog", all.feed)
        assertEquals("Browse the full catalog", all.summary)
        assertTrue(all.downloads.isEmpty())
        assertNull(root.next)
        assertEquals("/api/v1/opds/catalog?q={searchTerms}", root.searchTemplate)
        assertEquals("http://books.example:3070/api/v1/opds/search.opds", root.searchDescription)
    }

    @Test
    fun bookorbitCatalogListsBooksWithFilesCoversAndTheNextPage() {
        val catalog = feed("bookorbit-catalog.xml", "$bookorbit/catalog?size=2")
        assertEquals(listOf("Contract Spine NonXhtml", "Contract Plain"), catalog.entries.map { it.title })
        assertEquals("http://books.example:3070/api/v1/opds/catalog?page=2&size=2", catalog.next)

        val book = catalog.entries.first()
        assertEquals(listOf("Perigee Contract"), book.authors)
        assertEquals("", book.summary)
        assertNull("a book is not a section", book.feed)
        assertEquals(
            listOf(OpdsLink("http://books.example:3070/api/v1/opds/4/download?fileId=4", "application/epub+zip", "EPUB")),
            book.downloads,
        )
        assertEquals("epub", book.downloads.single().format)
        assertEquals(
            "the small picture is preferred",
            "http://books.example:3070/api/v1/opds/4/thumbnail?t=1.35b3b9720492cfef7b3103ca349faad2", book.cover,
        )
        assertNull(catalog.entries.last().cover)
    }

    @Test
    fun gutenbergRootHasSectionsAndASearchDescription() {
        val root = feed("gutenberg-root.xml", gutenberg)
        assertEquals("Project Gutenberg", root.title)
        assertEquals("Popular", root.entries.first().title)
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads", root.entries.first().feed)
        assertNull("a picture written into the page is not an address", root.entries.first().cover)
        assertNull(root.searchTemplate)
        assertEquals("https://www.gutenberg.org/catalog/osd-books.xml", root.searchDescription)
        assertTrue(root.searchable)
    }

    @Test
    fun gutenbergSearchResultsLeadToBookPages() {
        val found = feed("gutenberg-search.xml", "https://www.gutenberg.org/ebooks/search.opds/?query=dostoyevsky")
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=dostoyevsky&start_index=26", found.next)
        val book = found.entries.first { it.title == "Crime and Punishment" }
        assertEquals("https://www.gutenberg.org/ebooks/2554.opds", book.feed)
        assertEquals("Fyodor Dostoyevsky", book.summary)
        assertTrue(book.downloads.isEmpty())
    }

    @Test
    fun gutenbergBookPageOffersItsFiles() {
        val page = feed("gutenberg-book.xml", "https://www.gutenberg.org/ebooks/2554.opds")
        val book = page.entries.first { it.downloads.isNotEmpty() }
        assertEquals("Crime and Punishment", book.title)
        assertEquals(listOf("Dostoyevsky, Fyodor"), book.authors)
        assertEquals(listOf("epub", "mobi"), book.downloads.map { it.format }.distinct())
        assertEquals("https://www.gutenberg.org/ebooks/2554.epub.noimages", book.downloads.first().url)
        assertEquals("https://www.gutenberg.org/cache/epub/2554/pg2554.cover.small.jpg", book.cover)
        assertTrue("markup in a description reads as plain text: ${book.summary.take(80)}", book.summary.startsWith("This edition had all images removed. Uniform Title:"))
        assertFalse('<' in book.summary)
        assertNull("the author's other books are beside the book, not inside it", book.feed)
    }

    @Test
    fun searchTemplatesAreFoundAndFilledIn() {
        val gutenbergTemplate = recorded("gutenberg-osd.xml").use(OpdsParser::searchTemplate)
        assertEquals("http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}", gutenbergTemplate)
        assertEquals(
            "http://m.gutenberg.org/ebooks/search.opds/?query=war+%26+peace",
            OpdsParser.searchUrl(gutenbergTemplate!!, gutenberg, " war & peace "),
        )

        val bookorbitTemplate = recorded("bookorbit-search.xml").use(OpdsParser::searchTemplate)
        assertEquals("/api/v1/opds/catalog?q={searchTerms}", bookorbitTemplate)
        assertEquals(
            "http://books.example:3070/api/v1/opds/catalog?q=%D0%B2%D0%BE%D0%B9%D0%BD%D0%B0",
            OpdsParser.searchUrl(bookorbitTemplate!!, "$bookorbit/search.opds", "война"),
        )
        assertEquals(
            "optional placeholders are left empty",
            "https://a.example/s?q=x&page=",
            OpdsParser.searchUrl("/s?q={searchTerms}&page={startPage?}", "https://a.example/opds", "x"),
        )
    }

    @Test
    fun jsonRootListsSectionsGroupsAndItsSearch() {
        val root = feed("feedbooks-root.json", "https://catalog.feedbooks.com/catalog/index.json")
        assertEquals("Cantook Market", root.title)
        assertEquals(
            "a group with a page of its own is a section",
            listOf("Fiction", "Nonfiction", "Selections", "New & noteworthy", "Featured picks"),
            root.entries.map { it.title },
        )
        assertEquals("https://catalog.feedbooks.com/category/FBFIC000000/sub.json", root.entries.first().feed)
        assertEquals("https://catalog.feedbooks.com/featured.json?languages=en", root.entries[3].feed)
        assertEquals("https://catalog.feedbooks.com/search.json{?query}", root.searchTemplate)
        assertEquals(
            "https://catalog.feedbooks.com/search.json?query=war+%26+peace",
            OpdsParser.searchUrl(root.searchTemplate!!, "https://catalog.feedbooks.com/catalog/index.json", " war & peace "),
        )
    }

    @Test
    fun jsonPageOfPaidBooksOffersNoFiles() {
        val found = feed("feedbooks-search.json", "https://catalog.feedbooks.com/search.json?query=dumas")
        assertEquals("https://catalog.feedbooks.com/search.json?page=2&query=dumas", found.next)
        assertEquals(listOf("Alexandre Dumas", "The Count of Monte Cristo", "Pumas", "The Club Dumas"), found.entries.map { it.title })
        val book = found.entries[1]
        assertEquals(listOf("Alexandre Dumas"), book.authors)
        assertEquals("urn:ISBN:9791044615107", book.id)
        assertTrue("samples are left out: ${book.downloads}", book.downloads.isEmpty())
        assertNull("its own page is beside it, not a section", book.feed)
        assertTrue("the small picture is preferred: ${book.cover}", book.cover!!.startsWith("https://covers.feedbooks.net/item/") && "size=large" !in book.cover!!)
    }

    @Test
    fun jsonPublicationsAreReadHoweverTheyNameTheirAuthors() {
        val page = feed("opds2-test-publications.json", "https://test.opds.io/2.0/publications.json")
        assertEquals("OPDS 2.0 Test Publications", page.title)
        val byTitle = page.entries.associateBy { it.title }
        assertEquals(listOf("Jules Verne"), byTitle.getValue("Voyage au centre de la Terre").authors)
        assertEquals(listOf("Jules Verne", "Second Author"), byTitle.getValue("Multiple Authors").authors)
        assertEquals(listOf("Jules Verne"), byTitle.getValue("Author Using an Object").authors)
        assertEquals(listOf("Jules Verne", "Second Author"), byTitle.getValue("Multiple Authors Using Objects").authors)
        assertTrue("a title in several languages reads as one", "Title in multiple languages" in byTitle)

        val free = byTitle.getValue("Voyage au centre de la Terre")
        assertEquals(listOf(OpdsLink("https://test.opds.io/assets/centredelaterre/file.epub", "application/epub+zip")), free.downloads)
        assertEquals("epub", free.downloads.single().format)
        assertEquals("https://test.opds.io/assets/centredelaterre/small.jpg", free.cover)
        assertTrue(free.summary.startsWith("Le professeur Lidenbrock"))

        for (title in listOf("Buy", "Sample", "Subscribe", "Borrow")) {
            assertTrue("$title is not offered", byTitle.getValue(title).downloads.isEmpty())
        }
    }

    @Test
    fun jsonGroupsWithoutAPageAreShownInPlace() {
        val home = feed("opds2-test-home.json", "https://test.opds.io/2.0/home.json")
        val titles = home.entries.map { it.title }
        assertEquals("Explore (Navigation Feed)", titles.first())
        assertEquals(
            listOf("Moby-Dick", "Le Mystère de la chambre jaune", "French Classics", "French Books", "English Books", "Moby-Dick", "Romeo and Juliet", "Jane Eyre"),
            titles.drop(8),
        )
        assertEquals("https://test.opds.io/2.0/publications.json", home.entries.first { it.title == "French Classics" }.feed)
        assertFalse(home.searchable)
    }

    @Test
    fun jsonBookPageAndRelativeLinks() {
        val page = """{"metadata": {"title": "Alone", "author": "Someone"},
            "links": [{"rel": ["preview", "http://opds-spec.org/acquisition"], "href": "files/alone.fb2", "type": "application/x-fictionbook+xml"}],
            "images": [{"href": "data:image/png;base64,AAAA"}]}""".byteInputStream().use { OpdsParser.parse(it, "https://shop.example/opds/book.json") }
        val book = page.entries.single()
        assertEquals("Alone", book.title)
        assertEquals(listOf("https://shop.example/opds/files/alone.fb2"), book.downloads.map { it.url })
        assertNull(book.cover)
    }

    @Test
    fun whatIsNotACatalogIsRefused() {
        assertThrows(NotACatalogException::class.java) {
            OpdsParser.parse("<html><body>Sign in</body></html>".byteInputStream(), bookorbit)
        }
        assertThrows(NotACatalogException::class.java) { OpdsParser.parse("{\"error\": true}".byteInputStream(), bookorbit) }
        assertThrows(NotACatalogException::class.java) { OpdsParser.parse("<feed><entry><title>cut".byteInputStream(), bookorbit) }
        assertThrows(NotACatalogException::class.java) {
            OpdsParser.parse("""{"metadata": {"title": "Cut"}, "publications": [{"metadata": {"ti""".byteInputStream(), bookorbit)
        }
    }

    @Test
    fun paidAndBrokenLinksAreLeftOut() {
        val page = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Shop</title>
            <entry><title>For sale</title><id>1</id>
              <link rel="http://opds-spec.org/acquisition/buy" type="text/html" href="/buy/1"/>
              <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-fictionbook+xml" href="free/1.fb2"/>
              <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/1.pdf"/>
              <link rel="http://opds-spec.org/image" type="image/jpeg" href="mailto:nobody@example.invalid"/>
            </entry>
            <entry><id>2</id></entry>
        </feed>""".byteInputStream().use { OpdsParser.parse(it, "https://shop.example/opds/") }
        val book = page.entries.single()
        assertEquals(listOf("https://shop.example/opds/free/1.fb2", "https://shop.example/1.pdf"), book.downloads.map { it.url })
        assertEquals("a format the app does not read is told apart", listOf("fb2", ""), book.downloads.map { it.format })
        assertNull(book.cover)
        assertNull(book.feed)
    }
}
