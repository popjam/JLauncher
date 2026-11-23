package app.olauncher.ui

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
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
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
 * The Reddit newsletter: run the daily or weekly one on the PC, follow it with a thin bar, read the
 * issues, and change each one's settings. Opened from its own entry in the app list.
 */
class NewsletterFragment : CardPageFragment() {

    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList

    private var snapshot: NewsletterSync.Snapshot? = null
    private var loading = false
    private var failed = false

    /** Checking again soon after asking for something, and every few seconds while a run is going */
    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { refresh() }
    private var fastUntil = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx())
            addView(pageHeader(context, StatIcons.BOOK, getString(R.string.newsletter)) { findNavController().popBackStack() })
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
        super.onPause()
    }

    private fun refresh() {
        handler.removeCallbacks(poll)
        val context = context?.applicationContext ?: return
        if (!NewsletterSync.paired(context)) return render()
        loading = true
        thread {
            val result = runCatching { NewsletterSync.fetch(context) }
            handler.post {
                loading = false
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
        cardList.removeAllViews()
        val context = requireContext()
        if (!NewsletterSync.paired(context)) {
            val card = Card(getString(R.string.newsletter_pc))
            card.body(line(getString(R.string.newsletter_needs_pairing)),
                row(getString(R.string.lockdown), null) { runCatching { findNavController().navigate(R.id.lockdownFragment) } })
            return
        }
        runCard()
        issuesCard()
        settingsCard()
    }

    // --- Running ---

    private val teal get() = Style.accent(darkPage)

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
        if (snap == null) rows.add(line(getString(if (failed) R.string.newsletter_cant_reach else R.string.newsletter_loading)))
        card.body(*rows.toTypedArray())
    }

    /** A profile to run: its name and what it fetches, or the thin bar and where it's got while it runs */
    private fun profileRow(profile: String, about: String?, status: Status?, enabled: Boolean): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 12.dpToPx())
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = profileName(profile)
                textSize = Style.TEXT_TITLE
                setTextColor(if (status != null) teal else ink)
            })
            if (status != null) {
                addView(bar(status.percent), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2.dpToPx()).apply {
                    topMargin = 8.dpToPx(); bottomMargin = 6.dpToPx()
                })
                addView(small(progressText(status), 200))
            } else if (about != null) addView(small(about, 140))
            alpha = if (enabled || status != null) 1f else 0.4f
            if (enabled) setOnClickListener { askRun(profile) }
        }
    }

    private fun bar(percent: Int): View {
        val context = requireContext()
        return FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(faint(40)) }
            addView(View(context).apply { background = GradientDrawable().apply { setColor(teal) } },
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
        return getString(R.string.newsletter_progress, where, minutes)
    }

    private fun lastRunText(status: Status?): String? {
        if (status == null || status.running || status.profile == null) return null
        val name = profileName(status.profile).lowercase(Locale.getDefault())
        val at = when {
            status.finishedAt > 0 -> whenText(status.finishedAt)
            else -> ""
        }
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

    // --- Issues ---

    private fun issuesCard() {
        val issues = snapshot?.issues ?: return
        val card = Card(getString(R.string.newsletter_issues))
        if (issues.isEmpty()) return card.body(line(getString(R.string.newsletter_no_issues)))
        card.body(*issues.map { issue ->
            row(profileName(issue.profile), issueDate(issue.date)) {
                findNavController().navigate(R.id.newsletterReaderFragment, bundleOf(
                    NewsletterReaderFragment.FILE to issue.file,
                    NewsletterReaderFragment.TITLE to "${profileName(issue.profile)} · ${issueDate(issue.date)}",
                ))
            }
        }.toTypedArray())
    }

    // --- Settings ---

    private fun settingsCard() {
        val snap = snapshot ?: return
        val card = Card(getString(R.string.settings))
        card.body(*NewsletterSync.PROFILES.filter { snap.profiles.containsKey(it) }.map { profile ->
            row(profileName(profile), null) {
                findNavController().navigate(R.id.newsletterSettingsFragment, bundleOf(NewsletterSettingsFragment.PROFILE to profile))
            }
        }.toTypedArray(), row(getString(R.string.newsletter_reader_mode), getString(
            if (NewsletterReaderFragment.paged(requireContext())) R.string.newsletter_pages else R.string.newsletter_scrolling)) {
            NewsletterReaderFragment.setPaged(requireContext(), !NewsletterReaderFragment.paged(requireContext()))
            render()
        })
    }

    // --- Pieces ---

    private fun profileName(profile: String) = when (profile) {
        NewsletterSync.DAILY -> getString(R.string.newsletter_daily)
        NewsletterSync.WEEKLY -> getString(R.string.newsletter_weekly)
        else -> profile.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** "61 subreddits · top of the day" */
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

    /** "23 Nov" from "2026-11-23 21:40" */
    private fun issueDate(date: String): String = runCatching {
        LocalDate.parse(date.take(10)).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }.getOrDefault(date)

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
}
