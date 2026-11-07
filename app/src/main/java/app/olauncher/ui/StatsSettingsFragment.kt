package app.olauncher.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.data.Prefs
import app.olauncher.helper.AutoStats
import app.olauncher.helper.Social
import app.olauncher.helper.Spending
import app.olauncher.helper.SwipeTargets
import app.olauncher.helper.Tracking
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * Settings and tracking for the stats in one place: whether everything that fills them in is working
 * (with a fix for what isn't), the weekly spending limit, essentials, homes and work, the swipes that
 * open the stats, what the stats Home shows, and a report for an appointment.
 */
class StatsSettingsFragment : CardPageFragment() {

    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx())
            addView(pageHeader(context, StatIcons.GEAR, getString(R.string.stats_settings)) { findNavController().popBackStack() })
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
    }

    private fun render() {
        cardList.removeAllViews()
        trackingCard()
        spendingCard()
        placesCard()
        gesturesCard()
        homeCard()
        reportCard()
    }

    /** A row: a dot (or none), a title, a line under it, and what tapping it does */
    private fun row(card: Card, title: String, detail: String?, dot: Int? = null, onTap: (() -> Unit)?): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
            if (onTap != null) {
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onTap() }
            }
            if (dot != null) addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dot)
                }
            }, LinearLayout.LayoutParams(10.dpToPx(), 10.dpToPx()).apply { marginEnd = 14.dpToPx() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(card.textView(15f, 240).apply { text = title })
                if (detail != null) addView(card.textView(13f, 150).apply {
                    text = detail
                    updatePadding(top = 2.dpToPx())
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (onTap != null) addView(card.textView(18f, 120).apply { text = "›" })
        }
    }

    private fun Card.body(vararg rows: View) {
        scene.visibility = View.GONE
        val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { list.addView(it) }
        root.addView(list, root.indexOfChild(scene))
    }

    /** Everything that fills the stats in, working or not; tapping one that isn't fixes it where it can */
    private fun trackingCard() {
        val context = requireContext()
        val items = Tracking.items(context)
        val card = Card(getString(R.string.tracking))
        val problems = items.count { !it.ok }
        card.subtitle(if (problems == 0) getString(R.string.tracking_all_ok) else resources.getQuantityString(R.plurals.tracking_problems, problems, problems))
        card.body(*items.map { item ->
            val since = item.since?.let { getString(R.string.tracking_since, dateText(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate())) }
            row(card, item.title, listOfNotNull(item.detail, since).joinToString(" · "),
                dot = if (item.ok) 0xFF66BB6A.toInt() else 0xFFFFB300.toInt(),
                onTap = item.fix?.let { fix -> { fix(context) } })
        }.toTypedArray())
    }

    /** The weekly limit, and the categories set as essential or not */
    private fun spendingCard() {
        val context = requireContext()
        val card = Card(getString(R.string.settings_spending), icon = StatIcons.MONEY, iconColor = faint(200))
        val limit = Spending.weekLimitCents(context)
        val limitText = if (limit <= 0) getString(R.string.settings_none) else listOfNotNull(
            moneyShort(limit),
            getString(R.string.settings_carry).takeIf { Spending.carryOver(context) },
            getString(R.string.settings_on_home).takeIf { Spending.showOnHome(context) },
        ).joinToString(" · ")
        val rules = Spending.groupRules(context)
        card.body(
            row(card, getString(R.string.week_limit), limitText) { askWeekLimit { if (isAdded) render() } },
            row(card, getString(R.string.settings_essentials),
                if (rules.isEmpty()) getString(R.string.settings_essentials_none)
                else rules.entries.joinToString(", ") { (group, essential) -> group + if (essential) " ✓" else " ✗" }) { askRules() },
        )
    }

    /** Each category set by hand: change it or put it back to automatic */
    private fun askRules() {
        val context = requireContext()
        val rules = Spending.groupRules(context).entries.toList()
        if (rules.isEmpty()) {
            AlertDialog.Builder(context).setMessage(R.string.settings_essentials_how).setPositiveButton(R.string.okay, null).show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_essentials)
            .setItems(rules.map { (group, essential) -> group + " · " + getString(if (essential) R.string.essential else R.string.settings_not_essential) }.toTypedArray()) { _, i ->
                val (group, essential) = rules[i]
                AlertDialog.Builder(context)
                    .setTitle(group)
                    .setItems(arrayOf(getString(if (essential) R.string.essential_not_group else R.string.essential_group, group),
                        getString(R.string.essential_reset_group, group))) { _, which ->
                        Spending.setGroupEssential(context, group, if (which == 0) !essential else null)
                        if (isAdded) render()
                    }
                    .show()
            }
            .show()
    }

    /** Homes and work: set on Social's page, opened from here */
    private fun placesCard() {
        val context = requireContext()
        val card = Card(getString(R.string.settings_places), icon = StatIcons.SOCIAL, iconColor = faint(200))
        val social = AutoStats.id(context, AutoStats.SOCIAL)
        fun open() = social?.let { id -> runCatching { findNavController().navigate(R.id.action_global_statFragment, bundleOf(StatFragment.STAT_ID to id)) } }
        val homes = Social.homes(context)
        val work = Social.work(context)
        card.body(
            *homes.mapIndexed { slot, home ->
                row(card, home?.name ?: getString(R.string.social_home_n, slot + 1), home?.ssid ?: getString(R.string.social_not_set)) { open() }
            }.toTypedArray(),
            row(card, work?.name ?: getString(R.string.work), work?.ssid ?: getString(R.string.social_work_not_set)) { open() },
            placesRow(card),
            row(card, getString(R.string.places_delete), getString(R.string.places_delete_note)) {
                AlertDialog.Builder(context)
                    .setMessage(R.string.places_delete_ask)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.places_delete) { _, _ ->
                        app.olauncher.helper.Places.deleteHistory(context)
                        if (isAdded) render()
                    }
                    .show()
            },
        )
    }

    /** Finding places on or off, with today's location checks and Wi-Fi scans */
    private fun placesRow(card: Card): View {
        val context = requireContext()
        val on = app.olauncher.helper.Places.enabled(context)
        val (checks, scans) = app.olauncher.helper.Places.todayCounts(context)
        val detail = if (on) getString(R.string.places_on_counts, resources.getQuantityString(R.plurals.places_checks, checks, checks), resources.getQuantityString(R.plurals.places_scans, scans, scans)) else getString(R.string.off)
        return row(card, getString(R.string.places_find), detail) {
            app.olauncher.helper.Places.setEnabled(context, !on)
            render()
        }
    }

    /** What swiping left and right on the home screen opens */
    private fun gesturesCard() {
        val context = requireContext()
        val prefs = Prefs(context)
        val card = Card(getString(R.string.settings_swipes))
        fun name(left: Boolean) = when (SwipeTargets.get(context, left)) {
            SwipeTargets.Target.APP -> (if (left) prefs.appNameSwipeLeft else prefs.appNameSwipeRight).ifEmpty { getString(R.string.swipe_an_app) }
            SwipeTargets.Target.STATS -> getString(R.string.stats_title)
            SwipeTargets.Target.STATS_HOME -> getString(R.string.swipe_stats_home)
        }
        fun pick(left: Boolean) {
            val targets = SwipeTargets.Target.values()
            AlertDialog.Builder(context)
                .setTitle(if (left) R.string.swipe_left_opens else R.string.swipe_right_opens)
                .setSingleChoiceItems(targets.map {
                    when (it) {
                        SwipeTargets.Target.APP -> getString(R.string.settings_swipe_app)
                        SwipeTargets.Target.STATS -> getString(R.string.stats_title)
                        SwipeTargets.Target.STATS_HOME -> getString(R.string.swipe_stats_home)
                    }
                }.toTypedArray(), targets.indexOf(SwipeTargets.get(context, left))) { dialog, which ->
                    dialog.dismiss()
                    SwipeTargets.set(context, left, targets[which])
                    if (isAdded) render()
                }
                .show()
        }
        card.body(
            row(card, getString(R.string.swipe_left_opens), name(true)) { pick(true) },
            row(card, getString(R.string.swipe_right_opens), name(false)) { pick(false) },
        )
    }

    /** Whether stats added later show on the stats Home straight away */
    private fun homeCard() {
        val context = requireContext()
        val card = Card(getString(R.string.stats_home), icon = StatIcons.HOME, iconColor = faint(200))
        val on = StatsHomeFragment.showsNewStats(context)
        card.body(row(card, getString(R.string.settings_new_on_home), getString(if (on) R.string.on else R.string.off)) {
            StatsHomeFragment.setShowsNewStats(context, !on)
            render()
        })
    }

    /** A report of the last month or three, for an appointment */
    private fun reportCard() {
        val card = Card(getString(R.string.report))
        card.body(row(card, getString(R.string.report_make), getString(R.string.report_note)) { askReport() })
    }

    private fun askReport() {
        val context = requireContext()
        val spans = listOf(30, 90, 180)
        AlertDialog.Builder(context)
            .setTitle(R.string.report_make)
            .setItems(spans.map { getString(R.string.last_days, it) }.toTypedArray()) { _, i ->
                reportDays = spans[i]
                // So the launcher stays on this page while the file is picked, instead of going back home
                androidx.lifecycle.ViewModelProvider(requireActivity())[app.olauncher.MainViewModel::class.java].isPickingFile = true
                createReport.launch(getString(R.string.report_file, java.time.LocalDate.now().toString()))
            }
            .show()
    }

    private var reportDays = 30

    private val createReport = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        val days = reportDays
        Thread {
            val ok = runCatching { Report.write(context, uri, days) }.onFailure { android.util.Log.e("Report", "Failed", it) }.isSuccess
            view?.post { if (isAdded) android.widget.Toast.makeText(context, if (ok) R.string.report_done else R.string.report_failed, android.widget.Toast.LENGTH_LONG).show() }
        }.start()
    }
}

/**
 * The header the stats pages share: ‹, an icon, and the title, large. Tapping ‹ goes back.
 */
fun pageHeader(context: android.content.Context, icon: String?, title: String, onBack: () -> Unit): View {
    val ink = context.getColorFromAttr(R.attr.primaryColor)
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12.dpToPx(), 0, 20.dpToPx(), 16.dpToPx())
        addView(TextView(context, null, 0, R.style.TextLarge).apply {
            text = "‹"
            textSize = 28f
            setTextColor(ink)
            setPadding(8.dpToPx(), 0, 14.dpToPx(), 0)
            contentDescription = context.getString(R.string.close)
            setOnClickListener { onBack() }
        })
        if (icon != null) addView(View(context).apply { background = StatIcons.drawable(icon, ink, 24.dpToPx()) },
            LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply { marginEnd = 12.dpToPx() })
        addView(TextView(context, null, 0, R.style.TextLarge).apply {
            text = title
            textSize = 28f
            setTextColor(ink)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
