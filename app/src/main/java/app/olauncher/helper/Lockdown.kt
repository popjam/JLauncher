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
 * Lockdown: limits that are hard to undo. There's one lockdown, on the phone and on every other device
 * that's switched on (the Mac, the TV). It's on while it's switched on, while a timed lockdown runs,
 * during a schedule, at one of its places, and after the day's total screen time passes a set amount.
 *
 * Everything that has it on is a window of time, and they don't add up: one inside another changes
 * nothing, and windows that overlap or follow on make one long lockdown. Adding time always adds to
 * the end of that whole run. A break lifts what lockdown blocks on every device for a few minutes
 * without ending it.
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

    /** The devices lockdown runs on, as bits, as the Mac app and the TV's PC read them */
    enum class Device(val bit: Int) { PHONE(1), MAC(2), TV(4) }

    /** Every device */
    const val ALL = 7

    fun covers(devices: Int, device: Device) = devices and device.bit != 0

    /** After waiting to unlock, how long loosening stays open */
    const val READY_MS = 10 * 60_000L

    /** After passing the challenge, how long loosening stays open without asking again */
    private const val GRACE_MS = 5 * 60_000L

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
                Window(s, e)
            }.toList()
    }

    const val WEEKDAYS = 0b0011111
    const val WEEKENDS = 0b1100000
    const val EVERY_DAY = 0b1111111

    /**
     * "days:start:end" for each, separated by ";". Schedules from when each could be for some devices
     * ("days:start:end:devices") are read as for every device. Anything unreadable is skipped, and the
     * same schedule twice is kept once.
     */
    fun parseSchedules(text: String): List<Schedule> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':').map { it.toIntOrNull() ?: return@mapNotNull null }
        if (bits.size != 3 && bits.size != 4) return@mapNotNull null
        val (days, start, end) = bits
        val devices = bits.getOrElse(3) { ALL }
        if (days !in 1..EVERY_DAY || start !in 0 until 1440 || end !in 0 until 1440 || devices !in 1..ALL) null
        else Schedule(days, start, end)
    }.distinct()

    fun formatSchedules(schedules: List<Schedule>) = schedules.joinToString(";") { "${it.days}:${it.start}:${it.end}" }

    /** Whether [schedules] cover the [minute]th minute of the week (0 is Monday 0:00), as [Schedule.covers] would */
    fun coversMinuteOfWeek(schedules: List<Schedule>, minute: Int): Boolean {
        // 1 January 2024 was a Monday
        val time = LocalDate.of(2024, 1, 1).atStartOfDay().plusMinutes(minute.toLong())
        return schedules.any { it.covers(time) }
    }

    /**
     * Whether some time covered by [old] isn't covered by [new]: changing schedules that way loosens
     * (lockdown, or no breaks, for less of the week)
     */
    fun coverageLost(old: List<Schedule>, new: List<Schedule>): Boolean =
        old.isNotEmpty() && old != new && (0 until 7 * 1440).any { coversMinuteOfWeek(old, it) && !coversMinuteOfWeek(new, it) }

    /**
     * Whether changing a Lock after trigger from [old] to [new] loosens: more screen time before it
     * goes off, or a different end, which comes sooner for some of the times it could go off
     */
    fun triggerLoosens(old: Trigger, new: Trigger) = new.minutes > old.minutes || new.until != old.until

    // --- Windows: lockdowns with a start and an end ---

    data class Window(val start: LocalDateTime, val end: LocalDateTime) {
        fun covers(time: LocalDateTime) = !time.isBefore(start) && time.isBefore(end)
    }

    /** "start:end" in wall clock milliseconds, separated by ";"; an old third part (the devices) is ignored */
    fun parseWindows(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Window> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':').map { it.toLongOrNull() ?: return@mapNotNull null }
        if (bits.size !in 2..3 || bits[1] <= bits[0]) return@mapNotNull null
        Window(localTime(bits[0], zone), localTime(bits[1], zone))
    }

    fun formatWindows(windows: List<Window>, zone: ZoneId = ZoneId.systemDefault()) =
        windows.joinToString(";") { "${epoch(it.start, zone)}:${epoch(it.end, zone)}" }

    /**
     * Everything that puts lockdown on: on until turned off ([manual]), on with no end that turning off
     * doesn't end ([open]: at one of lockdown's places), a timer ending at [timedEnd], [schedules] and
     * other [windows]. Two plans together ([plus]) are on whenever either is.
     */
    data class Plan(
        val manual: Boolean = false,
        val timedEnd: LocalDateTime? = null,
        val schedules: List<Schedule> = emptyList(),
        val windows: List<Window> = emptyList(),
        val open: Boolean = false,
    ) {
        fun on(time: LocalDateTime) = manual || open || timedEnd?.isAfter(time) == true || schedules.any { it.covers(time) } || windows.any { it.covers(time) }

        /** When it ends, if it's on and has an end: the end of the whole run of windows that follow on from now */
        fun until(time: LocalDateTime): LocalDateTime? = if (manual || open) null else coveredUntil(schedules, timedEnd, time, windows)

        /**
         * When the run of schedules and windows covering [time] started, as far as they tell (a schedule's
         * window that started over a week ago counts from a week ago), or null when none covers it
         */
        fun coveredSince(time: LocalDateTime): LocalDateTime? {
            val all = schedules.flatMap { it.windows(time.toLocalDate().minusDays(7), time.toLocalDate()) } + windows
            var start: LocalDateTime? = all.filter { it.covers(time) }.minOfOrNull { it.start } ?: return null
            for (i in 0 until 64) {
                val earlier = all.filter { it.start.isBefore(start) && !it.end.isBefore(start) }.minOfOrNull { it.start } ?: break
                start = earlier
            }
            return start
        }

        operator fun plus(other: Plan) = Plan(manual || other.manual, listOfNotNull(timedEnd, other.timedEnd).maxOrNull(),
            schedules + other.schedules, windows + other.windows, open || other.open)
    }

    /** What a device gets from the phone: the one lockdown while it's switched on ([deviceOn]), nothing while it's off */
    fun planFor(shared: Plan, deviceOn: Boolean) = if (deviceOn) shared else Plan()

    // --- Screen time that turns lockdown on ---

    /** Whose screen time, for showing today's: the total is what turns lockdown on */
    enum class Source { TOTAL, PHONE, MAC, TV }

    /**
     * Once the day's total screen time across every device reaches [minutes], lockdown until the next
     * [until] (minutes after midnight: 420 is 7:00 the next morning, if it's evening).
     */
    data class Trigger(val minutes: Int, val until: Int) {
        /** How it's written, and how the days it went off are remembered: as it was when each named whose screen time and which devices */
        val key get() = "TOTAL:$minutes:$until:$ALL"

        /** The window it starts when it goes off at [time] */
        fun windowFrom(time: LocalDateTime): Window {
            var end = time.toLocalDate().atStartOfDay().plusMinutes(until.toLong())
            while (!end.isAfter(time)) end = end.plusDays(1)
            return Window(time, end)
        }
    }

    /**
     * "SOURCE:minutes:until:devices" for each, separated by ";". Whose screen time and which devices are
     * from before there was one lockdown: every trigger now counts the total and locks every device.
     * Anything unreadable is skipped, and triggers that come out the same are kept once.
     */
    fun parseTriggers(text: String): List<Trigger> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':')
        if (bits.size != 4) return@mapNotNull null
        runCatching { Source.valueOf(bits[0]) }.getOrNull() ?: return@mapNotNull null
        val minutes = bits[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val until = bits[2].toIntOrNull()?.takeIf { it in 0 until 1440 } ?: return@mapNotNull null
        bits[3].toIntOrNull()?.takeIf { it in 1..ALL } ?: return@mapNotNull null
        Trigger(minutes, until)
    }.distinct()

    fun formatTriggers(triggers: List<Trigger>) = triggers.joinToString(";") { it.key }

    /**
     * The triggers that go off now, given the day's total screen time ([usedMs]) and the ones that
     * already went off today ([fired], by key, in any format written before). Each goes off once a day.
     */
    fun dueTriggers(triggers: List<Trigger>, usedMs: Long, fired: Set<String>): List<Trigger> {
        val firedKeys = fired.flatMap { key -> parseTriggers(key).map { it.key } }.toSet() + fired
        return triggers.filter { it.key !in firedKeys && usedMs >= it.minutes * 60_000L }
    }

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

    /** Whether the Mac is switched on, so it takes the lockdown */
    fun macPaired(context: Context) = LockdownSync.deviceOn(context, Device.MAC)

    /** Whether the TV is switched on (its PC reads the lockdown through the same database), so it takes the lockdown */
    fun tvPaired(context: Context) = LockdownSync.deviceOn(context, Device.TV)

    /** Whether [device] is switched on: the phone always is */
    fun paired(context: Context, device: Device) = when (device) {
        Device.PHONE -> true
        Device.MAC -> macPaired(context)
        Device.TV -> tvPaired(context)
    }

    /** The switched-on devices, as bits */
    fun devices(context: Context): Int = Device.values().filter { paired(context, it) }.sumOf { it.bit }

    /** The switched-on devices, in order */
    fun deviceList(context: Context): List<Device> = Device.values().filter { paired(context, it) }

    /** Whether there's more than the phone */
    fun several(context: Context) = deviceList(context).size > 1

    /**
     * Switches [device] (the Mac or the TV) on or off. A switched-off device gets no lockdown from the
     * phone, so switching one off loosens: callers go through the unlock first.
     */
    fun setDeviceOn(context: Context, device: Device, on: Boolean) = LockdownSync.setDeviceOn(context, device, on)

    /** When [device] last checked in (wall clock), or null when that isn't known */
    fun lastSeen(context: Context, device: Device): Long? = LockdownSync.lastSeen(context, device)

    /** Whether lockdown is on until turned off */
    fun modeOn(prefs: Prefs) = prefs.lockdownModeOn

    // --- The Mac's and the TV's own lockdowns, from before there was one lockdown ---

    /** What the Mac or the TV had of its own before there was one lockdown, and whether it was switched on ([on]) */
    data class OldDevice(val on: Boolean, val modeOn: Boolean, val until: Long, val breakUntil: Long)

    /** What the one lockdown takes from the old ones: on until turned off, a timer's end and a break's end (wall clock, 0 for none) */
    data class Folded(val modeOn: Boolean, val until: Long, val breakUntil: Long)

    /** Only a switched-on device's lockdown is carried over; a switched-off one's is dropped */
    fun foldOld(old: List<OldDevice>, now: Long): Folded {
        val on = old.filter { it.on }
        return Folded(on.any { it.modeOn }, on.maxOfOrNull { it.until }?.takeIf { it > now } ?: 0L,
            on.maxOfOrNull { it.breakUntil }?.takeIf { it > now } ?: 0L)
    }

    /**
     * Once: the Mac's and the TV's own lockdowns (on until turned off, a timer, a break) become part of
     * the one lockdown if that device is switched on, then are cleared and never read again
     */
    fun foldOldDevices(context: Context, prefs: Prefs) {
        if (oldFolded) return
        oldFolded = true
        if (prefs.lockdownDevicesFolded) return
        val now = System.currentTimeMillis()
        val folded = foldOld(listOf(
            OldDevice(LockdownSync.deviceOn(context, Device.MAC), prefs.lockdownMacModeOn, prefs.lockdownMacUntil, prefs.lockdownMacBreakUntil),
            OldDevice(LockdownSync.deviceOn(context, Device.TV), prefs.lockdownTvModeOn, prefs.lockdownTvUntil, prefs.lockdownTvBreakUntil),
        ), now)
        if (folded.modeOn) prefs.lockdownModeOn = true
        if (folded.until > 0L) lockForTotal(context, folded.until - now, prefs)
        if (folded.breakUntil > prefs.lockdownBreakUntil) prefs.lockdownBreakUntil = folded.breakUntil
        prefs.lockdownMacModeOn = false
        prefs.lockdownTvModeOn = false
        prefs.lockdownMacUntil = 0L
        prefs.lockdownTvUntil = 0L
        prefs.lockdownMacBreakUntil = 0L
        prefs.lockdownTvBreakUntil = 0L
        prefs.lockdownDevicesFolded = true
    }

    @Volatile private var oldFolded = false

    // --- The timer ---

    /** How long the timed lockdown has left, or 0 */
    fun timedMsLeft(context: Context, prefs: Prefs = Prefs(context)): Long {
        val until = prefs.lockdownUntil
        if (until <= 0L) return 0L
        return msUntil(until, prefs.lockdownUntilElapsed, prefs.lockdownUntilBoot,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(context)).coerceAtLeast(0L)
    }

    fun timedActive(context: Context, prefs: Prefs = Prefs(context)) = timedMsLeft(context, prefs) > 0L

    /** Locks for [total] from now. Never shortens a timed lockdown already running. */
    fun lockForTotal(context: Context, total: Long, prefs: Prefs = Prefs(context)) {
        if (total <= timedMsLeft(context, prefs)) return
        starting(context, prefs) {
            prefs.lockdownUntil = System.currentTimeMillis() + total
            prefs.lockdownUntilElapsed = SystemClock.elapsedRealtime() + total
            prefs.lockdownUntilBoot = boot(context)
        }
    }

    /** Turns lockdown on until turned off */
    fun turnOn(context: Context, prefs: Prefs = Prefs(context)) = starting(context, prefs) { prefs.lockdownModeOn = true }

    /** Makes a [change] that may turn lockdown on, so a lockdown it starts counts from now for automatic breaks */
    private inline fun starting(context: Context, prefs: Prefs, change: () -> Unit) {
        // Looking first clears a start kept from a lockdown that has since ended
        isOn(context, prefs)
        change()
        isOn(context, prefs)
    }

    /**
     * Adds [ms] to lockdown: to the timer, or past the end of whatever keeps it on now (schedules,
     * screen time), so a tap always pushes the end later.
     */
    fun addTime(context: Context, ms: Long, prefs: Prefs = Prefs(context)) {
        val untilNow = activeUntil(context, prefs)?.let { it - System.currentTimeMillis() } ?: 0L
        lockForTotal(context, maxOf(timedMsLeft(context, prefs), untilNow) + ms, prefs)
    }

    /** When the timed lockdown ends, on the wall clock now, or null */
    fun timedEnd(context: Context, prefs: Prefs = Prefs(context)): Long? =
        timedMsLeft(context, prefs).takeIf { it > 0 }?.let { System.currentTimeMillis() + it }

    fun schedules(prefs: Prefs) = parseSchedules(prefs.lockdownSchedules)

    fun triggers(prefs: Prefs) = parseTriggers(prefs.lockdownTriggers)

    /** The lockdowns screen time has started that haven't ended */
    fun triggerWindows(prefs: Prefs): List<Window> {
        val now = now()
        return parseWindows(prefs.lockdownTriggerBlocks).filter { it.end.isAfter(now) }
    }

    private fun now(): LocalDateTime = LocalDateTime.now()

    fun scheduleActive(prefs: Prefs, time: LocalDateTime = now()) = schedules(prefs).any { it.covers(time) }

    /** The one lockdown every switched-on device takes */
    fun sharedPlan(context: Context, prefs: Prefs = Prefs(context)): Plan {
        foldOldDevices(context, prefs)
        return Plan(
            manual = modeOn(prefs),
            timedEnd = timedEnd(context, prefs)?.let { localTime(it) },
            schedules = schedules(prefs),
            windows = triggerWindows(prefs),
            // Turning lockdown off doesn't end being at a place, so it isn't sent as on until turned off
            open = placeActive(context, prefs),
        )
    }

    /** What puts [device] in lockdown: the one lockdown while it's switched on */
    fun plan(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE): Plan =
        planFor(sharedPlan(context, prefs), paired(context, device))

    /**
     * Whether [device] is in lockdown, on a break or not. Looking at the phone's also keeps when the
     * lockdown on now started, for automatic breaks, and clears that while lockdown is off.
     */
    fun isOn(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE): Boolean {
        val time = now()
        val shared = sharedPlan(context, prefs)
        val on = planFor(shared, paired(context, device)).on(time)
        if (device == Device.PHONE) {
            val since = runStart(on, prefs.lockdownOnSince, System.currentTimeMillis(), shared.coveredSince(time)?.let { epoch(it) })
            if (since != prefs.lockdownOnSince) prefs.lockdownOnSince = since
        }
        return on
    }

    /** Whether lockdown blocks things on the phone now: it's on, and not on a break */
    fun isActive(context: Context, prefs: Prefs = Prefs(context)) = isOn(context, prefs) && !onBreak(prefs)

    /** Why lockdown is on, the first that applies, or null when it's off */
    enum class Why { MANUAL, TIMER, SCHEDULE, PLACE, SCREEN_TIME }

    fun why(context: Context, prefs: Prefs = Prefs(context)): Why? {
        val time = now()
        val plan = sharedPlan(context, prefs)
        return when {
            modeOn(prefs) -> Why.MANUAL
            plan.timedEnd?.isAfter(time) == true -> Why.TIMER
            plan.schedules.any { it.covers(time) } -> Why.SCHEDULE
            placeActive(context, prefs) -> Why.PLACE
            plan.windows.any { it.covers(time) } -> Why.SCREEN_TIME
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

    private fun defaultDialer(context: Context) = runCatching {
        (context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage
    }.getOrNull()

    /** Why an app or site can't be used now */
    enum class Reason { BLOCKED, NOT_ALLOWED, TIME_LIMIT, OPENS_LIMIT }

    /**
     * An app or site that can't be used now: why, and until when on the wall clock (null: until
     * lockdown ends, with no set end). [gentle]: a gentle limit, whose screen offers one more minute or
     * ignoring it for the day for [pkg], instead of blocking.
     */
    data class Block(val reason: Reason, val until: Long?, val gentle: Boolean = false, val pkg: String? = null)

    private fun midnight() = midnightAfter(LocalDateTime.now())

    /** The next midnight after [now], when limits reset */
    fun midnightAfter(now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()) =
        now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * What a reached limit does: a strict one blocks; a gentle one shows its notice unless it's been
     * [allowed] (one more minute, or ignored for the day); null when the limit isn't reached ([over]).
     */
    fun limitBlock(over: Limits.Over?, gentle: Boolean, allowed: Boolean, until: Long, pkg: String): Block? {
        val reason = when (over) {
            Limits.Over.TIME -> Reason.TIME_LIMIT
            Limits.Over.OPENS -> Reason.OPENS_LIMIT
            null -> return null
        }
        if (gentle && allowed) return null
        return Block(reason, until, gentle, pkg)
    }

    /**
     * Whether [packageName] can't be used now, and why: over a daily limit, or lockdown's blocked apps
     * (or apps not allowed). [opening] counts the open about to happen. A gentle limit's notice comes
     * after lockdown, so a blocked app stays blocked.
     */
    fun blockFor(context: Context, packageName: String, opening: Boolean, prefs: Prefs = Prefs(context)): Block? {
        if (!canBlock(context, packageName)) return null
        val rule = Limits.rule(prefs, packageName)
        val limit = if (rule.isEmpty) null else limitBlock(
            Limits.over(rule, Limits.usedToday(context, packageName), Limits.opensToday(context, packageName), opening),
            rule.gentle, rule.gentle && Limits.gentleAllowed(context, packageName), midnight(), packageName)
        if (limit != null && !limit.gentle) return limit
        val lockdown = if (!isActive(context, prefs)) null else when {
            prefs.lockdownAppMode == ALLOW -> if (packageName in prefs.lockdownAllowedApps || packageName in prefs.lockdownEssentials) null
                else Block(Reason.NOT_ALLOWED, activeUntil(context, prefs))
            packageName in prefs.lockdownBlockedApps -> Block(Reason.BLOCKED, activeUntil(context, prefs))
            else -> null
        }
        return lockdown ?: limit
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
        // Apps over a limit take their sites with them, lockdown or not
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

    /** The next scheduled start, on the wall clock, when lockdown is off */
    fun nextScheduled(prefs: Prefs): Long? {
        val time = now()
        return schedules(prefs).mapNotNull { it.nextStart(time) }.minOrNull()?.let { epoch(it, ZoneId.systemDefault()) }
    }

    fun localTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)

    fun epoch(time: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()) = time.atZone(zone).toInstant().toEpochMilli()

    // --- Screen time triggers ---

    /** Today's screen time from each device and in total, as far as it's known */
    fun screenTimeToday(context: Context): Map<Source, Long> {
        val today = LocalDate.now()
        val phone = Limits.totalToday(context)
        val mac = (DeviceTime.days(context, DeviceTime.Device.MAC)[today]?.minutes ?: 0) * 60_000L
        val tv = (DeviceTime.days(context, DeviceTime.Device.TV)[today]?.minutes ?: 0) * 60_000L
        return mapOf(Source.PHONE to phone, Source.MAC to mac, Source.TV to tv, Source.TOTAL to phone + mac + tv)
    }

    /** Today's total screen time across every device */
    fun totalScreenTimeToday(context: Context) = screenTimeToday(context)[Source.TOTAL] ?: 0L

    /** Starts the lockdowns of triggers whose screen time has been reached today; returns whether any did */
    fun checkTriggers(context: Context, prefs: Prefs = Prefs(context)): Boolean {
        val triggers = triggers(prefs)
        if (triggers.isEmpty()) return false
        val today = LocalDate.now().toString()
        val firedJson = runCatching { org.json.JSONObject(prefs.lockdownTriggerFired) }.getOrDefault(org.json.JSONObject())
        val fired = firedJson.keys().asSequence().filter { firedJson.optString(it) == today }.toSet()
        val due = dueTriggers(triggers, totalScreenTimeToday(context), fired)
        if (due.isEmpty()) return false
        val now = now()
        starting(context, prefs) { prefs.lockdownTriggerBlocks = formatWindows(triggerWindows(prefs) + due.map { it.windowFrom(now) }) }
        val next = org.json.JSONObject()
        firedJson.keys().forEach { if (firedJson.optString(it) == today) next.put(it, today) }
        due.forEach { next.put(it.key, today) }
        prefs.lockdownTriggerFired = next.toString()
        return true
    }

    // --- Breaks: shared by every device ---

    fun breaks(prefs: Prefs) = Breaks.Config.decode(prefs.lockdownBreaks)

    fun breakLog(prefs: Prefs) = Breaks.decodeLog(prefs.lockdownBreakLog)

    /**
     * The automatic break running now, as start to end, unless it was ended early. Only while lockdown is
     * on: [isOn] keeps when it started and clears that whenever it finds lockdown off, so look first.
     */
    fun autoBreak(prefs: Prefs, now: Long = System.currentTimeMillis()): Pair<Long, Long>? =
        Breaks.autoWindow(breaks(prefs), prefs.lockdownOnSince, now)?.takeIf { it.second > prefs.lockdownAutoBreakSkip }

    /** The automatic break running now, if lockdown is on */
    fun autoBreak(context: Context, prefs: Prefs = Prefs(context)): Pair<Long, Long>? = if (isOn(context, prefs)) autoBreak(prefs) else null

    /** When the break running now ends, or 0: one taken on any device, or an automatic one (see [autoBreak]) */
    fun breakUntil(prefs: Prefs): Long = maxOf(prefs.lockdownBreakUntil, autoBreak(prefs)?.second ?: 0L)

    fun onBreak(prefs: Prefs) = breakUntil(prefs) > System.currentTimeMillis()

    /** The break time saved up, as last brought up to date (see [updatePool]) */
    fun pool(prefs: Prefs) = Breaks.Pool.decode(prefs.lockdownBreakPool)

    /**
     * Brings the pool up to now: ends a break from the pool when lockdown has turned off (what's left
     * goes back), counts one that has run out (making up its time), then adds what lockdown since the
     * last look has earned
     */
    fun updatePool(context: Context, prefs: Prefs = Prefs(context)): Breaks.Pool {
        val now = System.currentTimeMillis()
        val on = isOn(context, prefs)
        val until = prefs.lockdownBreakUntil
        val before = pool(prefs)
        if (until > now && !on) endBreak(context, prefs)
        else if (prefs.lockdownBreakStart > 0L && until <= now) {
            breakDone(context, prefs, if (until > 0L) until else now)
            // A break that ran out since the last look: lockdown from its end on earns, if it's still on
            if (on && until in (before.at + 1)..now) prefs.lockdownBreakPool = before.copy(at = until, earning = true).encode()
        }
        val pool = Breaks.accrue(breaks(prefs), pool(prefs), now, on && !onBreak(prefs), prefs.lockdownOnSince)
        prefs.lockdownBreakPool = pool.encode()
        return pool
    }

    /** A break from the pool is over at [end]: with making up on, the time it was used goes on the end of lockdown, if it's still on */
    private fun breakDone(context: Context, prefs: Prefs, end: Long) {
        val start = prefs.lockdownBreakStart
        prefs.lockdownBreakStart = 0L
        val used = end - start
        if (start > 0L && used > 0L && breaks(prefs).makeUp && isOn(context, prefs)) addTime(context, used, prefs)
    }

    /** Whether a break can be taken now */
    fun canBreak(context: Context, prefs: Prefs = Prefs(context)): Breaks.Can {
        val pool = updatePool(context, prefs)
        return Breaks.check(breaks(prefs), pool, System.currentTimeMillis(), isOn(context, prefs), breakUntil(prefs))
    }

    /**
     * Starts a break on every device, taken on [device], with the id the Mac gave it if it came from
     * there. It uses the whole pool, up to when lockdown ends, breaks' next time off, or midnight when the
     * pool empties then; what it can't use stays in the pool.
     */
    fun startBreak(context: Context, prefs: Prefs = Prefs(context), device: Device = Device.PHONE, id: String = java.util.UUID.randomUUID().toString()) {
        val config = breaks(prefs)
        if (!config.on) return
        val pool = updatePool(context, prefs)
        val now = System.currentTimeMillis()
        val length = Breaks.breakLength(config, pool.banked, now, activeUntil(context, prefs))
        if (length < 1_000L) return
        val end = now + length
        prefs.lockdownBreakUntil = end
        prefs.lockdownBreakStart = now
        prefs.lockdownBreakPool = pool.copy(banked = pool.banked - length, earning = false).encode()
        prefs.lockdownBreakLog = Breaks.encodeLog(Breaks.trim(Breaks.started(breakLog(prefs), id, now, end, device.name.lowercase()), now))
    }

    /** Ends the break now on every device, an automatic one too; what's left of one from the pool goes back in it */
    fun endBreak(context: Context, prefs: Prefs = Prefs(context)) {
        val now = System.currentTimeMillis()
        val until = prefs.lockdownBreakUntil
        if (until > now) {
            val pool = pool(prefs)
            prefs.lockdownBreakPool = pool.copy(banked = pool.banked + (until - now)).encode()
            breakDone(context, prefs, now)
        }
        prefs.lockdownBreakUntil = 0L
        autoBreak(prefs, now)?.let { prefs.lockdownAutoBreakSkip = it.second }
        prefs.lockdownBreakLog = Breaks.encodeLog(Breaks.ended(breakLog(prefs), now, Device.values().map { it.name.lowercase() }.toSet()))
    }

    /**
     * When the lockdown on now started, or 0 when it's off, given what was kept ([since]); one just found
     * on started when its schedules and windows say ([covered]), or now
     */
    fun runStart(on: Boolean, since: Long, now: Long, covered: Long? = null) = when {
        !on -> 0L
        since > 0L -> since
        covered != null && covered in 1..now -> covered
        else -> now
    }

    /** Keeps when the lockdown on now started, for automatic breaks (see [isOn]) */
    fun trackRun(context: Context, prefs: Prefs = Prefs(context)) {
        isOn(context, prefs)
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

    /** Ends the timed lockdown now; schedules, places and screen time carry on */
    fun endTimed(prefs: Prefs) {
        prefs.lockdownUntil = 0L
        prefs.lockdownUntilElapsed = 0L
        prefs.lockdownUntilBoot = -1
    }

    /** Turns off lockdown that's on until turned off */
    fun turnOff(prefs: Prefs) {
        prefs.lockdownModeOn = false
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

    /** Whether loosening needs nothing right now: lockdown is off (a break doesn't count), or its challenge was passed */
    fun isOpen(context: Context, prefs: Prefs = Prefs(context)): Boolean {
        if (!isOn(context, prefs)) return true
        if (timedActive(context, prefs)) return false
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
