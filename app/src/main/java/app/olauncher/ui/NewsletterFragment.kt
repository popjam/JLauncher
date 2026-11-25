package app.olauncher.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.DeviceTime
import app.olauncher.helper.NewsletterIssues
import app.olauncher.helper.NewsletterSync
import app.olauncher.helper.NewsletterSync.Status
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showToast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The Reddit newsletter, opened from Projects: the issues first, as a shelf of covers (newest first,
 * filtered to daily or weekly, the ones on the phone solid and the ones still on Drive dashed), then
 * running the daily or weekly one on the PC with a bar while it runs, then settings. Tapping a cover
 * opens it, downloading it first if needed; holding one gives its menu. Pulling down closes it.
 */
class NewsletterFragment : CardPageFragment() {

    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList

    private var snapshot: NewsletterSync.Snapshot? = null
    private var failed = false

    /** Checking again soon after asking for something, and every few seconds while a run is going */
    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { refresh() }
    private var fastUntil = 0L

    /** The covers on show, by file, so downloads can move on without building the page again */
    private val covers = mutableMapOf<String, IssueCoverView>()
    private val downloadTick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            covers.forEach { (file, cover) -> NewsletterIssues.downloads[file]?.let { if (cover.progress != it) cover.progress = it } }
            if (NewsletterIssues.downloads.isNotEmpty()) handler.postDelayed(this, 150L)
        }
    }

    private val prefs by lazy { requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    private var filter: String
        get() = prefs.getString("filter", ALL).orEmpty()
        set(value) = prefs.edit().putString("filter", value).apply()

    /** The issue to open once the Drive folder has been picked */
    private var afterFolder: NewsletterSync.Issue? = null
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        DeviceTime.setFolder(context, uri)
        afterFolder?.let { download(it) }
        afterFolder = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx())
            addView(pageHeader(context, StatIcons.NEWSPAPER, getString(R.string.newsletter)) { findNavController().popBackStack() })
            addView(cardList)
        }
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(content)
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(downloadTick)
        super.onPause()
    }

    private fun refresh() {
        handler.removeCallbacks(poll)
        val context = context?.applicationContext ?: return
        if (!NewsletterSync.paired(context)) return render()
        thread {
            val result = runCatching { NewsletterSync.fetch(context) }
            handler.post {
                result.onSuccess { snapshot = it; failed = false }.onFailure { failed = true }
                if (!isResumed) return@post
                render()
                val running = snapshot?.status?.running == true
                val fast = System.currentTimeMillis() < fastUntil
                if (running || fast) handler.postDelayed(poll, if (fast) 4_000L else 10_000L)
            }
        }
    }

    private fun render() {
        if (!isAdded) return
        val scroll = pageScroll()?.scrollY ?: 0
        cardList.removeAllViews()
        covers.clear()
        libraryCard()
        if (NewsletterSync.paired(requireContext())) runCard()
        else Card(getString(R.string.newsletter_run)).body(line(getString(R.string.newsletter_needs_pairing)),
            row(getString(R.string.lockdown), null) { runCatching { findNavController().navigate(R.id.lockdownFragment) } })
        settingsCard()
        pageScroll()?.post { pageScroll()?.scrollTo(0, scroll) }
        if (NewsletterIssues.downloads.isNotEmpty()) {
            handler.removeCallbacks(downloadTick)
            handler.post(downloadTick)
        }
    }

    private val teal get() = Style.accent(darkPage)

    // --- The issues ---

    /** Every issue known: Drive's list, and any on the phone that list doesn't have; newest first */
    private fun issues(): List<NewsletterSync.Issue> {
        val onDrive = snapshot?.issues.orEmpty()
        val onPhone = NewsletterIssues.downloaded(requireContext()).filter { phone -> onDrive.none { it.file == phone.file } }
        return (onDrive + onPhone).sortedByDescending { it.date }
    }

    private fun libraryCard() {
        val context = requireContext()
        val all = issues()
        val card = Card(getString(R.string.newsletter_issues))
        card.panel()
        val profiles = all.map { it.profile }.distinct()
        // Filtering only means something with both kinds there
        if (profiles.size > 1) {
            val options = listOf(ALL) + NewsletterSync.PROFILES.filter { it in profiles }
            val picked = filter.takeIf { it in options } ?: ALL
            card.addBody(filters(options, picked))
        }
        val shown = all.filter { filter == ALL || it.profile == filter || filter !in profiles }
        when {
            shown.isNotEmpty() -> card.addBody(shelf(shown))
            snapshot == null && NewsletterSync.paired(context) && !failed -> card.addBody(line(getString(R.string.newsletter_loading)))
            failed -> card.addBody(line(getString(R.string.newsletter_cant_reach)))
            else -> card.addBody(line(getString(R.string.newsletter_no_issues)))
        }
    }

    /** All · Daily · Weekly, the picked one underlined */
    private fun filters(options: List<String>, picked: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 14.dpToPx())
            options.forEach { option ->
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = if (option == ALL) getString(R.string.newsletter_all) else profileName(option)
                    textSize = Style.TEXT_BODY
                    setTextColor(ink)
                    alpha = if (option == picked) 1f else 0.45f
                    if (option == picked) setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(14.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
                    background = GradientDrawable().apply {
                        cornerRadius = 50.dpToPx().toFloat()
                        if (option == picked) setColor(faint(28)) else setStroke(1.dpToPx(), faint(40))
                    }
                    setOnClickListener { filter = option; render() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() })
            }
        }
    }

    /** The covers, three to a row */
    private fun shelf(issues: List<NewsletterSync.Issue>): View {
        val context = requireContext()
        val gap = 12.dpToPx()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            issues.chunked(COLUMNS).forEachIndexed { r, rowIssues ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    (0 until COLUMNS).forEach { c ->
                        val issue = rowIssues.getOrNull(c)
                        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (c > 0) marginStart = gap }
                        if (issue == null) addView(View(context), params) else addView(cover(issue), params)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = gap })
            }
        }
    }

    private fun cover(issue: NewsletterSync.Issue): View {
        val context = requireContext()
        val date = parseDate(issue.date)
        val onPhone = NewsletterIssues.isDownloaded(context, issue.file)
        val view = IssueCoverView(context).apply {
            kind = profileName(issue.profile).uppercase(Locale.getDefault())
            day = date?.dayOfMonth?.toString() ?: "?"
            month = date?.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))?.uppercase(Locale.getDefault()).orEmpty()
            weekday = date?.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).orEmpty()
            time = issue.date.substringAfter(' ', "")
            downloaded = onPhone
            read = NewsletterReaderFragment.readFraction(context, issue.file)
            finished = NewsletterReaderFragment.finished(context, issue.file)
            progress = NewsletterIssues.downloads[issue.file]
            contentDescription = "${profileName(issue.profile)} ${issueDate(issue.date)}"
            setOnClickListener { openIssue(issue) }
            setOnLongClickListener { issueMenu(issue); true }
        }
        covers[issue.file] = view
        return view
    }

    private fun openIssue(issue: NewsletterSync.Issue) {
        val context = requireContext()
        if (NewsletterIssues.isDownloaded(context, issue.file)) {
            findNavController().navigate(R.id.action_newsletterFragment_to_readerFragment, bundleOf(
                NewsletterReaderFragment.FILE to issue.file,
                NewsletterReaderFragment.TITLE to "${profileName(issue.profile)} · ${issueDate(issue.date)}",
            ))
            return
        }
        if (NewsletterIssues.downloads.containsKey(issue.file)) return
        download(issue)
    }

    /** Downloads [issue] from Drive, its cover showing how far it's got, and opens it when it's here */
    private fun download(issue: NewsletterSync.Issue) {
        val context = requireContext()
        if (!NewsletterIssues.hasFolder(context)) {
            afterFolder = issue
            context.showToast(R.string.newsletter_pick_folder)
            pickFolder.launch(null)
            return
        }
        NewsletterIssues.download(context, issue.file, issue.size) { ok ->
            if (!isResumed) return@download
            if (ok) openIssue(issue) else {
                context.showToast(R.string.newsletter_not_on_drive)
                render()
            }
        }
        covers[issue.file]?.progress = -1f
        handler.removeCallbacks(downloadTick)
        handler.post(downloadTick)
    }

    /** Held: open or download it, and take it off the phone or mark it read or unread */
    private fun issueMenu(issue: NewsletterSync.Issue) {
        val context = requireContext()
        val onPhone = NewsletterIssues.isDownloaded(context, issue.file)
        val read = NewsletterReaderFragment.finished(context, issue.file)
        val actions = listOfNotNull(
            getString(if (onPhone) R.string.newsletter_open else R.string.newsletter_download) to { openIssue(issue) },
            if (onPhone) getString(R.string.newsletter_remove_download) to {
                NewsletterIssues.remove(context, issue.file)
                render()
            } else null,
            if (onPhone) getString(if (read) R.string.newsletter_mark_unread else R.string.newsletter_mark_read) to {
                NewsletterReaderFragment.setFinished(context, issue.file, !read)
                render()
            } else null,
        )
        AppDialog(context)
            .setTitle("${profileName(issue.profile)} · ${issueDate(issue.date)}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    // --- Running ---

    private fun runCard() {
        val snap = snapshot
        val status = snap?.status
        val running = status?.running == true
        val card = Card(getString(R.string.newsletter_run))
        val rows = mutableListOf<View?>()
        NewsletterSync.PROFILES.forEach { profile ->
            val settings = snap?.profiles?.get(profile)
            val mine = running && status?.profile == profile
            rows.add(profileRow(profile, settings?.let { describe(it) }, if (mine) status else null, enabled = !running && settings != null))
        }
        if (running) rows.add(row(getString(R.string.newsletter_stop), null, faded = true) { askStop() })
        lastRunText(status)?.let { rows.add(line(it)) }
        pcAwayText(snap)?.let { rows.add(line(it)) }
        if (snap == null && failed) rows.add(line(getString(R.string.newsletter_cant_reach)))
        card.body(*rows.toTypedArray())
    }

    /** A profile to run: its name and what it fetches, or a bar and where it's got while it runs */
    private fun profileRow(profile: String, about: String?, status: Status?, enabled: Boolean): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 12.dpToPx())
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = profileName(profile)
                    textSize = Style.TEXT_TITLE
                    setTextColor(if (status != null) teal else ink)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (status != null) addView(small("${status.percent}%", 220))
            })
            if (status != null) {
                addView(bar(status.percent), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4.dpToPx()).apply {
                    topMargin = 10.dpToPx(); bottomMargin = 8.dpToPx()
                })
                addView(small(progressText(status), 200))
            } else if (about != null) addView(small(about, 140))
            alpha = if (enabled || status != null) 1f else 0.4f
            if (enabled) setOnClickListener { askRun(profile) }
        }
    }

    private fun bar(percent: Int): View {
        val context = requireContext()
        val radius = 2.dpToPx().toFloat()
        return FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(faint(40)); cornerRadius = radius }
            addView(View(context).apply { background = GradientDrawable().apply { setColor(teal); cornerRadius = radius } },
                FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            // The filled part, once its width is known
            post {
                val fill = getChildAt(0) ?: return@post
                fill.layoutParams = fill.layoutParams.apply { width = width * percent.coerceIn(0, 100) / 100 }
            }
        }
    }

    private fun progressText(status: Status): String {
        val where = NewsletterSync.fetching(status)?.let { (name, n, of) -> getString(R.string.newsletter_fetching, name, n, of) }
            ?: when (status.stage) {
                "book" -> getString(R.string.newsletter_stage_book)
                "send" -> getString(R.string.newsletter_stage_send)
                else -> getString(R.string.newsletter_stage_starting)
            }
        val minutes = if (status.startedAt > 0) ((System.currentTimeMillis() - status.startedAt) / 60_000L).coerceAtLeast(0) else 0
        return getString(R.string.newsletter_progress, where, app.olauncher.helper.Formats.minutes(minutes.toInt()))
    }

    private fun lastRunText(status: Status?): String? {
        if (status == null || status.running || status.profile == null) return null
        val name = profileName(status.profile).lowercase(Locale.getDefault())
        val at = if (status.finishedAt > 0) whenText(status.finishedAt) else ""
        return when (status.state) {
            "sent" -> getString(R.string.newsletter_last_sent, name, at)
            "failed" -> getString(R.string.newsletter_last_failed, name, at) + (status.message?.let { "\n$it" } ?: "")
            "stopped" -> getString(R.string.newsletter_last_stopped, name, at)
            "expired" -> getString(R.string.newsletter_last_expired, name)
            else -> null
        }
    }

    private fun pcAwayText(snap: NewsletterSync.Snapshot?): String? {
        val seen = snap?.pcSeen ?: return null
        if (seen > 0 && System.currentTimeMillis() - seen < NewsletterSync.PC_AWAY_MS) return null
        return if (seen > 0) getString(R.string.newsletter_pc_away, whenText(seen)) else getString(R.string.newsletter_pc_never)
    }

    private fun askRun(profile: String) {
        AppDialog(requireContext())
            .setTitle(getString(R.string.newsletter_send_ask, profileName(profile).lowercase(Locale.getDefault())))
            .setPositiveButton(R.string.newsletter_send) { _, _ -> send { NewsletterSync.run(it, profile) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askStop() {
        AppDialog(requireContext())
            .setTitle(R.string.newsletter_stop_ask)
            .setPositiveButton(R.string.newsletter_stop) { _, _ -> send { NewsletterSync.stop(it) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun send(action: (android.content.Context) -> Unit) {
        val context = requireContext().applicationContext
        thread {
            val ok = runCatching { action(context) }.isSuccess
            handler.post {
                if (!ok) context.showToast(R.string.newsletter_cant_reach)
                // The PC looks every 10 seconds
                fastUntil = System.currentTimeMillis() + 45_000L
                if (isResumed) handler.postDelayed(poll, 4_000L)
            }
        }
    }

    // --- Settings ---

    private fun settingsCard() {
        val snap = snapshot
        val context = requireContext()
        val card = Card(getString(R.string.settings))
        val profiles = snap?.let { s ->
            NewsletterSync.PROFILES.filter { s.profiles.containsKey(it) }.map { profile ->
                row(profileName(profile), null) {
                    findNavController().navigate(R.id.action_newsletterFragment_to_settingsFragment, bundleOf(NewsletterSettingsFragment.PROFILE to profile))
                }
            }
        }.orEmpty()
        card.body(*profiles.toTypedArray(), row(getString(R.string.newsletter_reader_mode), getString(
            if (NewsletterReaderFragment.paged(context)) R.string.newsletter_pages else R.string.newsletter_scrolling)) {
            NewsletterReaderFragment.setPaged(context, !NewsletterReaderFragment.paged(context))
            render()
        }, row(getString(R.string.newsletter_drive_folder), getString(if (NewsletterIssues.hasFolder(context)) R.string.newsletter_folder_set else R.string.newsletter_folder_none)) {
            afterFolder = null
            pickFolder.launch(null)
        })
    }

    // --- Pieces ---

    private fun profileName(profile: String) = when (profile) {
        NewsletterSync.DAILY -> getString(R.string.newsletter_daily)
        NewsletterSync.WEEKLY -> getString(R.string.newsletter_weekly)
        else -> profile.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** "61 subreddits · top · day" */
    private fun describe(settings: org.json.JSONObject): String {
        val count = NewsletterSync.subreddits(settings).size
        val defaults = settings.optJSONObject("reddit")?.optJSONObject("defaults")
        val sort = defaults?.optString("sort").orEmpty()
        val timeframe = defaults?.optString("timeframe").orEmpty()
        val subs = resources.getQuantityString(R.plurals.newsletter_subreddits, count, count)
        return if (sort == "top" || sort == "controversial") "$subs · $sort · $timeframe" else if (sort.isNotEmpty()) "$subs · $sort" else subs
    }

    private fun whenText(ms: Long): String {
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        val clock = clock(time.toLocalTime())
        return if (time.toLocalDate() == LocalDate.now()) clock else "${time.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} $clock"
    }

    private fun parseDate(date: String): LocalDate? = runCatching { LocalDate.parse(date.take(10)) }.getOrNull()

    /** "Tue 24 Nov" from "2026-11-24 02:23" */
    private fun issueDate(date: String): String =
        parseDate(date)?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) ?: date

    private fun small(text: String, alpha: Int) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = Style.TEXT_SMALL
        setTextColor(faint(alpha))
        updatePadding(top = 2.dpToPx())
    }

    private fun line(text: String) = small(text, 150).apply {
        textSize = Style.TEXT_BODY
        setLineSpacing(0f, 1.15f)
        setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 12.dpToPx())
    }

    private fun row(title: String, value: String?, faded: Boolean = false, onTap: () -> Unit): View =
        settingRow(title, value, faded = faded, onTap = onTap)

    private fun Card.body(vararg rows: View?) {
        rowsSection(rows.toList())
    }

    companion object {
        private const val PREFS = "newsletter_library"
        private const val ALL = "all"
        private const val COLUMNS = 3
    }
}
