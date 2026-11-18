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
    enum class Lock { APP_LIST, APP_CHANGES, HIDDEN_APPS, LAUNCHER, PROTECT, SYSTEM_SETTINGS }

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

        /** The next time a window starts after [time], within a week; an all-day one starts at midnight */
        fun nextStart(time: LocalDateTime): LocalDateTime? = (0L..7L).asSequence()
            .map { time.toLocalDate().plusDays(it) }
            .filter { on(it.dayOfWeek) }
            .map { it.atStartOfDay().plusMinutes(if (start == end) 0L else start.toLong()) }
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

    /**
     * Adds [ms] to lockdown: to the timer, or past the end of the schedule (or stage) keeping it on now,
     * so a tap always pushes the end later.
     */
    fun addTime(context: Context, ms: Long, prefs: Prefs = Prefs(context)) {
        val untilNow = activeUntil(context, prefs)?.let { it - System.currentTimeMillis() } ?: 0L
        lockForTotal(context, maxOf(timedMsLeft(context, prefs), untilNow) + ms, prefs)
    }

    /** When the timed lockdown ends, on the wall clock now, or null */
    fun timedEnd(context: Context, prefs: Prefs = Prefs(context)): Long? =
        timedMsLeft(context, prefs).takeIf { it > 0 }?.let { System.currentTimeMillis() + it }

    fun schedules(prefs: Prefs) = parseSchedules(prefs.lockdownSchedules)

    private fun now(): LocalDateTime = LocalDateTime.now()

    fun scheduleActive(prefs: Prefs, time: LocalDateTime = now()) = schedules(prefs).any { it.covers(time) }

    fun isActive(context: Context, prefs: Prefs = Prefs(context)) = why(context, prefs) != null

    /** Why lockdown is on, the first that applies, or null when it's off */
    enum class Why { MANUAL, TIMER, SCHEDULE, PLACE, STAGE }

    fun why(context: Context, prefs: Prefs = Prefs(context)): Why? = when {
        prefs.lockdownModeOn -> Why.MANUAL
        timedActive(context, prefs) -> Why.TIMER
        scheduleActive(prefs) -> Why.SCHEDULE
        placeActive(context, prefs) -> Why.PLACE
        Limits.stageToday(context, prefs) >= Limits.STAGES -> Why.STAGE
        else -> null
    }

    /** Whether you're at one of lockdown's places, as far as place tracking knows */
    fun placeActive(context: Context, prefs: Prefs = Prefs(context)): Boolean {
        val places = prefs.lockdownPlaces
        if (places.isEmpty()) return false
        return runCatching { Social.currentPlace(context) }.getOrNull() in places
    }

    fun isLocked(context: Context, lock: Lock, prefs: Prefs = Prefs(context)) = enabled(prefs, lock) && isActive(context, prefs)

    fun enabled(prefs: Prefs, lock: Lock) = when (lock) {
        Lock.APP_LIST -> prefs.lockdownAppDrawer
        Lock.APP_CHANGES -> prefs.lockdownHoldApp
        Lock.HIDDEN_APPS -> prefs.lockdownHiddenApps
        Lock.LAUNCHER -> prefs.lockdownChangeLauncher
        Lock.PROTECT -> prefs.lockdownProtect
        Lock.SYSTEM_SETTINGS -> prefs.lockdownSystemSettings
    }

    fun setEnabled(prefs: Prefs, lock: Lock, on: Boolean) = when (lock) {
        Lock.APP_LIST -> prefs.lockdownAppDrawer = on
        Lock.APP_CHANGES -> prefs.lockdownHoldApp = on
        Lock.HIDDEN_APPS -> prefs.lockdownHiddenApps = on
        Lock.LAUNCHER -> prefs.lockdownChangeLauncher = on
        Lock.PROTECT -> prefs.lockdownProtect = on
        Lock.SYSTEM_SETTINGS -> prefs.lockdownSystemSettings = on
    }

    /** Apps never blocked: JLauncher, and the phone app, so calls can still be made and answered */
    /**
     * Any app but JLauncher and the phone app. System screens that aren't apps on a list (the
     * emergency dialer, the in-call screen, permission dialogs, the share sheet, recents) are never
     * blocked: a fixed list of them, and any system package without a launcher icon. Apps in another
     * profile (work, Private Space) and apps not installed yet count as apps.
     */
    fun canBlock(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName || packageName == defaultDialer(context) || packageName in NEVER_BLOCKED) return false
        return !systemScreen.getOrPut(packageName) { isSystemScreen(context, packageName) ?: return true }
    }

    /** Whether [packageName] is a system package with no launcher icon; null when that can't be told (not installed here) */
    private fun isSystemScreen(context: Context, packageName: String): Boolean? = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        system && context.packageManager.getLaunchIntentForPackage(packageName) == null
    }.getOrNull()

    /** System packages don't change, so the answer is kept; others are looked at each time */
    private val systemScreen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val NEVER_BLOCKED = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom", "com.android.dialer",
        "com.google.android.dialer", "com.samsung.android.incallui", "com.android.incallui", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.android.emergency", "com.google.android.apps.safetyhub", "com.android.intentresolver",
    )

    /** What a screen time stage does to an app: none, a pause (stage 1, blocked apps), or a block (stage 2 for blocked apps, stage 3 for all but essentials) */
    fun stageBlocks(stage: Int, packageName: String, blocked: Set<String>, essentials: Set<String>) =
        (stage >= Limits.STAGES && packageName !in essentials) || (stage >= 2 && packageName in blocked)

    fun stagePauses(stage: Int, packageName: String, blocked: Set<String>) = stage >= 1 && packageName in blocked

    private fun defaultDialer(context: Context) = runCatching {
        (context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage
    }.getOrNull()

    /** Why an app or site can't be used now */
    enum class Reason { BLOCKED, NOT_ALLOWED, TIME_LIMIT, OPENS_LIMIT, STAGE }

    /** An app or site that can't be used now: why, and until when on the wall clock (null: until lockdown ends, with no set end) */
    data class Block(val reason: Reason, val until: Long?)

    private fun midnight() = midnightAfter(java.time.LocalDateTime.now())

    /** The next midnight after [now], when limits and stages reset */
    fun midnightAfter(now: java.time.LocalDateTime, zone: ZoneId = ZoneId.systemDefault()) =
        now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Whether [packageName] can't be used now, and why: over a daily limit, a screen time stage, or
     * lockdown's blocked apps (or apps not allowed). [opening] counts the open about to happen.
     */
    fun blockFor(context: Context, packageName: String, opening: Boolean, prefs: Prefs = Prefs(context)): Block? {
        if (!canBlock(context, packageName)) return null
        val rule = Limits.rule(prefs, packageName)
        if (!rule.isEmpty) {
            when (Limits.over(rule, Limits.usedToday(context, packageName), Limits.opensToday(context, packageName), opening)) {
                Limits.Over.TIME -> return Block(Reason.TIME_LIMIT, midnight())
                Limits.Over.OPENS -> return Block(Reason.OPENS_LIMIT, midnight())
                null -> {}
            }
        }
        val stage = Limits.stageToday(context, prefs)
        if (stageBlocks(stage, packageName, prefs.lockdownBlockedApps, prefs.lockdownEssentials)) return Block(Reason.STAGE, midnight())
        if (!isActive(context, prefs)) return null
        return when {
            prefs.lockdownAppMode == ALLOW -> if (packageName in prefs.lockdownAllowedApps || packageName in prefs.lockdownEssentials) null
                else Block(Reason.NOT_ALLOWED, activeUntil(context, prefs))
            packageName in prefs.lockdownBlockedApps -> Block(Reason.BLOCKED, activeUntil(context, prefs))
            else -> null
        }
    }

    /** Whether [packageName] is left out of the app list and home screen: with the allowlist, while lockdown is on */
    fun hides(context: Context, packageName: String, prefs: Prefs = Prefs(context)): Boolean =
        prefs.lockdownAppMode == ALLOW && packageName !in prefs.lockdownAllowedApps && packageName !in prefs.lockdownEssentials &&
            canBlock(context, packageName) && isActive(context, prefs)

    const val BLOCK = 0
    const val ALLOW = 1

    /** The sites that go with [apps] */
    fun sitesOf(apps: Collection<String>) = apps.flatMap { Limits.APP_SITES[it].orEmpty() }.toSet()

    /** Whether the site at [host] can't be opened now, and why */
    fun blockForSite(context: Context, host: String, prefs: Prefs = Prefs(context)): Block? {
        fun has(sites: Collection<String>) = sites.any { Limits.matches(host, it) }
        // Apps over a limit or a stage take their sites with them, lockdown or not
        Limits.APP_SITES.filterValues { has(it) }.keys.forEach { pkg ->
            blockFor(context, pkg, opening = false, prefs)?.takeIf { it.reason != Reason.BLOCKED && it.reason != Reason.NOT_ALLOWED }?.let { return it }
        }
        if (!isActive(context, prefs)) return null
        if (has(prefs.lockdownSites) || has(sitesOf(prefs.lockdownBlockedApps))) return Block(Reason.BLOCKED, activeUntil(context, prefs))
        return null
    }

    // --- Blocked attempts, by day ---

    fun blockedDays(prefs: Prefs): Map<java.time.LocalDate, Int> = runCatching {
        val json = org.json.JSONObject(prefs.lockdownBlockedDays)
        json.keys().asSequence().mapNotNull { key -> runCatching { java.time.LocalDate.parse(key) to json.getInt(key) }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    fun blockedToday(prefs: Prefs) = blockedDays(prefs)[java.time.LocalDate.now()] ?: 0

    /** Counts one more blocked attempt today, keeping a year of days */
    fun countBlocked(context: Context, prefs: Prefs = Prefs(context)) {
        val today = java.time.LocalDate.now()
        val days = blockedDays(prefs).filterKeys { it > today.minusDays(400) }.toMutableMap()
        days[today] = (days[today] ?: 0) + 1
        prefs.lockdownBlockedDays = org.json.JSONObject().apply { days.forEach { (date, n) -> put(date.toString(), n) } }.toString()
        LockdownStats.update(context, days)
    }

    /** Days in a row, up to yesterday, that didn't reach stage 3; null when it isn't set */
    fun streak(context: Context, prefs: Prefs = Prefs(context)): Int? {
        val last = Limits.stages(prefs).getOrNull(Limits.STAGES - 1)?.takeIf { it > 0 } ?: return null
        val data = app.olauncher.data.MoodStore(context).load()
        var date = java.time.LocalDate.now().minusDays(1)
        var days = 0
        while (days < 365) {
            val used = data.days[date.toString()]?.totalUsageMs ?: break
            if (used >= last * 60_000L) break
            days++
            date = date.minusDays(1)
        }
        return days
    }

    /**
     * When lockdown, on now, turns off by itself, on the wall clock; null when it's switched on or has
     * no end. A timed lockdown, schedules and the last screen time stage that follow on from each
     * other count as one; at a place, there's no end to give.
     */
    fun activeUntil(context: Context, prefs: Prefs = Prefs(context)): Long? {
        if (prefs.lockdownModeOn || placeActive(context, prefs)) return null
        val ends = listOfNotNull(timedEnd(context, prefs),
            midnight().takeIf { Limits.stageToday(context, prefs) >= Limits.STAGES })
        val until = ends.maxOrNull()?.let { localTime(it) }
        val end = coveredUntil(schedules(prefs), until, now()) ?: return null
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

    /** Ends the timed lockdown now; schedules, places and stages carry on */
    fun endTimed(prefs: Prefs) {
        prefs.lockdownUntil = 0L
        prefs.lockdownUntilElapsed = 0L
        prefs.lockdownUntilBoot = -1
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
