package app.olauncher.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A supplement taken on a schedule rather than asked about every day. Its [changes], oldest first, each
 * say what's taken from their day on: an amount on a schedule, or stopped. [fixes] are days marked by
 * hand as taken (true) or missed (false), which win over the schedule.
 */
data class Supplement(
    val id: String,
    val name: String,
    val unit: String,
    val color: Int,
    val changes: List<Change>,
    val fixes: Map<LocalDate, Boolean> = emptyMap(),
) {
    /**
     * From [from] on: [amount] on [schedule], with its on/off cycle counted from [cycleStart]; or, when
     * [stopped], not at all (the amount and schedule are kept, to start again with).
     */
    data class Change(
        val from: LocalDate,
        val amount: Float,
        val schedule: Schedule,
        val cycleStart: LocalDate = from,
        val stopped: Boolean = false,
    )

    enum class Kind { DAILY, DAYS, WEEKS, WEEKDAYS }

    /** Every day; [on] days then [off] days; [on] weeks then [off] weeks; or the [weekdays] picked */
    data class Schedule(
        val kind: Kind,
        val on: Int = 1,
        val off: Int = 0,
        val weekdays: Set<DayOfWeek> = emptySet(),
    ) {
        /** Whether it's taken on [date], counting a cycle from [cycleStart] */
        fun isOn(date: LocalDate, cycleStart: LocalDate): Boolean = when (kind) {
            Kind.DAILY -> true
            Kind.DAYS -> Math.floorMod(ChronoUnit.DAYS.between(cycleStart, date), (on + off).toLong()) < on
            Kind.WEEKS -> Math.floorMod(Math.floorDiv(ChronoUnit.DAYS.between(cycleStart, date), 7L), (on + off).toLong()) < on
            Kind.WEEKDAYS -> date.dayOfWeek in weekdays
        }

        /** Sensible numbers: at least one day (or week) on and none below zero off, and a weekday picked */
        val isValid get() = when (kind) {
            Kind.DAILY -> true
            Kind.DAYS, Kind.WEEKS -> on in 1..MAX_CYCLE && off in 0..MAX_CYCLE
            Kind.WEEKDAYS -> weekdays.isNotEmpty()
        }
    }

    /** The day it was first started, if it has been */
    val firstStart: LocalDate? get() = changes.firstOrNull()?.from

    /** The change in force on [date]: the last one from that day or before */
    fun changeOn(date: LocalDate): Change? = changes.lastOrNull { it.from <= date }

    /** Being taken on [date] by its plan: started and not stopped */
    fun isActive(date: LocalDate): Boolean = changeOn(date)?.let { !it.stopped } == true

    /** What the schedule says for [date], whatever's been marked */
    fun isScheduled(date: LocalDate): Boolean = changeOn(date)?.let { !it.stopped && it.schedule.isOn(date, it.cycleStart) } == true

    /** Taken on [date]: a day marked by hand, else the schedule; never before it starts or while it's stopped */
    fun isTaken(date: LocalDate): Boolean = isActive(date) && (fixes[date] ?: isScheduled(date))

    /** The schedule had it on [date], but it was marked as missed */
    fun isMissed(date: LocalDate): Boolean = isScheduled(date) && fixes[date] == false

    /** How much was taken on [date], or null when it wasn't */
    fun amountOn(date: LocalDate): Float? = if (isTaken(date)) changeOn(date)?.amount else null

    /** Whether it's stopped as of [date] (it was started before then) */
    fun isStopped(date: LocalDate): Boolean = changeOn(date)?.stopped == true

    /**
     * Where [date] falls in a weeks on/off cycle: the week number and how many weeks that stretch has,
     * and whether it's an on stretch. Null for other schedules or when it isn't active.
     */
    fun weekOf(date: LocalDate): Triple<Int, Int, Boolean>? {
        val change = changeOn(date)?.takeIf { !it.stopped && it.schedule.kind == Kind.WEEKS } ?: return null
        val week = Math.floorMod(Math.floorDiv(ChronoUnit.DAYS.between(change.cycleStart, date), 7L), (change.schedule.on + change.schedule.off).toLong()).toInt()
        return if (week < change.schedule.on) Triple(week + 1, change.schedule.on, true)
        else Triple(week - change.schedule.on + 1, change.schedule.off, false)
    }

    companion object {
        const val MAX_CYCLE = 365
    }
}

/** Changing supplements: each returns the supplement as it is afterwards, leaving the one given alone. */
object Supplements {

    /**
     * Takes [amount] on [schedule] from [from]. Only the amount changing keeps the cycle where it was;
     * a new schedule (or starting again) counts it from [from]. A change already on [from] is replaced,
     * and nothing changes when it's the same as what's already taken then.
     */
    fun change(s: Supplement, from: LocalDate, amount: Float, schedule: Supplement.Schedule): Supplement {
        val before = s.changes.lastOrNull { it.from < from }
        val now = s.changeOn(from)
        if (now != null && !now.stopped && now.amount == amount && now.schedule == schedule) return s
        val keepCycle = before != null && !before.stopped && before.schedule == schedule
        val change = Supplement.Change(from, amount, schedule, if (keepCycle) before!!.cycleStart else from)
        return s.copy(changes = withChange(s, change))
    }

    /**
     * Stops it from [from], keeping the amount, schedule and cycle it was on; a change already made that
     * day is kept inside the stop, so [undoStop] brings it back.
     */
    fun stop(s: Supplement, from: LocalDate): Supplement {
        val now = s.changeOn(from) ?: return s
        if (now.stopped) return s
        return s.copy(changes = withChange(s, now.copy(from = from, stopped = true)))
    }

    /**
     * Takes back a stop made on [from], if there is one: back to the change it was made on top of that
     * day, or, when it was a stop alone, gone.
     */
    fun undoStop(s: Supplement, from: LocalDate): Supplement {
        val stop = s.changes.firstOrNull { it.from == from && it.stopped } ?: return s
        val resumed = stop.copy(stopped = false)
        val before = s.changes.lastOrNull { it.from < from }
        val alone = before != null && !before.stopped && before.amount == resumed.amount &&
            before.schedule == resumed.schedule && before.cycleStart == resumed.cycleStart
        return s.copy(changes = if (alone) s.changes - stop else withChange(s, resumed))
    }

    /**
     * Marks [date] the other way from how it shows, taken or not. A mark that only says what the schedule
     * already does is dropped. Days before it starts or while it's stopped can't be marked.
     */
    fun flip(s: Supplement, date: LocalDate): Supplement {
        if (!s.isActive(date)) return s
        val taken = !s.isTaken(date)
        return s.copy(fixes = if (taken == s.isScheduled(date)) s.fixes - date else s.fixes + (date to taken))
    }

    /** The changes with [change] in its place by date, replacing one on the same day */
    private fun withChange(s: Supplement, change: Supplement.Change): List<Supplement.Change> =
        (s.changes.filterNot { it.from == change.from } + change).sortedBy { it.from }

    /** How many of [list] are taken on [date] */
    fun takenCount(list: List<Supplement>, date: LocalDate): Int = list.count { it.isTaken(date) }

    /** Whether the check-in asks about supplements for [date]: when any are being taken then */
    fun asked(list: List<Supplement>, date: LocalDate): Boolean = list.any { it.isActive(date) }

    /** The days from its first start to [today] it was taken, and those it wasn't */
    fun takenAndNot(s: Supplement, today: LocalDate): Pair<List<LocalDate>, List<LocalDate>> {
        val start = s.firstStart ?: return emptyList<LocalDate>() to emptyList()
        if (start > today) return emptyList<LocalDate>() to emptyList()
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        return days.partition { s.isTaken(it) }
    }

    /**
     * The days to set against each other for a change: [BEFORE_DAYS] before it, and from it to the day
     * before the next change (or [today]).
     */
    fun beforeAndSince(s: Supplement, change: Supplement.Change, today: LocalDate): Pair<List<LocalDate>, List<LocalDate>> {
        val next = s.changes.firstOrNull { it.from > change.from }?.from
        val end = minOf(next?.minusDays(1) ?: today, today)
        val before = (BEFORE_DAYS downTo 1).map { change.from.minusDays(it.toLong()) }
        val since = generateSequence(change.from) { it.plusDays(1) }.takeWhile { it <= end }.toList()
        return before to since
    }

    /** The amount as written: no trailing ".0" */
    fun amountText(amount: Float): String =
        if (amount == Math.round(amount).toFloat() && kotlin.math.abs(amount) < 1e9) Math.round(amount).toString()
        else java.math.BigDecimal(amount.toString()).stripTrailingZeros().toPlainString()

    const val BEFORE_DAYS = 14
}
