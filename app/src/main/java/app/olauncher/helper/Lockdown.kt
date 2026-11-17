package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.olauncher.data.Prefs
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Lockdown: limits that are hard to undo. It's on while it's switched on, while a timed lockdown
 * runs, or during one of its schedules.
 *
 * While it's on, the chosen [Lock]s apply, blocked apps can't be opened, and anything that loosens
 * a limit needs the chosen [Unlock]. A timed lockdown can't be loosened at all until it ends.
 *
 * Times that matter (the end of a timed lockdown, the start of a wait to unlock) are kept on both
 * the wall clock and the clock since the phone started, which can't be changed. The second is
 * used until the phone restarts, so changing the time doesn't end them early.
 */
object Lockdown {

    /** What lockdown can lock, besides blocked apps */
    enum class Lock { APP_LIST, APP_CHANGES, HIDDEN_APPS, LAUNCHER, PROTECT }

    /** How lockdown is loosened while it's on. Stored as the ordinal. */
    enum class Unlock { NOTHING, PASSWORD, WAIT }

    /** After waiting to unlock, how long loosening stays open */
    const val READY_MS = 10 * 60_000L

    /** After the password, how long loosening stays open without asking again */
    private const val GRACE_MS = 5 * 60_000L

    /** The choices for how long a wait to unlock is */
    val WAIT_CHOICES = listOf(5, 15, 30, 60)

    // --- Schedules ---

    /**
     * Lockdown on [days] (bit 0 Monday … bit 6 Sunday) from [start] to [end], in minutes after
     * midnight. An end before the start runs past midnight into the next day; the same start and end
     * is the whole day.
     */
    data class Schedule(val days: Int, val start: Int, val end: Int) {

        fun on(day: DayOfWeek) = days and (1 shl (day.value - 1)) != 0

        fun covers(time: LocalDateTime): Boolean {
            val minute = time.hour * 60 + time.minute
            val today = on(time.dayOfWeek)
            return when {
                start == end -> today
                start < end -> today && minute >= start && minute < end
                // Overnight: the evening of a scheduled day, or the morning after one
                else -> (today && minute >= start) || (on(time.dayOfWeek.minus(1)) && minute < end)
            }
        }

        /** When the window [time] is in ends; only meaningful while it [covers] it */
        fun endAfter(time: LocalDateTime): LocalDateTime {
            val minute = time.hour * 60 + time.minute
            val day = time.toLocalDate()
            return when {
                start == end -> day.plusDays(1).atStartOfDay()
                start < end -> day.atStartOfDay().plusMinutes(end.toLong())
                minute >= start -> day.plusDays(1).atStartOfDay().plusMinutes(end.toLong())
                else -> day.atStartOfDay().plusMinutes(end.toLong())
            }
        }

        /** The next time a window starts after [time], within a week */
        fun nextStart(time: LocalDateTime): LocalDateTime? = (0L..7L).asSequence()
            .map { time.toLocalDate().plusDays(it) }
            .filter { on(it.dayOfWeek) }
            .map { it.atStartOfDay().plusMinutes(start.toLong()) }
            .firstOrNull { it.isAfter(time) }
    }

    const val WEEKDAYS = 0b0011111
    const val WEEKENDS = 0b1100000
    const val EVERY_DAY = 0b1111111

    /** "days:start:end" for each, separated by ";". Anything unreadable is skipped. */
    fun parseSchedules(text: String): List<Schedule> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':').map { it.toIntOrNull() ?: return@mapNotNull null }
        if (bits.size != 3) return@mapNotNull null
        val (days, start, end) = bits
        if (days !in 1..EVERY_DAY || start !in 0 until 1440 || end !in 0 until 1440) null else Schedule(days, start, end)
    }

    fun formatSchedules(schedules: List<Schedule>) = schedules.joinToString(";") { "${it.days}:${it.start}:${it.end}" }

    // --- Time that can't be changed ---

    /**
     * How long from now until a time kept as [wall] / [elapsed] in boot [boot] (negative once it's
     * passed): by the clock since the phone started while it hasn't restarted, otherwise by the wall
     * clock.
     */
    fun msUntil(wall: Long, elapsed: Long, boot: Int, wallNow: Long, elapsedNow: Long, bootNow: Int): Long =
        if (boot >= 0 && boot == bootNow) elapsed - elapsedNow else wall - wallNow

    sealed class WaitState {
        object None : WaitState()
        data class Waiting(val msLeft: Long) : WaitState()
        data class Ready(val msLeft: Long) : WaitState()
    }

    /** Where a wait of [waitMs] started [sinceStart] ago has got to; [sinceStart] is null with no wait */
    fun waitState(sinceStart: Long?, waitMs: Long): WaitState = when {
        sinceStart == null || sinceStart < 0 -> WaitState.None
        sinceStart < waitMs -> WaitState.Waiting(waitMs - sinceStart)
        sinceStart < waitMs + READY_MS -> WaitState.Ready(waitMs + READY_MS - sinceStart)
        else -> WaitState.None
    }

    private fun boot(context: Context) = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    // --- The state on the phone ---

    /** How long the timed lockdown has left, or 0 */
    fun timedMsLeft(context: Context, prefs: Prefs = Prefs(context)): Long {
        val until = prefs.lockdownUntil
        if (until <= 0L) return 0L
        return msUntil(until, prefs.lockdownUntilElapsed, prefs.lockdownUntilBoot,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(context)).coerceAtLeast(0L)
    }

    fun timedActive(context: Context, prefs: Prefs = Prefs(context)) = timedMsLeft(context, prefs) > 0L

    /** Locks for [total] from now. Never shortens the timed lockdown already running. */
    fun lockForTotal(context: Context, total: Long, prefs: Prefs = Prefs(context)) {
        if (total <= timedMsLeft(context, prefs)) return
        prefs.lockdownUntil = System.currentTimeMillis() + total
        prefs.lockdownUntilElapsed = SystemClock.elapsedRealtime() + total
        prefs.lockdownUntilBoot = boot(context)
    }

    /** When the timed lockdown ends, on the wall clock now, or null */
    fun timedEnd(context: Context, prefs: Prefs = Prefs(context)): Long? =
        timedMsLeft(context, prefs).takeIf { it > 0 }?.let { System.currentTimeMillis() + it }

    fun schedules(prefs: Prefs) = parseSchedules(prefs.lockdownSchedules)

    private fun now(): LocalDateTime = LocalDateTime.now()

    fun scheduleActive(prefs: Prefs, time: LocalDateTime = now()) = schedules(prefs).any { it.covers(time) }

    fun isActive(context: Context, prefs: Prefs = Prefs(context)) =
        prefs.lockdownModeOn || scheduleActive(prefs) || timedActive(context, prefs)

    fun isLocked(context: Context, lock: Lock, prefs: Prefs = Prefs(context)) = enabled(prefs, lock) && isActive(context, prefs)

    fun enabled(prefs: Prefs, lock: Lock) = when (lock) {
        Lock.APP_LIST -> prefs.lockdownAppDrawer
        Lock.APP_CHANGES -> prefs.lockdownHoldApp
        Lock.HIDDEN_APPS -> prefs.lockdownHiddenApps
        Lock.LAUNCHER -> prefs.lockdownChangeLauncher
        Lock.PROTECT -> prefs.lockdownProtect
    }

    fun setEnabled(prefs: Prefs, lock: Lock, on: Boolean) = when (lock) {
        Lock.APP_LIST -> prefs.lockdownAppDrawer = on
        Lock.APP_CHANGES -> prefs.lockdownHoldApp = on
        Lock.HIDDEN_APPS -> prefs.lockdownHiddenApps = on
        Lock.LAUNCHER -> prefs.lockdownChangeLauncher = on
        Lock.PROTECT -> prefs.lockdownProtect = on
    }

    /** Apps never blocked: JLauncher, and the phone app, so calls can still be made and answered */
    fun canBlock(context: Context, packageName: String) = packageName != context.packageName && packageName != defaultDialer(context)

    private fun defaultDialer(context: Context) = runCatching {
        (context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage
    }.getOrNull()

    fun isBlocked(context: Context, packageName: String, prefs: Prefs = Prefs(context)) =
        packageName in prefs.lockdownBlockedApps && isActive(context, prefs) && canBlock(context, packageName)

    /**
     * When lockdown, on now, turns off by itself, on the wall clock; null when it's switched on or has
     * no end. A timed lockdown and schedules that follow on from each other count as one.
     */
    fun activeUntil(context: Context, prefs: Prefs = Prefs(context)): Long? {
        if (prefs.lockdownModeOn) return null
        val timed = timedEnd(context, prefs)?.let { localTime(it) }
        val end = coveredUntil(schedules(prefs), timed, now()) ?: return null
        return end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Where a run of [schedules] and a timed lockdown ending at [timedEnd] covering [time] ends, or null if none covers it */
    fun coveredUntil(schedules: List<Schedule>, timedEnd: LocalDateTime?, time: LocalDateTime): LocalDateTime? {
        var end: LocalDateTime? = null
        var at = time
        for (i in 0 until 32) {
            val next = (schedules.filter { it.covers(at) }.map { it.endAfter(at) } + listOfNotNull(timedEnd?.takeIf { it.isAfter(at) })).maxOrNull()
            if (next == null || !next.isAfter(at)) break
            end = next
            at = next
        }
        return end
    }

    /** The next scheduled start, on the wall clock, when lockdown is off */
    fun nextScheduled(prefs: Prefs): Long? {
        val time = now()
        return schedules(prefs).mapNotNull { it.nextStart(time) }.minOrNull()
            ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    }

    fun localTime(epochMs: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())

    // --- Loosening ---

    fun unlock(prefs: Prefs) = Unlock.values().getOrElse(prefs.lockdownUnlock) { Unlock.NOTHING }

    fun waitState(context: Context, prefs: Prefs = Prefs(context)): WaitState {
        val started = prefs.lockdownWaitRequestedAt
        if (started <= 0L) return WaitState.None
        val waitMs = prefs.lockdownWaitMinutes * 60_000L
        val since = -msUntil(started, prefs.lockdownWaitRequestedElapsed, prefs.lockdownWaitBoot,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(context))
        return waitState(since, waitMs)
    }

    fun startWait(context: Context, prefs: Prefs = Prefs(context)) {
        prefs.lockdownWaitRequestedAt = System.currentTimeMillis()
        prefs.lockdownWaitRequestedElapsed = SystemClock.elapsedRealtime()
        prefs.lockdownWaitBoot = boot(context)
    }

    fun cancelWait(prefs: Prefs) {
        prefs.lockdownWaitRequestedAt = 0L
    }

    private var graceUntil = 0L

    /** The password was just given: loosening needs nothing more for a few minutes, while JLauncher stays open */
    fun startGrace() {
        graceUntil = SystemClock.elapsedRealtime() + GRACE_MS
    }

    fun endGrace() {
        graceUntil = 0L
    }

    /** Whether loosening needs nothing right now */
    fun isOpen(context: Context, prefs: Prefs = Prefs(context)): Boolean {
        if (!isActive(context, prefs)) return true
        if (timedActive(context, prefs)) return false
        return when (unlock(prefs)) {
            Unlock.NOTHING -> true
            Unlock.PASSWORD -> !prefs.isLockdownPasswordSet || SystemClock.elapsedRealtime() < graceUntil
            Unlock.WAIT -> waitState(context, prefs) is WaitState.Ready
        }
    }
}
