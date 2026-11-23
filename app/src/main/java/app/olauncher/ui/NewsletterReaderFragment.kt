package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.DeviceTime
import app.olauncher.helper.Epub
import app.olauncher.helper.NewsletterIssues
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A newsletter issue, read here: its contents by subreddit, then each post with its comments,
 * scrolling or a page at a time, in the reader's own plain style (the book's own style and its
 * prev/next bars are left out). Images are off unless turned on. Where you were and what you've read
 * are kept for each issue.
 */
class NewsletterReaderFragment : CardPageFragment() {

    companion object {
        const val FILE = "file"
        const val TITLE = "title"

        private const val PREFS = "newsletter_reader"
        private const val HOST = "book.local"

        fun paged(context: Context) = prefs(context).getBoolean("paged", false)
        fun setPaged(context: Context, on: Boolean) = prefs(context).edit().putBoolean("paged", on).apply()
        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList

    private val file by lazy { requireArguments().getString(FILE).orEmpty() }
    private val title by lazy { requireArguments().getString(TITLE).orEmpty() }
    private val handler = Handler(Looper.getMainLooper())

    private var opened: Epub.Opened? = null
    private var status: String? = null
    private var downloading = false
    private var expanded = -1

    /** The page being read, or -1 on the contents */
    private var current = -1
    private var startAt = 0f
    private var page = 0
    private var pages = 1

    private lateinit var contentsView: View
    private lateinit var readerView: FrameLayout
    private var web: WebView? = null
    private lateinit var topTitle: TextView
    private lateinit var topWhere: TextView
    private lateinit var controls: LinearLayout

    private val prefs by lazy { prefs(requireContext()) }
    private val paged get() = prefs.getBoolean("paged", false)
    private val dark get() = prefs.getBoolean("dark", darkPage)
    private val images get() = prefs.getBoolean("images", false)
    private val textSize get() = prefs.getInt("size", 17)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        DeviceTime.setFolder(context, uri)
        load()
    }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showContents()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        contentsView = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    updatePadding(top = 48.dpToPx())
                    addView(pageHeader(context, StatIcons.BOOK, title) { findNavController().popBackStack() })
                    addView(cardList)
                })
            })
        }
        readerView = FrameLayout(context).apply { isVisible = false; isClickable = true }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        return FrameLayout(context).apply {
            addView(contentsView)
            addView(readerView)
        }
    }

    override fun onResume() {
        super.onResume()
        if (opened == null) load() else if (current < 0) renderContents()
    }

    override fun onPause() {
        savePosition()
        super.onPause()
    }

    override fun onDestroyView() {
        web?.destroy()
        web = null
        opened?.close()
        opened = null
        super.onDestroyView()
    }

    // --- Getting the book ---

    private fun load() {
        val context = requireContext().applicationContext
        val local = NewsletterIssues.cached(context, file)
        if (local.exists()) return open(local)
        if (!NewsletterIssues.hasFolder(context)) {
            status = null
            return renderContents()
        }
        if (downloading) return
        downloading = true
        status = getString(R.string.newsletter_downloading, 0, 0)
        renderContents()
        thread {
            var lastShown = 0L
            val ok = NewsletterIssues.fetch(context, file) { done, size ->
                val now = System.currentTimeMillis()
                if (now - lastShown > 300) {
                    lastShown = now
                    handler.post { status = progressText(done, size); if (isAdded && current < 0) renderContents() }
                }
            }
            handler.post {
                downloading = false
                if (!isAdded) return@post
                if (ok) open(local) else { status = getString(R.string.newsletter_not_on_drive); renderContents() }
            }
        }
    }

    private fun progressText(done: Long, size: Long): String {
        val mb = { b: Long -> ((b + 512 * 1024) / (1024 * 1024)).toInt() }
        return if (size > 0) getString(R.string.newsletter_downloading, mb(done), mb(size)) else getString(R.string.newsletter_downloading_unknown, mb(done))
    }

    private fun open(local: java.io.File) {
        val book = runCatching { Epub.Opened(local) }
        book.onSuccess {
            opened = it
            status = null
        }.onFailure {
            local.delete()
            status = getString(R.string.newsletter_cant_open)
        }
        renderContents()
    }

    // --- Contents ---

    private val teal get() = Style.accent(darkPage)

    private fun readSet(): Set<Int> = prefs.getString("read_$file", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()

    private fun savedPosition(): Pair<Int, Float>? = prefs.getString("pos_$file", null)?.split(':')?.let { parts ->
        val index = parts.getOrNull(0)?.toIntOrNull() ?: return null
        index to (parts.getOrNull(1)?.toFloatOrNull() ?: 0f)
    }

    private fun renderContents() {
        if (!isAdded) return
        cardList.removeAllViews()
        val book = opened?.book
        if (book == null) {
            val card = Card(getString(R.string.newsletter_issue))
            when {
                status != null -> card.body(line(status!!))
                !NewsletterIssues.hasFolder(requireContext()) ->
                    card.body(row(getString(R.string.newsletter_pick_folder), null) { pickFolder.launch(null) })
                else -> card.body(line(getString(R.string.newsletter_loading)))
            }
            return
        }
        val read = readSet()
        val saved = savedPosition()
        val currentSection = saved?.first?.let { book.sectionOf(it) } ?: -1
        if (saved != null && saved.first in book.pages.indices) {
            val sectionTitle = book.sections[currentSection].title
            val inSection = saved.first - book.sections.take(currentSection).sumOf { it.pages.size } + 1
            val card = Card(getString(R.string.newsletter_continue))
            card.body(row(sectionTitle, "$inSection / ${book.sections[currentSection].pages.size}", color = teal) { openPage(saved.first, saved.second) })
        }
        val card = Card(getString(R.string.newsletter_contents))
        val rows = mutableListOf<View>()
        var first = 0
        book.sections.forEachIndexed { s, section ->
            val start = first
            val readHere = section.pages.indices.count { start + it in read }
            val done = readHere == section.pages.size
            val value = if (s == currentSection) "${(saved!!.first - start) + 1} / ${section.pages.size}" else section.pages.size.toString()
            rows.add(row(section.title, value, faded = done && s != currentSection, color = if (s == currentSection) teal else null) {
                expanded = if (expanded == s) -1 else s
                renderContents()
            })
            if (expanded == s) section.pages.forEachIndexed { i, p ->
                rows.add(row(p.title, null, faded = start + i in read, indent = true) { openPage(start + i, 0f) })
            }
            first += section.pages.size
        }
        card.body(*rows.toTypedArray())
    }

    private fun showContents() {
        savePosition()
        current = -1
        back.isEnabled = false
        readerView.isVisible = false
        contentsView.isVisible = true
        renderContents()
    }

    // --- Reading a page ---

    private fun openPage(index: Int, at: Float) {
        val book = opened?.book ?: return
        if (index !in book.pages.indices) return
        current = index
        startAt = at
        page = 0
        pages = 1
        prefs.edit().putString("read_$file", (readSet() + index).joinToString(",")).apply()
        back.isEnabled = true
        contentsView.isVisible = false
        readerView.isVisible = true
        buildReader()
        web?.loadUrl("https://$HOST/${book.pages[index].path}")
        updateBars()
    }

    private val bg get() = if (dark) android.graphics.Color.BLACK else Style.PAPER_LIGHT
    private val fg get() = if (dark) Style.PAPER_INK_DARK else Style.INK_ON_LIGHT

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildReader() {
        val context = requireContext()
        readerView.removeAllViews()
        readerView.setBackgroundColor(bg)
        web?.destroy()
        val view = WebView(context).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkImage = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "Reader")
            webViewClient = Client()
        }
        web = view
        readerView.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = 76.dpToPx()
        })
        // Along the top: back to the contents, and where you are
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            setPadding(8.dpToPx(), 36.dpToPx(), 20.dpToPx(), 10.dpToPx())
        }
        topTitle = TextView(context, null, 0, R.style.TextSmall).apply {
            text = getString(R.string.newsletter_contents_back)
            textSize = Style.TEXT_BODY
            setTextColor(fg)
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            setOnClickListener { showContents() }
        }
        topWhere = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = Style.TEXT_SMALL
            setTextColor(fg)
            alpha = 0.6f
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        top.addView(topTitle)
        top.addView(topWhere, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        readerView.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (dark) Style.PANEL_DARK else Style.PAPER_BAR_LIGHT)
            setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 28.dpToPx())
            isVisible = false
        }
        readerView.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        fillControls()
    }

    private fun fillControls() {
        controls.removeAllViews()
        fun control(text: String, onTap: () -> Unit) = controls.addView(TextView(requireContext(), null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = Style.TEXT_BODY
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(0, 10.dpToPx(), 0, 10.dpToPx())
            setOnClickListener { onTap() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        control("A−") { changeSetting { prefs.edit().putInt("size", (textSize - 1).coerceAtLeast(12)).apply() } }
        control("A+") { changeSetting { prefs.edit().putInt("size", (textSize + 1).coerceAtMost(28)).apply() } }
        control(getString(if (paged) R.string.newsletter_scrolling else R.string.newsletter_pages)) { changeSetting { setPaged(requireContext(), !paged) } }
        control(getString(if (dark) R.string.newsletter_light else R.string.newsletter_dark)) { changeSetting { prefs.edit().putBoolean("dark", !dark).apply() } }
        control(getString(if (images) R.string.newsletter_images_off else R.string.newsletter_images_on)) { changeSetting { prefs.edit().putBoolean("images", !images).apply() } }
    }

    /** Changes a reader setting and shows the page again at the same place */
    private fun changeSetting(change: () -> Unit) {
        val view = web ?: return
        view.evaluateJavascript("JL.position()") { value ->
            change()
            if (isAdded && current >= 0) {
                openPage(current, value?.toFloatOrNull() ?: 0f)
                controls.isVisible = true
            }
        }
    }

    private fun updateBars() {
        val book = opened?.book ?: return
        if (current !in book.pages.indices || !::topWhere.isInitialized) return
        val s = book.sectionOf(current)
        val start = book.sections.take(s).sumOf { it.pages.size }
        val where = "${book.sections[s].title} ${current - start + 1} / ${book.sections[s].pages.size}"
        topWhere.text = if (paged && pages > 1) "$where · ${getString(R.string.newsletter_page_of, page + 1, pages)}" else where
    }

    private fun savePosition() {
        val index = current
        if (index < 0) return
        val view = web ?: return
        view.evaluateJavascript("JL.position()") { value ->
            prefs.edit().putString("pos_$file", "$index:${value?.toFloatOrNull() ?: 0f}").apply()
        }
        // Kept straight away too, in case the page goes before the answer comes
        prefs.edit().putString("pos_$file", "$index:${prefs.getString("pos_$file", null)?.takeIf { it.startsWith("$index:") }?.substringAfter(':') ?: "0"}").apply()
    }

    private fun next() {
        val book = opened?.book ?: return
        if (current < book.pages.lastIndex) openPage(current + 1, 0f) else showContents()
    }

    private fun previous() {
        if (current > 0) openPage(current - 1, if (paged) 1f else 0f)
    }

    /** What the page's script tells the app; called off the main thread */
    private inner class Bridge {
        @JavascriptInterface fun pages(at: Int, of: Int) = handler.post { page = at; pages = of; updateBars() }
        @JavascriptInterface fun tap() = handler.post { if (current >= 0) controls.isVisible = !controls.isVisible }
        @JavascriptInterface fun next() = handler.post { this@NewsletterReaderFragment.next() }
        @JavascriptInterface fun prev() = handler.post { previous() }
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            when {
                url.scheme == "jl" && url.schemeSpecificPart == "next" -> next()
                url.scheme == "jl" && url.schemeSpecificPart == "prev" -> previous()
                url.host == HOST -> {
                    // A link to another page of the book
                    val index = opened?.book?.indexOf(Epub.resolve("", url.path.orEmpty()))
                    if (index != null && index >= 0) openPage(index, 0f)
                }
                url.scheme == "http" || url.scheme == "https" ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            // Nothing leaves the phone: only the book's own files are given
            if (url.host != HOST) return empty("text/plain")
            val path = Epub.resolve("", url.path.orEmpty())
            val book = opened ?: return empty("text/plain")
            val lower = path.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") -> {
                    val text = book.read(path) ?: return empty("text/html")
                    WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(dress(text).toByteArray()))
                }
                // The book's own look is left out for the reader's
                lower.endsWith(".css") -> empty("text/css")
                IMAGE_TYPES.any { lower.endsWith(it.key) } -> {
                    if (!images) return empty("image/png")
                    val type = IMAGE_TYPES.entries.first { lower.endsWith(it.key) }.value
                    book.stream(path)?.let { WebResourceResponse(type, null, it) } ?: empty(type)
                }
                else -> empty("text/plain")
            }
        }
    }

    private val IMAGE_TYPES = mapOf(".jpg" to "image/jpeg", ".jpeg" to "image/jpeg", ".png" to "image/png",
        ".gif" to "image/gif", ".webp" to "image/webp", ".svg" to "image/svg+xml")

    private fun empty(type: String) = WebResourceResponse(type, "utf-8", ByteArrayInputStream(ByteArray(0)))

    // --- The reader's look and the script that turns pages ---

    private fun css(): String {
        fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
        val faint = if (dark) "rgba(255,255,255,.18)" else "rgba(0,0,0,.15)"
        val link = hex(teal.let { Style.accent(dark) })
        val base = """
            html, body { background: ${hex(bg)} !important; color: ${hex(fg)} !important; }
            body { font-family: Georgia, 'Times New Roman', serif; font-size: ${textSize}px; line-height: 1.55; margin: 0;
                   padding: 4px 20px 40px; overflow-wrap: anywhere; -webkit-text-size-adjust: none; }
            * { color: inherit !important; background: transparent !important; }
            a { color: $link !important; text-decoration: none; }
            .nav-bar-top, .nav-bar-bottom, .toc-link { display: none !important; }
            h1 { font-size: 1.35em; line-height: 1.3; font-weight: normal; margin: .2em 0 .3em; }
            h2 { font-size: 1.15em; font-weight: normal; text-align: left; margin: 1em 0 .4em; }
            h3, h4 { font-size: 1em; }
            .subreddit, .source-line, .post-meta { font-family: sans-serif; font-size: .75em; opacity: .6; font-style: normal; }
            img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
            ${if (!images) "img, .img-container, figure { display: none !important; }" else ""}
            .comments { border-top: 1px solid $faint; margin-top: 1.6em; padding-top: .4em; font-size: .9em; }
            .comments h4 { font-family: sans-serif; font-size: .7em; letter-spacing: .08em; text-transform: uppercase; opacity: .5; }
            .comment-separator, hr { border: 0; border-top: 1px solid $faint; margin: 1em 0; height: 0; }
            blockquote { border-left: 2px solid $faint; margin-left: 0; padding-left: .8em; }
            pre, code { white-space: pre-wrap; font-size: .85em; }
            table { display: block; max-width: 100%; overflow-x: auto; }
            #jl-nav { display: flex; justify-content: space-between; font-family: sans-serif; font-size: .85em;
                      margin-top: 2em; padding-top: 1em; border-top: 1px solid $faint; }
        """
        val pagedCss = """
            html { height: 100%; overflow: hidden; }
            body { height: 100vh; box-sizing: border-box; padding: 0; overflow: hidden; }
            #jl-cols { height: calc(100vh - 32px); margin-top: 4px; column-width: 100vw; column-gap: 0; column-fill: auto; }
            #jl-cols > * { padding-left: 20px !important; padding-right: 20px !important; box-sizing: border-box; }
            img { max-height: 70vh; width: auto; }
            #jl-nav { display: none; }
        """
        return base + if (paged) pagedCss else ""
    }

    private fun script(): String = """
        var JL = { paged: $paged, page: 0, pages: 1, start: $startAt };
        JL.measure = function() {
          if (!JL.paged) return;
          var c = document.getElementById('jl-cols');
          JL.pages = Math.max(1, Math.round(c.scrollWidth / window.innerWidth));
        };
        JL.go = function(p) {
          JL.page = Math.max(0, Math.min(JL.pages - 1, p));
          window.scrollTo(JL.page * window.innerWidth, 0);
          Reader.pages(JL.page, JL.pages);
        };
        JL.position = function() {
          if (JL.paged) return JL.pages > 1 ? JL.page / (JL.pages - 1) : 0;
          var h = document.documentElement.scrollHeight - window.innerHeight;
          return h > 0 ? window.scrollY / h : 0;
        };
        JL.restore = function(f) {
          if (JL.paged) JL.go(Math.round(f * (JL.pages - 1)));
          else window.scrollTo(0, f * (document.documentElement.scrollHeight - window.innerHeight));
        };
        JL.next = function() { if (JL.paged && JL.page < JL.pages - 1) JL.go(JL.page + 1); else Reader.next(); };
        JL.prev = function() { if (JL.paged && JL.page > 0) JL.go(JL.page - 1); else Reader.prev(); };
        document.addEventListener('DOMContentLoaded', function() {
          if (JL.paged) {
            var c = document.createElement('div');
            c.id = 'jl-cols';
            while (document.body.firstChild) c.appendChild(document.body.firstChild);
            document.body.appendChild(c);
          }
          setTimeout(function() { JL.measure(); JL.restore(JL.start); }, 30);
        });
        window.addEventListener('load', function() { if (JL.paged) { JL.measure(); JL.restore(JL.start); } });
        var sx = 0, sy = 0;
        document.addEventListener('touchstart', function(e) { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
        document.addEventListener('touchend', function(e) {
          if (!JL.paged) return;
          var dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy;
          if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) { JL.swiped = true; if (dx < 0) JL.next(); else JL.prev(); }
        }, { passive: true });
        document.addEventListener('click', function(e) {
          if (JL.swiped) { JL.swiped = false; return; }
          if (e.target.closest && e.target.closest('a')) return;
          var x = e.clientX / window.innerWidth;
          if (JL.paged && x < 0.3) JL.prev();
          else if (JL.paged && x > 0.7) JL.next();
          else Reader.tap();
        });
    """

    /** The page with the reader's look and script in it, and the way to the next post at its end */
    private fun dress(html: String): String {
        val head = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>${css()}</style><script>${script()}</script>"
        val nav = "<div id=\"jl-nav\"><a href=\"jl:prev\">‹ ${getString(R.string.newsletter_previous)}</a><a href=\"jl:next\">${getString(R.string.newsletter_next)} ›</a></div>"
        // Without the XML declaration, read as HTML, which forgives what XHTML wouldn't
        var out = html.replaceFirst(Regex("^\\s*<\\?xml[^>]*>"), "").replaceFirst(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "<!DOCTYPE html>")
        out = Regex("</head>", RegexOption.IGNORE_CASE).find(out)?.let { out.replaceRange(it.range, "$head</head>") } ?: "$head$out"
        out = Regex("</body>", RegexOption.IGNORE_CASE).findAll(out).lastOrNull()?.let { out.replaceRange(it.range, "$nav</body>") } ?: "$out$nav"
        return out
    }

    // --- Pieces ---

    private fun line(text: String) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = Style.TEXT_BODY
        setTextColor(faint(170))
        setLineSpacing(0f, 1.15f)
        setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 12.dpToPx())
    }

    private fun row(title: String, value: String?, faded: Boolean = false, color: Int? = null, indent: Boolean = false, onTap: () -> Unit): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (indent) 20.dpToPx() else 4.dpToPx(), 0, 4.dpToPx(), 0)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onTap() }
            alpha = if (faded) 0.4f else 1f
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = title
                textSize = if (indent) Style.TEXT_BODY else Style.TEXT_BODY
                setTextColor(color ?: ink)
                if (!indent) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = if (indent) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (if (indent) 10 else 13).dpToPx(), 0, (if (indent) 10 else 13).dpToPx())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value != null) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = value
                textSize = Style.TEXT_BODY
                setTextColor(color ?: faint(215))
                updatePadding(left = 12.dpToPx())
            })
        }
    }

    private fun Card.body(vararg rows: View) {
        panel()
        val list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            dividerDrawable = rowDivider()
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        rows.forEach { list.addView(it) }
        root.addView(list, root.indexOfChild(scene))
    }
}
