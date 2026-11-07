package app.olauncher.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.helper.Spending
import app.olauncher.helper.StatKind
import app.olauncher.helper.dpToPx
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.CUPS_HOT
import app.olauncher.ui.StatPageFragment.Companion.CUP_DAYS
import app.olauncher.ui.StatPageFragment.Companion.LIMIT_OVER_COLOR
import app.olauncher.ui.StatPageFragment.Companion.LIMIT_WEEKS
import app.olauncher.ui.StatPageFragment.Companion.RECENT_DAYS
import app.olauncher.ui.StatPageFragment.Companion.SPENDING_SHOWN
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Money and Coffee: what was spent, the weekly limit, and cups, as cards on [StatPageFragment]

/**
 * Coffee's days as stacks of little cups, one per cup: its place in the row, so the faces go 0 to 4
 * cups. Cups past the [CUPS_HOT]rd stand out. A dot is a day with none; a gap, a day not logged.
 */
internal fun StatPageFragment.cupsCard() {
    val card = Card(getString(R.string.stat_cups))
    card.keyItem(coffeeCup, getString(R.string.a_cup))
    card.keyItem(hotCup, getString(R.string.cup_past, CUPS_HOT + 1))
    fun cupsOn(date: LocalDate): Int? = dayOf(date)?.face(stat.id)?.let { stat.faces.indexOf(it).coerceAtLeast(0) }
    fun draw() {
        val days = dailyDates(card, CUP_DAYS, ::draw)
        val cups = days.map { cupsOn(it) }
        card.describe = { key ->
            val date = key as LocalDate
            val count = cupsOn(date)
            listOf(dateText(date), count?.let { resources.getQuantityString(R.plurals.cup_count, it, it) } ?: "–").joinToString(" · ")
        }
        card.draw { s ->
            val column = s.width / days.size
            val cup = minOf(column * 0.6f, s.dp(24f))
            val cupHeight = cup * 0.72f
            val gap = s.dp(4f)
            val most = maxOf(cups.maxOf { it ?: 0 }, stat.faces.size - 1)
            val base = s.dp(4f) + most * (cupHeight + gap)
            s.line(0f, base + s.dp(2f), s.width, base + s.dp(2f), faint(40), s.dp(1.5f))
            cups.forEachIndexed { i, count ->
                val x = i * column + (column - cup) / 2 - cup * 0.06f
                if (count == 0) s.circle(x + cup / 2, base - s.dp(4f), s.dp(2f), faint(110))
                repeat(count ?: 0) { k ->
                    val y = base - (k + 1) * (cupHeight + gap) + gap
                    val color = if (k >= CUPS_HOT) hotCup else coffeeCup
                    s.path(Path().apply {
                        moveTo(x, y)
                        lineTo(x + cup, y)
                        lineTo(x + cup * 0.88f, y + cupHeight)
                        lineTo(x + cup * 0.12f, y + cupHeight)
                        close()
                    }, color)
                    s.path(Path().apply {
                        moveTo(x + cup * 0.94f, y + cupHeight * 0.18f)
                        quadTo(x + cup * 1.3f, y + cupHeight * 0.3f, x + cup * 0.94f, y + cupHeight * 0.78f)
                    }, color, cup * 0.12f)
                }
                s.text(days[i].dayOfMonth.toString(), i * column + column / 2, base + s.dp(22f), faint(140), s.sp(12f))
            }
            s.height = base + s.dp(28f)
            days.forEachIndexed { i, date ->
                val x = i * column
                s.target(date, RectF(x, 0f, x + column, s.height), RectF(x, 0f, x + column, base + s.dp(6f)))
            }
        }
    }
    draw()
}

/**
 * Purchases read from CommBank's and Google Wallet's notifications, which fill in Money spent: today's
 * total (leaving out essentials) and the latest [SPENDING_SHOWN]. Until notification access is on, a
 * way to turn it on.
 */
internal fun StatPageFragment.spendingCard() {
    val context = requireContext()
    val card = Card(getString(R.string.today))
    val on = Spending.isEnabled(context)
    spendingShownOn = on
    if (!on) {
        card.showNote(getString(R.string.spending_off))
        card.chipRow(listOf(getString(R.string.spending_turn_on) to ink), null) { Spending.openSettings(context) }
        return
    }
    val purchases = Spending.purchases(context)
    val today = LocalDate.now()
    val todays = purchases.filter { it.date == today }
    val spent = todays.filter { !it.essential }
    val essentials = todays.filter { it.essential }.sumOf { it.cents }
    card.headline(money(spent.sumOf { it.cents }), listOfNotNull(
        resources.getQuantityString(R.plurals.spending_today, spent.size, spent.size),
        essentials.takeIf { it > 0 }?.let { getString(R.string.plus_essentials, money(it)) },
    ).joinToString(" · "))
    if (purchases.isEmpty()) {
        card.showNote(getString(R.string.spending_none))
        return
    }
    val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    purchases.take(SPENDING_SHOWN).forEach { purchase ->
        val time = java.time.Instant.ofEpochMilli(purchase.time).atZone(java.time.ZoneId.systemDefault())
        val `when` = if (purchase.date == today) time.format(timeFormat).lowercase(Locale.getDefault()) else dateText(purchase.date)
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            updatePadding(top = 7.dpToPx(), bottom = 7.dpToPx())
            alpha = if (purchase.essential) 0.6f else 1f
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnLongClickListener {
                askEssential(purchase)
                true
            }
            addView(card.textView(15f, 255).apply {
                text = money(purchase.cents)
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (purchase.essential) faint(255) else moneyGold)
            }, LinearLayout.LayoutParams(84.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(card.textView(14f, 235).apply {
                    text = purchase.merchant
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(card.textView(12f, 140).apply {
                    text = listOfNotNull(purchase.category, getString(R.string.essential).takeIf { purchase.essential }).joinToString(" · ").ifEmpty { `when` }
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(card.textView(12f, 130).apply {
                text = `when`
                updatePadding(left = 10.dpToPx())
            })
        })
    }
    card.addBody(list)
}

/**
 * The weekly limit: what's left of it this week, and the weeks spent (leaving out essentials) as bars
 * against it, the ones over it in red. Without a limit, a way to set one.
 */
internal fun StatPageFragment.weekLimitCard() {
    val context = requireContext()
    val spent = real ?: return
    val card = Card(getString(R.string.week_limit))
    val limit = Spending.weekLimitCents(context)
    if (limit <= 0) {
        card.showNote(getString(R.string.week_limit_none))
        card.chipRow(listOf(getString(R.string.week_limit_set) to moneyGold), null) { askWeekLimit { if (isAdded) render() } }
        return
    }
    val today = LocalDate.now()
    val monday = today.with(DayOfWeek.MONDAY)
    val first = maxOf(spent.keys.minOrNull()?.with(DayOfWeek.MONDAY) ?: monday, monday.minusWeeks(LIMIT_WEEKS - 1L))
    val weeks = generateSequence(first) { it.plusWeeks(1) }.takeWhile { it <= monday }.toList()
    val daily = Spending.dailyCents(context)
    val weekCents = weeks.map { Spending.weekSpentCents(daily, it) }
    // With carry-over each week has its own allowance
    val allowances = weeks.map { Spending.allowanceCents(context, it, daily) }
    val carry = Spending.carryOver(context)
    val thisWeek = weekCents.last()
    val allowance = allowances.last()
    val over = LIMIT_OVER_COLOR
    fun dollars(cents: Long) = if (cents % 100 == 0L) "$" + String.format(Locale.getDefault(), "%,d", cents / 100) else money(cents)
    val carried = (allowance - limit).takeIf { carry && it != 0L }?.let {
        getString(if (it > 0) R.string.week_limit_carried else R.string.week_limit_debt, dollars(abs(it)))
    }
    if (thisWeek <= allowance) card.headline(money(allowance - thisWeek), listOfNotNull(getString(R.string.week_limit_left, dollars(allowance)), carried).joinToString(" · "))
    else card.headline(money(thisWeek - allowance), listOfNotNull(getString(R.string.week_limit_over, dollars(allowance)), carried).joinToString(" · "))
    val pastOver = weekCents.indices.count { it < weeks.lastIndex && weekCents[it] > allowances[it] }
    if (weeks.size > 1) card.subtitle(getString(R.string.week_limit_broken, pastOver, weeks.size - 1))
    card.keyItem(moneyGold, getString(R.string.week_limit_under))
    card.keyItem(over, getString(R.string.week_limit_over_key))
    card.chipRow(listOf(getString(R.string.week_limit_change, moneyShort(limit)) to moneyGold), null) { askWeekLimit { if (isAdded) render() } }
    card.describe = { key ->
        val i = key as Int
        val cents = weekCents[i]
        val allowed = allowances[i]
        listOfNotNull(getString(R.string.week_of, weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))),
            getString(R.string.stat_label, getString(R.string.spent), money(cents)),
            if (allowed != limit) getString(R.string.stat_label, getString(R.string.week_limit_allowed), money(allowed)) else null,
            if (cents > allowed) getString(R.string.stat_label, getString(R.string.week_limit_over_by), money(cents - allowed))
            else getString(R.string.stat_label, getString(R.string.week_limit_under_by), money(allowed - cents))).joinToString("\n")
    }
    val most = maxOf(weekCents.maxOrNull() ?: 0L, limit) * 1.1f
    card.draw { s ->
        val top = s.dp(14f)
        val bottom = top + s.dp(96f)
        val column = s.width / LIMIT_WEEKS
        val left = s.width - column * weeks.size
        s.line(0f, bottom, s.width, bottom, faint(30), s.dp(1f))
        weeks.forEachIndexed { i, start ->
            val x = left + i * column
            val cents = weekCents[i]
            val color = if (cents > allowances[i]) over else moneyGold
            val h = cents / most * (bottom - top)
            if (cents == 0L) s.rect(x + s.dp(3f), bottom - s.dp(2f), x + column - s.dp(3f), bottom, faint(45), s.dp(1f))
            else s.rect(x + s.dp(3f), bottom - h, x + column - s.dp(3f), bottom,
                if (i == weeks.lastIndex) color else ColorUtils.setAlphaComponent(color, 170), s.dp(4f))
            s.target(i, RectF(x, 0f, x + column, bottom + s.dp(20f)), RectF(x + s.dp(1f), top - s.dp(2f), x + column - s.dp(1f), bottom + s.dp(2f)))
        }
        // The limit across them all
        val y = bottom - limit / most * (bottom - top)
        var x = 0f
        while (x < s.width) {
            s.line(x, y, minOf(x + s.dp(5f), s.width), y, faint(150), s.dp(1.5f))
            x += s.dp(9f)
        }
        s.text(moneyShort(limit), 0f, y - s.dp(5f), faint(170), s.sp(11f), Paint.Align.LEFT)
        labelIndices(weeks.size, 3).forEach { i ->
            s.text(weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), left + i * column + column / 2, bottom + s.dp(16f), faint(140), s.sp(11f))
        }
        s.height = bottom + s.dp(22f)
    }
}

/** The weekly limit, carrying over, and the home screen widget, then [onDone] */
internal fun androidx.fragment.app.Fragment.askWeekLimit(onDone: () -> Unit) {
    val context = requireContext()
    val current = Spending.weekLimitCents(context)
    val input = EditText(context).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        hint = "$"
        if (current > 0) setText(if (current % 100 == 0L) (current / 100).toString() else String.format(Locale.US, "%.2f", current / 100.0))
        setSelection(text.length)
    }
    val carry = android.widget.CheckBox(context).apply {
        setText(R.string.week_limit_carry)
        isChecked = Spending.carryOver(context)
    }
    val onHome = android.widget.CheckBox(context).apply {
        setText(R.string.week_limit_on_home)
        isChecked = Spending.showOnHome(context)
    }
    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
        addView(input)
        addView(carry)
        addView(onHome)
    }
    AlertDialog.Builder(context)
        .setTitle(R.string.week_limit_ask)
        .setView(box)
        .setPositiveButton(R.string.okay) { _, _ ->
            val dollars = input.text.toString().replace("$", "").replace(",", "").trim().toDoubleOrNull()
            if (dollars != null && dollars > 0) Spending.setWeekLimitCents(context, (dollars * 100).roundToInt().toLong())
            Spending.setCarryOver(context, carry.isChecked)
            Spending.setShowOnHome(context, onHome.isChecked)
            onDone()
        }
        .apply { if (current > 0) setNeutralButton(R.string.week_limit_remove) { _, _ -> Spending.setWeekLimitCents(context, 0); onDone() } }
        .setNegativeButton(R.string.close, null)
        .show()
        .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    input.requestFocus()
}

/**
 * Holding a purchase: count it (or everything in its category, or at its shop without one) as an
 * essential or not, or put it back to how it would go by itself.
 */
internal fun StatPageFragment.askEssential(purchase: Spending.Purchase) {
    val context = requireContext()
    val group = purchase.group
    val rule = Spending.groupRule(context, group)
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += getString(if (purchase.essential) R.string.essential_not_this else R.string.essential_this) to {
        Spending.setEssential(context, purchase, !purchase.essential)
    }
    val groupEssential = rule ?: purchase.usuallyEssential
    options += getString(if (groupEssential) R.string.essential_not_group else R.string.essential_group, group) to {
        Spending.setGroupEssential(context, group, !groupEssential)
    }
    if (purchase.mark != null) options += getString(R.string.essential_reset_this) to { Spending.setEssential(context, purchase, null) }
    if (rule != null) options += getString(R.string.essential_reset_group, group) to { Spending.setGroupEssential(context, group, null) }
    AlertDialog.Builder(context)
        .setTitle(getString(R.string.essential_title, purchase.merchant, money(purchase.cents)))
        .setItems(options.map { it.first }.toTypedArray()) { _, which ->
            options[which].second()
            if (_binding != null) render()
        }
        .show()
}

/** Dollars short enough for a day's square: "$4.50", "$45", "$1.2k" */
internal fun StatPageFragment.squareMoney(cents: Long): String = when {
    cents < 1000 -> String.format(Locale.getDefault(), "$%.2f", cents / 100.0)
    cents < 100_000 -> "$" + (cents + 50) / 100
    else -> String.format(Locale.getDefault(), "$%.1fk", cents / 100_000.0)
}

/**
 * The days as a calendar, each square gold as deep as what was spent (leaving out essentials), with
 * the amount on it. Days from before purchases were read show their face's colour and label instead.
 */
internal fun StatPageFragment.moneyCalendarCard() {
    val card = Card(getString(R.string.spent_each_day))
    val shades = StatKind.MONEY.shades!!
    fun draw() {
        val days = dailyDates(card, redraw = ::draw)
        val cents = days.associateWith { date -> real?.get(date)?.let { (it * 100).roundToInt().toLong() } }
        val most = cents.values.filterNotNull().maxOrNull()?.coerceAtLeast(1) ?: 1
        card.describe = { key ->
            val date = key as LocalDate
            val amount = cents[date]
            val purchases = Spending.purchases(requireContext()).filter { it.date == date }
            (listOf(dateText(date), amount?.let { getString(R.string.stat_label, getString(R.string.spent), money(it)) } ?: dayText(stat, date) ?: "–") +
                purchases.take(4).map { getString(R.string.stat_label, it.merchant, money(it.cents)) }).joinToString("\n")
        }
        card.draw { s ->
            val cell = minOf(s.width / 7.4f, s.dp(48f))
            val left = (s.width - cell * 7) / 2
            val gap = s.dp(3f)
            var day = DayOfWeek.MONDAY
            repeat(7) { i ->
                s.text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), left + i * cell + cell / 2, s.sp(12f), faint(140), s.sp(12f))
                day = day.plus(1)
            }
            val gridTop = s.dp(24f)
            val lead = days.first().dayOfWeek.value - 1
            val corner = s.dp(9f)
            days.forEachIndexed { i, date ->
                val place = i + lead
                val x = left + place % 7 * cell + gap
                val y = gridTop + place / 7 * cell + gap
                val size = cell - gap * 2
                val amount = cents[date]
                val face = dayOf(date)?.face(stat.id)
                val (fill, text) = when {
                    amount != null && amount > 0 -> ColorUtils.blendARGB(shades.first, shades.second, (amount.toFloat() / most).coerceIn(0f, 1f) * 0.85f) to squareMoney(amount)
                    amount != null -> null to "$0"
                    face != null && !stat.isBlank(face) -> stat.faceColor(requireContext(), face) to faceName(stat, face)
                    face != null -> null to "$0"
                    else -> null to null
                }
                if (fill != null) s.rect(x, y, x + size, y + size, fill, corner)
                else if (text != null) s.outline(x, y, x + size, y + size, faint(80), corner, s.dp(1.5f))
                val textColor = if (fill != null && ColorUtils.calculateLuminance(fill) > 0.4) 0xFF1A1A1A.toInt() else if (fill != null) 0xFFFFFFFF.toInt() else faint(150)
                // A day with nothing logged is just its date, faint
                if (text == null) s.text(date.dayOfMonth.toString(), x + size / 2, y + size / 2 + s.sp(4.5f), faint(70), s.sp(12.5f))
                else s.text(date.dayOfMonth.toString(), x + s.dp(6f), y + s.sp(11f), ColorUtils.setAlphaComponent(textColor, 150), s.sp(9.5f), Paint.Align.LEFT)
                text?.let {
                    val textSize = if (it.length > 5) s.sp(10f) else s.sp(12f)
                    s.text(it, x + size / 2, y + size * 0.68f, textColor, textSize, bold = fill != null)
                }
                s.target(date, RectF(x - gap, y - gap, x + size + gap, y + size + gap), RectF(x - gap / 2, y - gap / 2, x + size + gap / 2, y + size + gap / 2), corner)
            }
            val rows = (days.size + lead + 6) / 7
            s.height = gridTop + rows * cell + s.dp(2f)
        }
    }
    draw()
}

/** This month so far against last month by the same day, and the usual day */
internal fun StatPageFragment.moneyMonthCard() {
    val spent = real ?: return
    val today = LocalDate.now()
    val thisMonth = spent.filterKeys { YearMonth.from(it) == YearMonth.from(today) }
    val lastMonth = YearMonth.from(today).minusMonths(1)
    val lastBy = spent.filterKeys { YearMonth.from(it) == lastMonth && it.dayOfMonth <= today.dayOfMonth }
    val card = Card(getString(R.string.this_month))
    val tracked = lastBy.isNotEmpty()
    tiles(card, null, listOfNotNull(
        moneyShort((thisMonth.values.sum() * 100).roundToInt().toLong()) to getString(R.string.so_far),
        if (tracked) moneyShort((lastBy.values.sum() * 100).roundToInt().toLong()) to getString(R.string.last_month_by_now) else null,
        moneyShort((spent.filterKeys { it > today.minusDays(30) }.values.average() * 100).roundToInt().toLong()) to getString(R.string.a_usual_day),
    ))
}

/** What went on each category (or on essentials alone) over the last 30 days */
internal fun StatPageFragment.moneyCategoriesCard(essential: Boolean) {
    val context = requireContext()
    val since = LocalDate.now().minusDays(RECENT_DAYS.toLong())
    val purchases = Spending.purchases(context).filter { it.date > since && it.essential == essential }
    if (purchases.isEmpty()) return
    val card = Card(getString(if (essential) R.string.essentials else R.string.where_it_went))
    card.subtitle(getString(R.string.last_days, RECENT_DAYS))
    val groups = purchases.groupBy { it.category ?: getString(R.string.other) }.mapValues { (_, list) -> list.sumOf { it.cents } }
        .entries.sortedByDescending { it.value }.take(6)
    if (essential) card.headline(money(purchases.sumOf { it.cents }), getString(R.string.essentials_note))
    val color = if (essential) faint(150) else moneyGold
    card.describe = { key ->
        val (name, cents) = groups[key as Int]
        val count = purchases.count { (it.category ?: getString(R.string.other)) == name }
        listOf(name, getString(R.string.stat_label, getString(R.string.spent), money(cents)),
            getString(R.string.stat_label, getString(R.string.purchases), count.toString())).joinToString("\n")
    }
    horizontalBars(card, groups.map { (name, cents) -> Triple(name, (cents / 100).toInt().coerceAtLeast(1), color) }) { "$$it" }
}

/** The shops spent at most over the last 30 days, leaving out essentials */
internal fun StatPageFragment.moneyShopsCard() {
    val since = LocalDate.now().minusDays(RECENT_DAYS.toLong())
    val purchases = Spending.purchases(requireContext()).filter { it.date > since && !it.essential }
    if (purchases.size < 2) return
    val card = Card(getString(R.string.top_shops))
    card.subtitle(getString(R.string.last_days, RECENT_DAYS))
    val shops = purchases.groupBy { it.merchant }.entries.sortedByDescending { (_, list) -> list.sumOf { it.cents } }.take(5)
    card.describe = { key ->
        val (shop, list) = shops[key as Int]
        listOf(shop, getString(R.string.stat_label, getString(R.string.spent), money(list.sumOf { it.cents })),
            getString(R.string.stat_label, getString(R.string.purchases), list.size.toString()),
            getString(R.string.stat_label, getString(R.string.last_time), dateText(list.maxOf { it.date }))).joinToString("\n")
    }
    horizontalBars(card, shops.map { (shop, list) -> Triple(shop, (list.sumOf { it.cents } / 100).toInt().coerceAtLeast(1), moneyGold) }) { "$$it" }
}
