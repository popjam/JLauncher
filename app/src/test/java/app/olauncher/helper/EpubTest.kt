package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubTest {

    private val container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    // As the newsletter writes them: a contents page, then each subreddit's intro page and its posts
    private val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Reddit Daily Newsletter - November 23rd, 2026</dc:title></metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="toc" href="toc.xhtml" media-type="application/xhtml+xml"/>
    <item id="intro0" href="sub_0.xhtml" media-type="application/xhtml+xml"/>
    <item id="p0" href="post_0.xhtml" media-type="application/xhtml+xml"/>
    <item id="p1" href="post_1.xhtml" media-type="application/xhtml+xml"/>
    <item id="intro1" href="sub_1.xhtml" media-type="application/xhtml+xml"/>
    <item id="p2" href="post_2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="toc"/><itemref idref="intro0"/><itemref idref="p0"/><itemref idref="p1"/><itemref idref="intro1"/><itemref idref="p2"/></spine>
</package>"""

    private val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<navMap>
<navPoint id="nav-toc" playOrder="1"><navLabel><text>Table of Contents</text></navLabel><content src="toc.xhtml"/></navPoint>
<navPoint id="nav-intro0" playOrder="2"><navLabel><text>worldnews</text></navLabel><content src="sub_0.xhtml"/>
<navPoint id="nav-p0" playOrder="3"><navLabel><text>Flood barrier approved &amp; funded</text></navLabel><content src="post_0.xhtml"/></navPoint>
<navPoint id="nav-p1" playOrder="4"><navLabel><text>Second post</text></navLabel><content src="post_1.xhtml#top"/></navPoint></navPoint>
<navPoint id="nav-intro1" playOrder="5"><navLabel><text>science</text></navLabel><content src="sub_1.xhtml"/>
<navPoint id="nav-p2" playOrder="6"><navLabel><text>Third post</text></navLabel><content src="post_2.xhtml"/></navPoint></navPoint>
</navMap></ncx>"""

    private fun book(ncxText: String? = ncx) = Epub.book("OEBPS/content.opf", opf) { path -> if (path == "OEBPS/toc.ncx") ncxText else null }

    @Test
    fun findsThePackage() {
        assertEquals("OEBPS/content.opf", Epub.packagePath(container))
        assertNull(Epub.packagePath("<container/>"))
        assertNull(Epub.packagePath("not xml"))
    }

    @Test
    fun subredditsWithTheirPostsInOrder() {
        val book = book()
        assertEquals("Reddit Daily Newsletter - November 23rd, 2026", book.title)
        assertEquals(listOf("worldnews", "science"), book.sections.map { it.title })
        assertEquals(listOf("Flood barrier approved & funded", "Second post", "Third post"), book.pages.map { it.title })
        // The intro and contents pages aren't pages to read; #parts are dropped
        assertEquals(listOf("OEBPS/post_0.xhtml", "OEBPS/post_1.xhtml", "OEBPS/post_2.xhtml"), book.pages.map { it.path })
        assertEquals(1, book.indexOf("OEBPS/post_1.xhtml"))
        assertEquals(0, book.sectionOf(1))
        assertEquals(1, book.sectionOf(2))
    }

    @Test
    fun withoutContentsEveryPageInTheSpine() {
        val book = book(ncxText = null)
        assertEquals(1, book.sections.size)
        assertEquals(6, book.pages.size)
        assertEquals("OEBPS/toc.xhtml", book.pages.first().path)
    }

    @Test
    fun pathsAreResolved() {
        assertEquals("OEBPS/images/a.jpg", Epub.resolve("OEBPS/text", "../images/a.jpg"))
        assertEquals("a b.xhtml", Epub.resolve("", "a%20b.xhtml#x"))
        assertEquals("OEBPS/p.xhtml", Epub.resolve("OEBPS", "./p.xhtml"))
    }
}
