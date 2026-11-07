package app.olauncher.ui

import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.Battery
import app.olauncher.helper.Workload
import app.olauncher.helper.usageStats.EventLogWrapper
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The phone's battery: where it is now and how long it'll last, the last two days as a line, how fast it
 * drains with the screen on and off, how often it's charged, and what uses it most.
 */
class BatteryFragment : CardPageFragment() {

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
            addView(pageHeader(context, StatIcons.BATTERY, getString(R.string.battery)) { findNavController().popBackStack() })
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
        runCatching { Battery.record(requireContext()) }
        render()
    }

    private val green = 0xFF81C784.toInt()

    private fun render() {
        cardList.removeAllViews()
        val samples = Battery.samples(requireContext())
        nowCard(samples)
        lineCard(samples)
        drainCard(samples)
        chargingCard(samples)
        usesCard()
        ownCard(samples)
    }

    /** Whether JLauncher's own use shows for screen-off time only */
    private var screenOffOnly = true

    /**
     * What JLauncher itself uses, part by part, measured as each runs, against what the phone lost over
     * the same time: so a drain can be put down to one part of it, or ruled out.
     */
    private fun ownCard(samples: List<Battery.Sample>) {
        val context = requireContext()
        val card = Card(getString(R.string.battery_own))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addBody(body)
        fun fill() {
            card.segment(listOf(R.string.battery_screen_off_short, R.string.battery_all_day), if (screenOffOnly) 0 else 1) {
                screenOffOnly = it == 0
                fill()
            }
            body.removeAllViews()
            val since = System.currentTimeMillis() - Battery.DAY_MS
            val uses = Workload.since(context, since, screenOffOnly)
            if (uses.isEmpty()) {
                body.addView(card.textView(14f, 170).apply { text = getString(R.string.battery_own_waiting) })
                return
            }
            val capacity = Workload.capacityMah(context)
            fun percent(mAh: Double) = mAh * 100 / capacity
            val total = percent(uses.values.sumOf { it.mAh })
            val lost = Battery.lost(samples, since, screenOffOnly)
            body.addView(card.textView(14f, 230).apply {
                text = getString(if (screenOffOnly) R.string.battery_own_summary_off else R.string.battery_own_summary_all, share(total), lost)
            })
            if (lost > 0) body.addView(card.textView(12.5f, 150).apply {
                val ofDrain = total * 100 / lost
                val shown = if (ofDrain < 1) getString(R.string.battery_under_one) else "${ofDrain.roundToInt().coerceAtMost(100)}%"
                text = getString(if (ofDrain >= 25) R.string.battery_own_big else R.string.battery_own_small, shown)
                updatePadding(top = 4.dpToPx(), bottom = 6.dpToPx())
            })
            val most = uses.values.maxOf { it.mAh }
            uses.forEach { (part, use) ->
                body.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 7.dpToPx(), 0, 7.dpToPx())
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(card.textView(15f, 240).apply { text = partName(part) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(card.textView(14f, 200).apply { text = share(percent(use.mAh)) })
                    })
                    addView(View(context).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 3.dpToPx().toFloat()
                            setColor(faint(120))
                        }
                    }, LinearLayout.LayoutParams(((resources.displayMetrics.widthPixels - 80.dpToPx()) * (use.mAh / most)).toInt().coerceAtLeast(3.dpToPx()), 4.dpToPx()).apply {
                        topMargin = 5.dpToPx()
                    })
                    addView(card.textView(12.5f, 140).apply {
                        text = partDetails(use)
                        updatePadding(top = 4.dpToPx())
                    })
                })
            }
            body.addView(card.textView(12f, 110).apply {
                text = getString(R.string.battery_own_note)
                updatePadding(top = 8.dpToPx())
            })
        }
        fill()
    }

    /** A share of the battery: two places when it's small, as most of these are */
    private fun share(percent: Double) = when {
        percent < 0.01 -> "<0.01%"
        percent < 1 -> String.format(Locale.getDefault(), "%.2f%%", percent)
        else -> String.format(Locale.getDefault(), "%.1f%%", percent)
    }

    private fun partName(part: String) = getString(when (part) {
        Workload.PLACES -> R.string.battery_part_places
        Workload.WIFI -> R.string.battery_part_wifi
        Workload.STEPS -> R.string.battery_part_steps
        Workload.BATTERY -> R.string.battery_part_battery
        Workload.NOTIFICATIONS -> R.string.battery_part_notifications
        Workload.MUSIC -> R.string.battery_part_music
        Workload.WEATHER -> R.string.battery_part_weather
        Workload.PEOPLE -> R.string.battery_part_people
        else -> R.string.battery_part_other
    })

    /** "Ran 32 times · 1.4s working · 18 location checks · 6 Wi-Fi scans" */
    private fun partDetails(use: Workload.Use): String {
        val r = resources
        return listOfNotNull(
            use.runs.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.battery_runs, it.toInt(), it.toInt()) },
            use.cpuUs.takeIf { it >= 1000 }?.let { working(it) },
            use.locations.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.battery_locations, it.toInt(), it.toInt()) },
            use.scans.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.battery_scans, it.toInt(), it.toInt()) },
            use.downloads.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.battery_downloads, it.toInt(), it.toInt()) },
        ).joinToString(" · ")
    }

    /** Processor time: "40ms", "1.4s" or "3m 10s" working */
    private fun working(us: Long): String {
        val ms = us / 1000
        val text = when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> String.format(Locale.getDefault(), "%.1fs", ms / 1000f)
            else -> "${ms / 60_000}m ${ms % 60_000 / 1000}s"
        }
        return getString(R.string.battery_working, text)
    }

    private fun hours(h: Float) = when {
        h >= 1 -> String.format(Locale.getDefault(), "%.0fh", h)
        else -> String.format(Locale.getDefault(), "%.0fm", h * 60)
    }

    private fun rate(r: Float) = String.format(Locale.getDefault(), "%.1f%%", r)

    /** The level now, and how long until empty or full */
    private fun nowCard(samples: List<Battery.Sample>) {
        val now = samples.lastOrNull() ?: return Card(getString(R.string.battery_now)).showNote(getString(R.string.battery_waiting))
        val card = Card(getString(R.string.battery_now))
        val dayAgo = System.currentTimeMillis() - Battery.DAY_MS
        val caption = if (now.charging) {
            // Its usual charging speed, from the charges kept
            val speeds = Battery.charges(samples).filter { it.end > it.start && it.toLevel > it.fromLevel }
                .map { (it.toLevel - it.fromLevel) / ((it.end - it.start) / 3_600_000f) }
            val speed = speeds.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            listOfNotNull(getString(R.string.battery_charging),
                speed?.takeIf { it > 0 && now.level < 100 }?.let { getString(R.string.battery_full_in, hours((100 - now.level) / it)) }).joinToString(" · ")
        } else {
            val on = Battery.drainPerHour(samples, dayAgo, true)
            val off = Battery.drainPerHour(samples, dayAgo, false)
            // Time left as the phone's usually used: mostly off, some on
            val blended = listOfNotNull(on?.let { it * 0.25f }, off?.let { it * 0.75f }).takeIf { on != null && off != null }?.sum() ?: on ?: off
            val unplugged = Battery.charges(samples).lastOrNull()?.end
            listOfNotNull(
                unplugged?.let { getString(R.string.battery_off_charger, hours((System.currentTimeMillis() - it) / 3_600_000f)) },
                blended?.takeIf { it > 0 }?.let { getString(R.string.battery_left, hours(now.level / it)) },
            ).joinToString(" · ")
        }
        card.headline("${now.level}%", caption)
        card.scene.visibility = View.GONE
    }

    /** The last two days as a line, charging in green */
    private fun lineCard(samples: List<Battery.Sample>) {
        val end = System.currentTimeMillis()
        val start = end - 2 * Battery.DAY_MS
        val shown = samples.filter { it.time >= start }
        if (shown.size < 2) return
        val card = Card(getString(R.string.battery_two_days))
        val ink = faint(200)
        card.describe = { key -> getString(R.string.battery_at, dateTimeText(key as Long), shown.minByOrNull { Math.abs(it.time - key) }?.level ?: 0) }
        card.draw { s ->
            val top = s.dp(8f)
            val bottom = top + s.dp(110f)
            fun x(t: Long) = (t - start) / (end - start).toFloat() * s.width
            fun y(level: Int) = bottom - level / 100f * (bottom - top)
            listOf(0, 50, 100).forEach { l -> s.line(0f, y(l), s.width, y(l), faint(26), s.dp(1f)) }
            shown.zipWithNext().forEach { (a, b) ->
                // A long gap is drawn flat, as it's not known what happened in it
                s.line(x(a.time), y(a.level), x(b.time), y(if (b.time - a.time > 6 * 3_600_000L) a.level else b.level),
                    if (a.charging) green else ink, s.dp(2f))
            }
            s.line(x(shown.last().time), y(shown.last().level), s.width, y(shown.last().level), if (shown.last().charging) green else ink, s.dp(2f))
            // Midnights
            var day = LocalDate.now()
            while (true) {
                val t = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (t < start) break
                s.line(x(t), top, x(t), bottom, faint(40), s.dp(1f))
                s.text(day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()), x(t) + s.dp(4f), bottom + s.dp(14f), faint(140), s.sp(11f), android.graphics.Paint.Align.LEFT)
                day = day.minusDays(1)
            }
            // Each hour can be tapped for the level then
            var t = start
            while (t < end) {
                s.target(t + 1_800_000L, RectF(x(t), top, x(t + 3_600_000L), bottom))
                t += 3_600_000L
            }
            s.height = bottom + s.dp(20f)
        }
        card.keyItem(ink, getString(R.string.battery_on_battery))
        card.keyItem(green, getString(R.string.battery_charging))
    }

    private fun dateTimeText(time: Long): String {
        val at = java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        return dateText(at.toLocalDate()) + " " + clock(at.toLocalTime())
    }

    /** How fast it drains, screen on and off, over the last week */
    private fun drainCard(samples: List<Battery.Sample>) {
        val week = System.currentTimeMillis() - 7 * Battery.DAY_MS
        val on = Battery.drainPerHour(samples, week, true)
        val off = Battery.drainPerHour(samples, week, false)
        if (on == null && off == null) return
        tiles(Card(getString(R.string.battery_drain)), getString(R.string.battery_drain_note), listOf(
            (on?.let(::rate) ?: "–") to getString(R.string.battery_screen_on),
            (off?.let(::rate) ?: "–") to getString(R.string.battery_screen_off),
            (on?.takeIf { it > 0 }?.let { hours(100 / it) } ?: "–") to getString(R.string.battery_full_lasts),
        ))
    }

    /** How often it's charged, from where, and to where */
    private fun chargingCard(samples: List<Battery.Sample>) {
        val week = System.currentTimeMillis() - 7 * Battery.DAY_MS
        val charges = Battery.charges(samples).filter { it.start >= week }
        val perDay = Battery.chargesPerDay(samples, 7) ?: return
        tiles(Card(getString(R.string.battery_charges)), getString(R.string.battery_last_week), listOf(
            String.format(Locale.getDefault(), "%.1f", perDay) to getString(R.string.battery_charges_day),
            (charges.takeIf { it.isNotEmpty() }?.map { it.fromLevel }?.average()?.roundToInt()?.let { "$it%" } ?: "–") to getString(R.string.battery_plugged_at),
            (charges.filter { it.end < samples.last().time || !samples.last().charging }.takeIf { it.isNotEmpty() }
                ?.map { it.toLevel }?.average()?.roundToInt()?.let { "$it%" } ?: "–") to getString(R.string.battery_unplugged_at),
        ))
    }

    /**
     * What uses it: the screen is most of the drain, so each app's screen time today; and JLauncher's own,
     * with the checks it does in the background.
     */
    private fun usesCard() {
        val context = requireContext()
        if (!context.appUsagePermissionGranted()) return
        val card = Card(getString(R.string.battery_uses))
        card.subtitle(getString(R.string.battery_today))
        card.showNote(getString(R.string.insights_working))
        val app = context.applicationContext
        Thread {
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            // Today's own screen time per app, as Screen time works it out (Android's totals can run over days)
            val usage = runCatching {
                val events = EventLogWrapper(app)
                events.aggregateForegroundStats(events.getForegroundStatsByTimestamps(start, System.currentTimeMillis()))
                    .groupBy { it.applicationId }.mapValues { (_, list) -> list.sumOf { it.timeUsed } }.filterValues { it > 60_000L }
            }.getOrDefault(emptyMap())
            view?.post { if (isAdded) fillUses(card, usage) }
        }.start()
    }

    private fun fillUses(card: Card, usage: Map<String, Long>) {
        val context = requireContext()
        val total = usage.values.sum().takeIf { it > 0 } ?: return card.showNote(getString(R.string.battery_no_use))
        card.note.visibility = View.GONE
        val pm = context.packageManager
        fun name(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val self = context.packageName
        val top = usage.entries.filter { it.key != self }.sortedByDescending { it.value }.take(6)
        val rows = top.map { Triple(name(it.key), it.value, null as String?) } +
            Triple(getString(R.string.app_name), usage[self] ?: 0L, getString(R.string.battery_self_below))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { (label, ms, extra) ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 7.dpToPx(), 0, 7.dpToPx())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(card.textView(15f, 240).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(card.textView(14f, 160).apply { text = listOf(duration(ms), "${(ms * 100 / total)}%").joinToString(" · ") })
                })
                // A bar for its share of the screen's time
                addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 3.dpToPx().toFloat()
                        setColor(faint(120))
                    }
                }, LinearLayout.LayoutParams(0, 4.dpToPx()).apply {
                    topMargin = 5.dpToPx()
                    width = ((resources.displayMetrics.widthPixels - 80.dpToPx()) * ms / total).toInt().coerceAtLeast(3.dpToPx())
                })
                if (extra != null) addView(card.textView(12.5f, 140).apply {
                    text = extra
                    updatePadding(top = 4.dpToPx())
                })
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
        card.scene.visibility = View.GONE
    }
}
