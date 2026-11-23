package app.olauncher.helper

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reading an EPUB for the newsletter's reader: where its pages are and what its contents say.
 *
 * The newsletter's books have a contents (toc.ncx) of subreddits, each with its posts, and a page per
 * post; the reader goes through the posts in that order. A book without that shape is read page by
 * page in its spine's order.
 */
object Epub {

    /** One page of the book: its title and its path inside the zip */
    data class Page(val title: String, val path: String)

    /** A part of the contents (a subreddit) and its pages (its posts) */
    data class Section(val title: String, val pages: List<Page>)

    class Book(val title: String, val sections: List<Section>) {
        /** Every page in reading order */
        val pages: List<Page> = sections.flatMap { it.pages }

        fun indexOf(path: String) = pages.indexOfFirst { it.path == path }

        fun sectionOf(index: Int): Int {
            var seen = 0
            sections.forEachIndexed { i, s ->
                seen += s.pages.size
                if (index < seen) return i
            }
            return sections.lastIndex
        }
    }

    /** The package file's path, from META-INF/container.xml */
    fun packagePath(containerXml: String): String? =
        parse(containerXml)?.let { doc -> elements(doc.documentElement, "rootfile").firstOrNull()?.getAttribute("full-path")?.ifEmpty { null } }

    /**
     * The book from its package file (at [packagePath]) and contents, [readFile] giving a file's text
     * by its path in the zip.
     */
    fun book(packagePath: String, packageXml: String, readFile: (String) -> String?): Book {
        val doc = parse(packageXml) ?: return Book("", emptyList())
        val dir = packagePath.substringBeforeLast('/', "")
        val manifest = elements(doc.documentElement, "item").associate { it.getAttribute("id") to it }
        val title = elements(doc.documentElement, "dc:title").firstOrNull()?.textContent?.trim()
            ?: elements(doc.documentElement, "title").firstOrNull()?.textContent?.trim().orEmpty()
        val spineElement = elements(doc.documentElement, "spine").firstOrNull()
        val spine = spineElement?.let { s -> elements(s, "itemref").mapNotNull { manifest[it.getAttribute("idref")]?.getAttribute("href") } }
            ?.map { resolve(dir, it) }.orEmpty()

        val ncxPath = (spineElement?.getAttribute("toc")?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" })
            ?.getAttribute("href")?.let { resolve(dir, it) }
        val sections = ncxPath?.let(readFile)?.let { sectionsFromNcx(it, ncxPath.substringBeforeLast('/', "")) }.orEmpty()
        if (sections.any { it.pages.isNotEmpty() }) return Book(title, sections.filter { it.pages.isNotEmpty() })

        // No contents to go by: every page in the spine, as one part
        return Book(title, listOf(Section(title, spine.mapIndexed { i, path -> Page("${i + 1}", path) })))
    }

    /** The contents' top entries that have entries under them, as parts of their pages */
    internal fun sectionsFromNcx(ncx: String, dir: String): List<Section> {
        val doc = parse(ncx) ?: return emptyList()
        val navMap = elements(doc.documentElement, "navMap").firstOrNull() ?: return emptyList()
        return children(navMap, "navPoint").mapNotNull { point ->
            val pages = children(point, "navPoint").mapNotNull { page(it, dir) }
            if (pages.isEmpty()) null else Section(label(point), pages)
        }
    }

    private fun page(point: Element, dir: String): Page? {
        val src = children(point, "content").firstOrNull()?.getAttribute("src")?.ifEmpty { null } ?: return null
        return Page(label(point), resolve(dir, src))
    }

    private fun label(point: Element) =
        children(point, "navLabel").firstOrNull()?.let { elements(it, "text").firstOrNull()?.textContent?.trim() }.orEmpty()

    /** [href] from a file in [dir], as a path in the zip, without any #part */
    fun resolve(dir: String, href: String): String {
        val clean = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayDeque<String>()
        (if (dir.isEmpty()) clean else "$dir/$clean").split('/').forEach { part ->
            when (part) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun parse(xml: String) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Never fetch a DTD from the web while reading
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isExpandEntityReferences = false
        }
        factory.newDocumentBuilder().parse(xml.byteInputStream())
    }.getOrNull()

    /** Every element named [name] under [parent], at any depth */
    private fun elements(parent: Element, name: String): List<Element> {
        val list = parent.getElementsByTagName(name)
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    /** The elements named [name] right under [parent] */
    private fun children(parent: Element, name: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && (it as Element).tagName == name }.map { it as Element }
    }

    // --- An EPUB file on the phone ---

    /** A book file opened for reading; close it when done */
    class Opened(file: File) : AutoCloseable {
        private val zip = ZipFile(file)
        val book: Book

        init {
            val container = read("META-INF/container.xml") ?: error("Not an EPUB")
            val packagePath = packagePath(container) ?: error("Not an EPUB")
            book = book(packagePath, read(packagePath) ?: error("Not an EPUB")) { read(it) }
        }

        fun read(path: String): String? = zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().decodeToString() } }

        fun stream(path: String) = zip.getEntry(path)?.let { zip.getInputStream(it) }

        override fun close() = zip.close()
    }
}
