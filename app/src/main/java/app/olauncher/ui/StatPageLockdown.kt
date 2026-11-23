package app.olauncher.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.BlockItems
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownLog
import app.olauncher.helper.dpToPx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

// Lockdown's stat page: what's on now and what it blocks, then time locked, breaks and blocked attempts

/** All of Lockdown's cards */
internal fun StatPageFragment.lockdownCards() {
    lockdownNowCard()
    lockdownTimeCard()
    lockdownWeekCard()
    lockdownBlockedCard()
}

private fun StatPageFragment.openLockdown() {
    runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.lockdownFragment) }
}

/** At the top: on or off and until when, on each device; what it blocks; and the way to its settings */
private fun StatPageFragment.lockdownNowCard() {
    val context = requireContext()
    val prefs = Prefs(context)
    val on = Lockdown.isOn(context, prefs)
    val card = Card(getString(R.string.lockdown_now))
    card.headerEnd.addView(card.textView(Style.TEXT_SMALL, 200).apply {
        text = getString(R.string.settings)
        setPadding(12.dpToPx(), 4.dpToPx(), 0, 4.dpToPx())
        setOnClickListener { openLockdown() }
    })
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val colour = if (on) stat.color else faint(120)
    // The lock and the big status
    body.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val open = Lockdown.onBreak(prefs, Lockdown.Device.PHONE) || !on
        addView(View(context).apply { background = StatIcons.drawable(if (open) StatIcons.UNLOCK else StatIcons.LOCK, colour, 34.dpToPx()) },
            LinearLayout.LayoutParams(34.dpToPx(), 34.dpToPx()).apply { marginEnd = 14.dpToPx() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context, null, 0, R.style.TextLarge).apply {
                text = LockdownGate.status(context)
                textSize = Style.TEXT_LARGE
                setTextColor(ink)
            })
            val left = Lockdown.activeUntil(context, prefs)?.takeIf { on }?.let { it - System.currentTimeMillis() }
            val why = LockdownGate.whyText(context)?.takeIf { on }
            listOfNotNull(left?.let { getString(R.string.lockdown_left, LockdownGate.duration(context, it)) }, why).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                addView(card.textView(Style.TEXT_BODY, 165).apply { text = it })
            }
        })
    })
    // Each device, when there's more than the phone
    if (Lockdown.several(context)) {
        body.addView(card.textView(Style.TEXT_BODY, 200).apply {
            text = Lockdown.deviceList(context).joinToString("\n") {
                LockdownGate.deviceName(context, it.bit) + " · " + LockdownGate.deviceStatus(context, it)
            }
            setLineSpacing(0f, 1.25f)
            updatePadding(top = 12.dpToPx())
        })
    }
    // What it blocks, on each device
    val items = prefs.lockdownItems.let { if (prefs.lockdownMatchDevices) BlockItems.matched(it) else it }
    fun counts(apps: Int, sites: Int) = listOfNotNull(
        resources.getQuantityString(R.plurals.lockdown_apps_count, apps, apps).takeIf { apps > 0 },
        resources.getQuantityString(R.plurals.lockdown_sites_count, sites, sites).takeIf { sites > 0 },
    ).joinToString(" · ").ifEmpty { getString(R.string.lockdown_none) }
    val phoneApps = if (prefs.lockdownAppMode == Lockdown.ALLOW) null else BlockItems.phoneApps(items).size
    val lines = mutableListOf<Pair<String, String>>()
    lines += LockdownGate.deviceName(context, Lockdown.Device.PHONE.bit) to
        (phoneApps?.let { counts(it, BlockItems.phoneSites(items).size) } ?: getString(R.string.lockdown_all_but, prefs.lockdownAllowedApps.size))
    if (Lockdown.macPaired(context)) lines += LockdownGate.deviceName(context, Lockdown.Device.MAC.bit) to
        counts(BlockItems.macApps(items).size, BlockItems.macSites(items).size)
    if (Lockdown.tvPaired(context)) lines += LockdownGate.deviceName(context, Lockdown.Device.TV.bit) to counts(BlockItems.tvApps(items).size, 0)
    body.addView(card.textView(Style.TEXT_SMALL, 140).apply {
        text = getString(R.string.lockdown_what_it_blocks).uppercase()
        letterSpacing = 0.1f
        updatePadding(top = 16.dpToPx(), bottom = 4.dpToPx())
    })
    lines.forEach { (device, text) ->
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(card.textView(Style.TEXT_BODY, 230).apply { this.text = device }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(card.textView(Style.TEXT_BODY, 190).apply { this.text = text })
            updatePadding(top = 3.dpToPx(), bottom = 3.dpToPx())
            setOnClickListener {
                runCatching {
                    androidx.navigation.fragment.NavHostFragment.findNavController(this@lockdownNowCard).navigate(R.id.lockdownPageFragment,
                        androidx.core.os.bundleOf(LockdownPageFragment.PAGE to LockdownPageFragment.BLOCKS))
                }
            }
        })
    }
    // The way to the settings
    body.addView(FlowLayout(context).apply {
        updatePadding(top = 14.dpToPx())
        fun pill(text: String, onTap: () -> Unit) = TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            background = GradientDrawable().apply {
                cornerRadius = Style.radiusLarge
                setColor(faint(36))
            }
            setPadding(18.dpToPx(), 9.dpToPx(), 18.dpToPx(), 9.dpToPx())
            setOnClickListener { onTap() }
        }
        addView(pill(getString(R.string.lockdown_settings)) { openLockdown() })
    })
    card.addBody(body)
}

/** Time in lockdown each day, today's against the week's */
private fun StatPageFragment.lockdownTimeCard() {
    val context = requireContext()
    val days = LockdownLog.days(Prefs(context))
    val today = LocalDate.now()
    val card = Card(getString(R.string.lockdown_time_locked))
    val week = (1..7L).map { days[today.minusDays(it)]?.phoneMs ?: 0L }
    card.headline(LockdownGate.durationOrNone(context, days[today]?.phoneMs ?: 0L),
        getString(R.string.lockdown_time_caption, LockdownGate.durationOrNone(context, week.average().roundToLong())))
    dayBars(card, days.mapValues { it.value.phoneMs / 3_600_000f }, stat.color) { date, _ ->
        LockdownGate.durationOrNone(context, days[date]?.phoneMs ?: 0L)
    }
}

/** This week in figures: time locked on each device, breaks, the longest day */
private fun StatPageFragment.lockdownWeekCard() {
    val context = requireContext()
    val prefs = Prefs(context)
    val days = LockdownLog.days(prefs)
    val today = LocalDate.now()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val thisWeek = days.filterKeys { !it.isBefore(monday) && !it.isAfter(today) }
    val card = Card(getString(R.string.lockdown_this_week))
    val mondayMs = Lockdown.epoch(monday.atStartOfDay())
    val breaks = Lockdown.breakLog(prefs).filter { it.start >= mondayMs && (it.device == "phone" || Lockdown.breaks(prefs).shared) }
    val tiles = mutableListOf(
        LockdownGate.durationOrNone(context, thisWeek.values.sumOf { it.phoneMs }) to getString(R.string.lockdown_device_phone),
    )
    if (Lockdown.macPaired(context)) tiles += LockdownGate.durationOrNone(context, thisWeek.values.sumOf { it.macMs }) to getString(R.string.lockdown_device_mac)
    if (Lockdown.tvPaired(context)) tiles += LockdownGate.durationOrNone(context, thisWeek.values.sumOf { it.tvMs }) to getString(R.string.lockdown_device_tv)
    tiles += breaks.size.toString() to getString(R.string.lockdown_breaks_taken)
    tiles(card, null, tiles)
    val longest = days.maxByOrNull { it.value.phoneMs }?.takeIf { it.value.phoneMs > 0 }
    val breakTime = thisWeek.values.sumOf { it.breakMs }
    val lines = listOfNotNull(
        longest?.let { getString(R.string.lockdown_longest_day, LockdownGate.durationOrNone(context, it.value.phoneMs), dateText(it.key)) },
        getString(R.string.lockdown_break_time, LockdownGate.durationOrNone(context, breakTime)).takeIf { breakTime > 0 },
        Lockdown.streak(context, prefs)?.let { getString(R.string.lockdown_streak_line, resources.getQuantityString(R.plurals.lockdown_days, it, it)) },
        usualHours(days)?.let { getString(R.string.lockdown_usually, it) },
    )
    if (lines.isNotEmpty()) card.root.addView(card.textView(Style.TEXT_BODY, 190).apply {
        text = lines.joinToString("\n")
        setLineSpacing(0f, 1.3f)
        updatePadding(top = 12.dpToPx())
    })
}

/** When in the day lockdown is usually on, over the last month: "22:00–07:00", or null without enough */
private fun StatPageFragment.usualHours(days: Map<LocalDate, LockdownLog.Day>): String? {
    val since = LocalDate.now().minusDays(30)
    val recent = days.filterKeys { it > since }.values.filter { it.spans.isNotEmpty() }
    if (recent.size < 3) return null
    val counts = IntArray(24)
    recent.forEach { day -> day.spans.forEach { span -> (span.first / 60..(span.last / 60).coerceAtMost(23)).forEach { counts[it]++ } } }
    val usual = (0 until 24).filter { counts[it] * 2 >= recent.size }
    if (usual.isEmpty() || usual.size == 24) return null
    // The longest run of usual hours, going round midnight
    var best = 0 to 0
    for (start in 0 until 24) {
        if (start !in usual || (start + 23) % 24 in usual) continue
        var length = 0
        while (length < 24 && (start + length) % 24 in usual) length++
        if (length > best.second) best = start to length
    }
    if (best.second == 0) return null
    fun hour(h: Int) = clock(java.time.LocalTime.of(h % 24, 0))
    return hour(best.first) + "–" + hour(best.first + best.second)
}

/** Apps and sites stopped each day */
private fun StatPageFragment.lockdownBlockedCard() {
    val days = Lockdown.blockedDays(Prefs(requireContext()))
    if (days.isEmpty()) return
    val card = Card(getString(R.string.lockdown_blocked_attempts))
    val today = LocalDate.now()
    val week = (1..7L).map { days[today.minusDays(it)] ?: 0 }
    card.headline((days[today] ?: 0).toString(), getString(R.string.lockdown_stat_caption, Math.round(week.average()).toInt()))
    dayBars(card, days.mapValues { it.value.toFloat() }, faint(170)) { _, value ->
        getString(R.string.lockdown_blocked_count, value.toInt())
    }
}
