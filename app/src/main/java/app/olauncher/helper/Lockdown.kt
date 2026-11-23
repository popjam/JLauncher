package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.olauncher.data.Prefs
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Lockdown: limits that are hard to undo, on the phone and (when paired) the Mac, each with its own
 * lockdown or both matched to whichever is greater. A device is in lockdown while it's switched on,
 * while a timed lockdown runs, during one of its schedules, after screen time passes a set amount,
 * and on the phone at one of its places or the last screen time stage.
 *
 * Everything that has it on is a window of time, and they don't add up: one inside another changes
 * nothing, and windows that overlap or follow on make one long lockdown. Adding time always adds to
 * the end of that whole run. A break lifts what lockdown blocks for a few minutes without ending it.
 *
 * While it's on, the chosen [Lock]s apply, blocked apps can't be opened, and anything that loosens
 * a limit needs the chosen [Challenge]. A timed lockdown can't be loosened at all until it ends.
 *
 * Times that matter (the end of a timed lockdown, the start of a wait to unlock) are kept on both
 * the wall clock and the clock since the phone started, which can't be changed. The second is
 * used until the phone restarts, so changing the time doesn't end them early.
 */
object Lockdown {

    /** What lockdown can lock, besides blocked apps */
    enum class Lock { APP_LIST, APP_CHANGES, HIDDEN_APPS, LAUNCHER, PROTECT, SYSTEM_SETTINGS }

    /** The devices lockdown runs on, as bits so a choice can be one, the other or both */
    enum class Device(val bit: Int) { PHONE(1), MAC(2) }

    const val BOTH = 3

    fun covers(devices: Int, device: Device) = devices and device.bit != 0

    /** After waiting to unlock, how long loosening stays open */
    const val READY_MS = 10 * 60_000L

    /** After passing the challenge, how long loosening stays open without asking again */
    private const val GRACE_MS = 5 * 60_000L

    // --- Schedules ---

    /**
     * Lockdown on [days] (bit 0 Monday … bit 6 Sunday) from [start] to [end], in minutes after
     * midnight, on [devices]. An end before the start runs past midnight into the next day; the same
     * start and end is the whole day.
     */
    data class Schedule(val days: Int, val start: Int, val end: Int, val devices: Int = BOTH) {

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

        /** Its windows that start on the days from [from] to [to] */
        fun windows(from: LocalDate, to: LocalDate): List<Window> = generateSequence(from.minusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filter { on(it.dayOfWeek) }
            .map { day ->
                val s = day.atStartOfDay().plusMinutes(if (start == end) 0L else start.toLong())
                val e = when {
                    start == end -> day.plusDays(1).atStartOfDay()
                    start < end -> day.atStartOfDay().plusMinutes(end.toLong())
                    else -> day.plusDays(1).atStartOfDay().plusMinutes(end.toLong())
                }
                Window(s, e, devices)
            }.toList()
    }

    const val WEEKDAYS = 0b0011111
    const val WEEKENDS = 0b1100000
    const val EVERY_DAY = 0b1111111

    /** "days:start:end" (on both devices) or "days:start:end:devices" for each, separated by ";". Anything unreadable is skipped. */
    fun parseSchedules(text: String): List<Schedule> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':').map { it.toIntOrNull() ?: return@mapNotNull null }
        if (bits.size != 3 && bits.size != 4) return@mapNotNull null
        val (days, start, end) = bits
        val devices = bits.getOrElse(3) { BOTH }
        if (days !in 1..EVERY_DAY || start !in 0 until 1440 || end !in 0 until 1440 || devices !in 1..BOTH) null
        else Schedule(days, start, end, devices)
    }

    fun formatSchedules(schedules: List<Schedule>) =
        schedules.joinToString(";") { "${it.days}:${it.start}:${it.end}" + if (it.devices == BOTH) "" else ":${it.devices}" }

    // --- Windows: lockdowns with a start and an end ---

    data class Window(val start: LocalDateTime, val end: LocalDateTime, val devices: Int = BOTH) {
        fun covers(time: LocalDateTime) = !time.isBefore(start) && time.isBefore(end)
    }

    /** "start:end:devices" in wall clock milliseconds, separated by ";" */
    fun parseWindows(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Window> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':').map { it.toLongOrNull() ?: return@mapNotNull null }
        if (bits.size != 3 || bits[1] <= bits[0]) return@mapNotNull null
        Window(localTime(bits[0], zone), localTime(bits[1], zone), bits[2].toInt())
    }

    fun formatWindows(windows: List<Window>, zone: ZoneId = ZoneId.systemDefault()) =
        windows.joinToString(";") { "${epoch(it.start, zone)}:${epoch(it.end, zone)}:${it.devices}" }

    /**
     * Everything that puts one device in lockdown: on with no end ([manual]), a timer ending at
     * [timedEnd], [schedules] and other [windows]. Two plans together ([plus]) are on whenever either is.
     */
    data class Plan(
        val manual: Boolean = false,
        val timedEnd: LocalDateTime? = null,
        val schedules: List<Schedule> = emptyList(),
        val windows: List<Window> = emptyList(),
    ) {
        fun on(time: LocalDateTime) = manual || timedEnd?.isAfter(time) == true || schedules.any { it.covers(time) } || windows.any { it.covers(time) }

        /** When it ends, if it's on and has an end: the end of the whole run of windows that follow on from now */
        fun until(time: LocalDateTime): LocalDateTime? = if (manual) null else coveredUntil(schedules, timedEnd, time, windows)

        operator fun plus(other: Plan) = Plan(manual || other.manual, listOfNotNull(timedEnd, other.timedEnd).maxOrNull(),
            schedules + other.schedules, windows + other.windows)
    }

    // --- Screen time that turns lockdown on ---

    /** Whose screen time a [Trigger] counts */
    enum class Source { TOTAL, PHONE, MAC, TV }

    /**
     * Once [source]'s screen time today reaches [minutes], lockdown on [devices] until the next [until]
     * (minutes after midnight: 420 is 7:00 the next morning, if it's evening).
     */
    data class Trigger(val source: Source, val minutes: Int, val until: Int, val devices: Int = BOTH) {
        val key get() = "${source.name}:$minutes:$until:$devices"

        /** The window it starts when it goes off at [time] */
        fun windowFrom(time: LocalDateTime): Window {
            var end = time.toLocalDate().atStartOfDay().plusMinutes(until.toLong())
            while (!end.isAfter(time)) end = end.plusDays(1)
            return Window(time, end, devices)
        }
    }

    fun parseTriggers(text: String): List<Trigger> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':')
        if (bits.size != 4) return@mapNotNull null
        val source = runCatching { Source.valueOf(bits[0]) }.getOrNull() ?: return@mapNotNull null
        val minutes = bits[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val until = bits[2].toIntOrNull()?.takeIf { it in 0 until 1440 } ?: return@mapNotNull null
        val devices = bits[3].toIntOrNull()?.takeIf { it in 1..BOTH } ?: return@mapNotNull null
        Trigger(source, minutes, until, devices)
    }

    fun formatTriggers(triggers: List<Trigger>) = triggers.joinToString(";") { it.key }

    /**
     * The triggers that go off now, given each source's screen time today ([used], ms) and the ones
     * that already went off today ([fired], by key). Each goes off once a day.
     */
    fun dueTriggers(triggers: List<Trigger>, used: Map<Source, Long>, fired: Set<String>): List<Trigger> =
        triggers.filter { it.key !in fired && (used[it.source] ?: 0L) >= it.minutes * 60_000L }

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

    // --- The devices ---

    /** Whether the Mac is paired, so it has a lockdown of its own */
    fun macPaired(context: Context) = LockdownSync.isOn(context)

    /** The devices there are: the phone, and the Mac when it's paired */
    fun devices(context: Context): Int = if (macPaired(context)) BOTH else Device.PHONE.bit

    /** [wanted] cut down to the devices there are */
    fun present(context: Context, wanted: Int): Int = (wanted and devices(context)).takeIf { it != 0 } ?: Device.PHONE.bit

    // --- The timer ---

    /** How long the phone's timed lockdown has left, or 0 */
    fun timedMsLeft(context: Context, prefs: Prefs = Prefs(context)): Long {
        val until = prefs.lockdownUntil
        if (until <= 0L) return 0L
        return msUntil(until, prefs.lockdownUntilElapsed, prefs.lockdownUntilBoot,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(context)).coerceAtLeast(0L)
    }

    /** How long [device]'s timed lockdown has left, or 0 */
    fun timedMsLeft(context: Context, prefs: Prefs, device: Device): Long = when (device) {
        Device.PHONE -> timedMsLeft(context, prefs)
        Device.MAC -> (prefs.lockdownMacUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun timedActive(context: Context, prefs: Prefs = Prefs(context)) = timedMsLeft(context, prefs) > 0L

    fun timedActive(context: Context, prefs: Prefs, device: Device) = timedMsLeft(context, prefs, device) > 0L

    /** Locks [devices] (the phone only if not given) for [total] from now. Never shortens a timed lockdown already running. */
    fun lockForTotal(context: Context, total: Long, prefs: Prefs = Prefs(context), devices: Int = Device.PHONE.bit) {
        if (covers(devices, Device.PHONE) && total > timedMsLeft(context, prefs)) {
            prefs.lockdownUntil = System.currentTimeMillis() + total
            prefs.lockdownUntilElapsed = SystemClock.elapsedRealtime() + total
            prefs.lockdownUntilBoot = boot(context)
        }
        if (covers(devices, Device.MAC) && total > timedMsLeft(context, prefs, Device.MAC)) {
            prefs.lockdownMacUntil = System.currentTimeMillis() + total
        }
    }

    /** Turns [devices] on until turned off */
    fun turnOn(prefs: Prefs, devices: Int) {
        if (covers(devices, Device.PHONE)) prefs.lockdownModeOn = true
        if (covers(devices, Device.MAC)) prefs.lockdownMacModeOn = true
    }

    /**
     * Adds [ms] to lockdown on [devices]: to the timer, or past the end of whatever keeps it on now
     * (schedules, screen time, a stage), so a tap always pushes the end later.
     */
    fun addTime(context: Context, ms: Long, prefs: Prefs = Prefs(context), devices: Int = Device.PHONE.bit) {
        Device.values().filter { covers(devices, it) }.forEach { device ->
            val untilNow = activeUntil(context, prefs, device)?.let { it - System.currentTimeMillis() } ?: 0L
            lockForTotal(context, maxOf(timedMsLeft(context, prefs, device), untilNow) + ms, prefs, device.bit)
        }
    }

    /** The devices the home lock and Start act on: the chosen ones, or both while they're matched */
    fun startDevices(context: Context, prefs: Prefs = Prefs(context)) =
        if (prefs.lockdownMatchDevices) devices(context) else present(context, prefs.lockdownStartDevices)

    /** When the phone's timed lockdown ends, on the wall clock now, or null */
    fun timedEnd(context: Context, prefs: Prefs = Prefs(context)): Long? =
        timedMsLeft(context, prefs).takeIf { it > 0 }?.let { System.currentTimeMillis() + it }

    fun timedEnd(context: Context, prefs: Prefs, device: Device): Long? =
        timedMsLeft(context, prefs, device).takeIf { it > 0 }?.let { System.currentTimeMillis() + it }

    fun schedules(prefs: Prefs) = parseSchedules(prefs.lockdownSchedules)

    fun triggers(prefs: Prefs) = parseTriggers(prefs.lockdownTriggers)

    /** The lockdowns screen time has started that haven't ended */
    fun triggerWindows(prefs: Prefs): List<Window> {
        val now = now()
        return parseWindows(prefs.lockdownTriggerBlocks).filter { it.end.isAfter(now) }
    }

    private fun now(): LocalDateTime = LocalDateTime.now()

    fun scheduleActive(prefs: Prefs, time: LocalDateTime = now(), device: Device = Device.PHONE) =
        schedules(prefs).any { covers(it.devices, device) && it.covers(time) }

    /** [device]'s own plan, without the other device's */
    private fun ownPlan(context: Context, prefs: Prefs, device: Device): Plan = when (device) {
        Device.PHONE -> Plan(
            manual = prefs.lockdownModeOn || placeActive(context, prefs),
            timedEnd = timedEnd(context, prefs)?.let { localTime(it) },
            schedules = schedules(prefs).filter { covers(it.devices, device) },
            windows = triggerWindows(prefs).filter { covers(it.devices, device) } +
                listOfNotNull(Window(LocalDate.now().atStartOfDay(), localTime(midnight())).takeIf { Limits.stageToday(context, prefs) >= Limits.STAGES }),
        )
        Device.MAC -> if (!macPaired(context)) Plan() else Plan(
            manual = prefs.lockdownMacModeOn,
            timedEnd = timedEnd(context, prefs, device)?.let { localTime(it) },
            schedules = schedules(prefs).filter { covers(it.devices, device) },
            windows = triggerWindows(prefs).filter { covers(it.devices, device) },
        )
    }

    /** Everything that puts [device] in lockdown: its own, and the other device's while they're matched */
    fun plan(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE): Plan {
        val own = ownPlan(context, prefs, device)
        if (!prefs.lockdownMatchDevices || !macPaired(context)) return own
        return own + ownPlan(context, prefs, if (device == Device.PHONE) Device.MAC else Device.PHONE)
    }

    /** Whether [device] is in lockdown, on a break or not */
    fun isOn(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE) = plan(context, prefs, device).on(now())

    /** Whether lockdown blocks things on the phone now: it's on, and not on a break */
    fun isActive(context: Context, prefs: Prefs = Prefs(context)) = isOn(context, prefs) && !onBreak(prefs, Device.PHONE)

    /** Why the phone is in lockdown, the first that applies, or null when it's off */
    enum class Why { MANUAL, TIMER, SCHEDULE, PLACE, STAGE, SCREEN_TIME, OTHER_DEVICE }

    fun why(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE): Why? {
        val time = now()
        val own = ownPlan(context, prefs, device)
        return when {
            device == Device.PHONE && prefs.lockdownModeOn -> Why.MANUAL
            device == Device.MAC && prefs.lockdownMacModeOn -> Why.MANUAL
            own.timedEnd?.isAfter(time) == true -> Why.TIMER
            own.schedules.any { it.covers(time) } -> Why.SCHEDULE
            device == Device.PHONE && placeActive(context, prefs) -> Why.PLACE
            device == Device.PHONE && Limits.stageToday(context, prefs) >= Limits.STAGES -> Why.STAGE
            triggerWindows(prefs).any { covers(it.devices, device) && it.covers(time) } -> Why.SCREEN_TIME
            plan(context, prefs, device).on(time) -> Why.OTHER_DEVICE
            else -> null
        }
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

    private fun midnight() = midnightAfter(LocalDateTime.now())

    /** The next midnight after [now], when limits and stages reset */
    fun midnightAfter(now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()) =
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
        // The last stage is a lockdown, which a break lifts
        val stage = Limits.stageToday(context, prefs).let { if (onBreak(prefs, Device.PHONE)) it.coerceAtMost(Limits.STAGES - 1) else it }
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

    fun blockedDays(prefs: Prefs): Map<LocalDate, Int> = runCatching {
        val json = org.json.JSONObject(prefs.lockdownBlockedDays)
        json.keys().asSequence().mapNotNull { key -> runCatching { LocalDate.parse(key) to json.getInt(key) }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    fun blockedToday(prefs: Prefs) = blockedDays(prefs)[LocalDate.now()] ?: 0

    /** Counts one more blocked attempt today, keeping a year of days */
    fun countBlocked(context: Context, prefs: Prefs = Prefs(context)) {
        val today = LocalDate.now()
        val days = blockedDays(prefs).filterKeys { it > today.minusDays(400) }.toMutableMap()
        days[today] = (days[today] ?: 0) + 1
        prefs.lockdownBlockedDays = org.json.JSONObject().apply { days.forEach { (date, n) -> put(date.toString(), n) } }.toString()
    }

    /** Days in a row, up to yesterday, that didn't reach stage 3; null when it isn't set */
    fun streak(context: Context, prefs: Prefs = Prefs(context)): Int? {
        val last = Limits.stages(prefs).getOrNull(Limits.STAGES - 1)?.takeIf { it > 0 } ?: return null
        val data = app.olauncher.data.MoodStore(context).load()
        var date = LocalDate.now().minusDays(1)
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
     * When [device]'s lockdown, on now, turns off by itself, on the wall clock; null when it's
     * switched on or has no end (at a place). Everything that follows on counts as one.
     */
    fun activeUntil(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE): Long? =
        plan(context, prefs, device).until(now())?.let { epoch(it, ZoneId.systemDefault()) }

    /** Where a run of [schedules], [windows] and a timed lockdown ending at [timedEnd] covering [time] ends, or null if none covers it */
    fun coveredUntil(schedules: List<Schedule>, timedEnd: LocalDateTime?, time: LocalDateTime, windows: List<Window> = emptyList()): LocalDateTime? {
        var end: LocalDateTime? = null
        var at = time
        for (i in 0 until 64) {
            val next = (schedules.filter { it.covers(at) }.map { it.endAfter(at) } + windows.filter { it.covers(at) }.map { it.end } +
                listOfNotNull(timedEnd?.takeIf { it.isAfter(at) })).maxOrNull()
            if (next == null || !next.isAfter(at)) break
            end = next
            at = next
        }
        return end
    }

    /** The next scheduled start on [device], on the wall clock, when lockdown is off */
    fun nextScheduled(prefs: Prefs, device: Device = Device.PHONE): Long? {
        val time = now()
        return schedules(prefs).filter { covers(it.devices, device) || prefs.lockdownMatchDevices }.mapNotNull { it.nextStart(time) }.minOrNull()
            ?.let { epoch(it, ZoneId.systemDefault()) }
    }

    fun localTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)

    fun epoch(time: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()) = time.atZone(zone).toInstant().toEpochMilli()

    // --- Screen time triggers ---

    /** Today's screen time from each source, as far as it's known */
    fun screenTimeToday(context: Context): Map<Source, Long> {
        val today = LocalDate.now()
        val phone = Limits.totalToday(context)
        val mac = (DeviceTime.days(context, DeviceTime.Device.MAC)[today]?.minutes ?: 0) * 60_000L
        val tv = (DeviceTime.days(context, DeviceTime.Device.TV)[today]?.minutes ?: 0) * 60_000L
        return mapOf(Source.PHONE to phone, Source.MAC to mac, Source.TV to tv, Source.TOTAL to phone + mac + tv)
    }

    /** Starts the lockdowns of triggers whose screen time has been reached today; returns whether any did */
    fun checkTriggers(context: Context, prefs: Prefs = Prefs(context)): Boolean {
        val triggers = triggers(prefs)
        if (triggers.isEmpty()) return false
        val today = LocalDate.now().toString()
        val firedJson = runCatching { org.json.JSONObject(prefs.lockdownTriggerFired) }.getOrDefault(org.json.JSONObject())
        val fired = firedJson.keys().asSequence().filter { firedJson.optString(it) == today }.toSet()
        val due = dueTriggers(triggers, screenTimeToday(context), fired)
        if (due.isEmpty()) return false
        val now = now()
        prefs.lockdownTriggerBlocks = formatWindows(triggerWindows(prefs) + due.map { it.windowFrom(now) })
        val next = org.json.JSONObject()
        firedJson.keys().forEach { if (firedJson.optString(it) == today) next.put(it, today) }
        due.forEach { next.put(it.key, today) }
        prefs.lockdownTriggerFired = next.toString()
        return true
    }

    // --- Breaks ---

    fun breaks(prefs: Prefs) = Breaks.Config.decode(prefs.lockdownBreaks)

    fun breakLog(prefs: Prefs) = Breaks.decodeLog(prefs.lockdownBreakLog)

    /** When [device]'s break ends, or 0: with shared breaks, a break on either */
    fun breakUntil(prefs: Prefs, device: Device): Long {
        val shared = breaks(prefs).shared
        return when {
            shared -> maxOf(prefs.lockdownBreakUntil, prefs.lockdownMacBreakUntil)
            device == Device.PHONE -> prefs.lockdownBreakUntil
            else -> prefs.lockdownMacBreakUntil
        }
    }

    fun onBreak(prefs: Prefs, device: Device) = breakUntil(prefs, device) > System.currentTimeMillis()

    /** Whether [device] can take a break now */
    fun canBreak(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE): Breaks.Can =
        Breaks.check(breaks(prefs), breakLog(prefs), System.currentTimeMillis(), device.name.lowercase(),
            isOn(context, prefs, device), breakUntil(prefs, device))

    /** Starts a break on [device] (both, when shared), with the id the Mac gave it if it came from there */
    fun startBreak(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE, id: String = java.util.UUID.randomUUID().toString(),
                   at: Long = System.currentTimeMillis()) {
        val config = breaks(prefs)
        if (!config.on) return
        val end = Breaks.newEnd(config, at, breakUntil(prefs, device))
        when (device) {
            Device.PHONE -> prefs.lockdownBreakUntil = end
            Device.MAC -> prefs.lockdownMacBreakUntil = end
        }
        prefs.lockdownBreakLog = Breaks.encodeLog(Breaks.trim(Breaks.started(breakLog(prefs), id, at, end, device.name.lowercase()), at))
        // Lockdown ends a break's length later
        if (config.makeUp) addTime(context, config.minutes * 60_000L, prefs, if (config.shared) devices(context) else device.bit)
    }

    /** Ends [device]'s break now (both, when shared) */
    fun endBreak(prefs: Prefs, device: Device = Device.PHONE) {
        val now = System.currentTimeMillis()
        val shared = breaks(prefs).shared
        if (shared || device == Device.PHONE) prefs.lockdownBreakUntil = 0L
        if (shared || device == Device.MAC) prefs.lockdownMacBreakUntil = 0L
        val devices = if (shared) setOf(Breaks.PHONE, Breaks.MAC) else setOf(device.name.lowercase())
        prefs.lockdownBreakLog = Breaks.encodeLog(Breaks.ended(breakLog(prefs), now, devices))
    }

    // --- Loosening ---

    fun challenge(prefs: Prefs) = Challenge.decode(prefs.lockdownChallenge) ?: Challenge.DEFAULT

    fun waitState(context: Context, prefs: Prefs = Prefs(context)): WaitState {
        val started = prefs.lockdownWaitRequestedAt
        if (started <= 0L) return WaitState.None
        val waitMs = challenge(prefs).amount * 60_000L
        val since = -msUntil(started, prefs.lockdownWaitRequestedElapsed, prefs.lockdownWaitBoot,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(context))
        return waitState(since, waitMs)
    }

    fun startWait(context: Context, prefs: Prefs = Prefs(context)) {
        prefs.lockdownWaitRequestedAt = System.currentTimeMillis()
        prefs.lockdownWaitRequestedElapsed = SystemClock.elapsedRealtime()
        prefs.lockdownWaitBoot = boot(context)
    }

    /** Ends the timed lockdown on [devices] now; schedules, places and stages carry on */
    fun endTimed(prefs: Prefs, devices: Int = Device.PHONE.bit) {
        if (covers(devices, Device.PHONE)) {
            prefs.lockdownUntil = 0L
            prefs.lockdownUntilElapsed = 0L
            prefs.lockdownUntilBoot = -1
        }
        if (covers(devices, Device.MAC)) prefs.lockdownMacUntil = 0L
    }

    /** Turns off [devices]' lockdown that's on until turned off */
    fun turnOff(prefs: Prefs, devices: Int) {
        if (covers(devices, Device.PHONE)) prefs.lockdownModeOn = false
        if (covers(devices, Device.MAC)) prefs.lockdownMacModeOn = false
    }

    fun cancelWait(prefs: Prefs) {
        prefs.lockdownWaitRequestedAt = 0L
    }

    private var graceUntil = 0L

    /** The challenge was just passed: loosening needs nothing more for a few minutes, while JLauncher stays open */
    fun startGrace() {
        graceUntil = SystemClock.elapsedRealtime() + GRACE_MS
    }

    fun endGrace() {
        graceUntil = 0L
    }

    /** Whether loosening needs nothing right now: lockdown is off on every device (a break doesn't count), or its challenge was passed */
    fun isOpen(context: Context, prefs: Prefs = Prefs(context)): Boolean {
        val on = Device.values().any { isOn(context, prefs, it) }
        if (!on) return true
        if (Device.values().any { timedActive(context, prefs, it) }) return false
        val challenge = challenge(prefs)
        return when (challenge.type) {
            Challenge.Type.NONE -> true
            Challenge.Type.NEVER -> false
            Challenge.Type.PASSWORD -> !prefs.isLockdownPasswordSet || SystemClock.elapsedRealtime() < graceUntil
            Challenge.Type.WAIT -> waitState(context, prefs) is WaitState.Ready
            else -> SystemClock.elapsedRealtime() < graceUntil
        }
    }
}
