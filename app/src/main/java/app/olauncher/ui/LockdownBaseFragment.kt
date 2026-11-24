package app.olauncher.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.helper.BlockItems
import app.olauncher.helper.Breaks
import app.olauncher.helper.Challenge
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSync
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.Places
import app.olauncher.helper.Social
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import app.olauncher.helper.AppPause
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Lockdown's rows, for its pages ([LockdownFragment], [LockdownPageFragment]), its setup, and
 * Settings' search, which finds them too: the status and its actions, then a page of rows for each
 * of its sections ([SECTIONS]: Blocks, Turns on, Screen time, Breaks, Unlock, On screen, Devices).
 *
 * Anything that makes lockdown stricter is done straight away; anything that loosens it goes through
 * the unlock ([LockdownGate.loosen]) while lockdown is on. Things added since the page opened can be
 * taken off again without it.
 */
abstract class LockdownBaseFragment : SettingsBaseFragment() {

    companion object {
        const val BLOCKS = "blocks"
        const val TURNS_ON = "turnsOn"
        const val SCREEN_TIME = "screenTime"
        const val BREAKS = "breaks"
        const val UNLOCK = "unlock"
        const val ON_SCREEN = "onScreen"
        const val DEVICES = "devices"

        /** Lockdown's sections, each a page, in order */
        val SECTIONS = listOf(BLOCKS, TURNS_ON, SCREEN_TIME, BREAKS, UNLOCK, ON_SCREEN, DEVICES)
    }

    internal val gate by lazy { LockdownGate(this) { render() } }

    /** Things added since the page opened, so a mistake can be taken off again without the unlock */
    internal val addedHere = mutableSetOf<String>()

    internal val scheduleList = HoldList<Lockdown.Schedule>(onRemove = { removeSchedules(it) }, tapOpensMenu = true)
    internal val placeList = HoldList<Int>(onRemove = { removePlaces(it) }, tapOpensMenu = true)
    internal val triggerList = HoldList<Lockdown.Trigger>(onRemove = { removeTriggers(it) }, tapOpensMenu = true)
    internal val breakOffList = HoldList<Lockdown.Schedule>(onRemove = { removeBreakOff(it) }, tapOpensMenu = true)

    override val holdLists: List<HoldList<*>> get() = listOf(scheduleList, placeList, triggerList, breakOffList)

    /** Whether turning notifications down for good opens the system's page for them straight away */
    internal open val openSettingsWhenDenied = true

    internal val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.lockdownNotification = granted
        render()
        // Turned down for good: only the system settings can allow it now
        if (openSettingsWhenDenied && !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
        }
    }

    override fun afterRender() = LockdownSystem.sync(requireContext())

    /** One of Lockdown's lists ([LockdownPageFragment]), lighting up [highlight] if given */
    internal fun open(page: String, pkg: String? = null, highlight: String? = null) {
        runCatching {
            findNavController().navigate(R.id.lockdownPageFragment, bundleOf(LockdownPageFragment.PAGE to page, LockdownPageFragment.PKG to pkg, HIGHLIGHT to highlight))
        }
    }

    /** One of Lockdown's sections as a page, lighting up [highlight] if given */
    internal fun openSection(section: String, highlight: String? = null) {
        runCatching { findNavController().navigate(R.id.lockdownFragment, bundleOf(LockdownFragment.SECTION to section, HIGHLIGHT to highlight)) }
    }

    internal fun sectionTitle(section: String) = getString(when (section) {
        BLOCKS -> R.string.lockdown_blocks
        TURNS_ON -> R.string.lockdown_turns_on
        SCREEN_TIME -> R.string.lockdown_screen_time
        BREAKS -> R.string.lockdown_breaks
        UNLOCK -> R.string.lockdown_unlock
        ON_SCREEN -> R.string.lockdown_on_screen
        else -> R.string.lockdown_devices
    })

    private fun sectionAbout(section: String) = when (section) {
        BLOCKS -> R.string.info_lockdown_blocks_section
        TURNS_ON -> R.string.info_lockdown_turns_on
        SCREEN_TIME -> R.string.info_lockdown_screen_time_section
        BREAKS -> R.string.info_lockdown_breaks
        UNLOCK -> R.string.info_lockdown_unlock
        ON_SCREEN -> R.string.info_lockdown_on_screen
        else -> R.string.info_lockdown_devices
    }

    /** A section's rows */
    internal fun sectionRows(section: String): Rows = when (section) {
        BLOCKS -> blocksRows()
        TURNS_ON -> turnsOnRows()
        SCREEN_TIME -> screenTimeRows()
        BREAKS -> breaksRows()
        UNLOCK -> unlockRows()
        ON_SCREEN -> onScreenRows()
        else -> devicesRows()
    }

    /** A section in a few words, for its row on the Lockdown page */
    private fun sectionValue(section: String): String? {
        val context = requireContext()
        return when (section) {
            BLOCKS -> modeName() + " · " + blockedValue(BlockItems.effective(prefs.lockdownItems, LockdownSync.macInstalled(context), LockdownSync.tvInstalled(context)))
            TURNS_ON -> turnsOnValue()
            SCREEN_TIME -> Limits.appsWithRules(context, prefs).let { if (it == 0) null else resources.getQuantityString(R.plurals.lockdown_apps_count, it, it) }
            BREAKS -> breaksValue()
            UNLOCK -> unlockValue()
            ON_SCREEN -> onScreenValue()
            else -> LockdownGate.deviceName(context, Lockdown.deviceList(context).fold(0) { mask, device -> mask or device.bit })
        }
    }

    // --- The Lockdown page: now, the rules, and more ---

    /** Taps on the status, counting to 500 to show the password, when that way of getting it back is on */
    private var recoveryTaps = 0

    /** The Lockdown page: its status and what can be done now, its sections, then setup and stats */
    internal fun lockdownRows(): Rows {
        val context = requireContext()
        return Rows(statusRows() + listOf(SettingLabel(getString(R.string.lockdown_rules))) +
            SECTIONS.map { section ->
                row("section.$section", sectionTitle(section), sectionAbout(section), value = sectionValue(section), control = Control.Opens) { openSection(section) }
            } + listOf(
            SettingLabel(getString(R.string.lockdown_more)),
            row("setup", getString(R.string.lockdown_setup_again), R.string.info_lockdown_setup) { openSetup() },
            row("stats", getString(R.string.lockdown_stats), R.string.info_lockdown_stats, control = Control.Opens) {
                app.olauncher.helper.AutoStats.id(context, app.olauncher.helper.AutoStats.LOCKDOWN)?.let { openStat(it) }
                    ?: context.showToast(getString(R.string.no_data))
            },
        ))
    }

    /** Lockdown now, each device's when there's more than the phone, and what can be done: Start, or Add time, End early, Keep on, a break */
    private fun statusRows(): List<SettingItem?> {
        val context = requireContext()
        val on = Lockdown.isOn(context, prefs)
        val endable = Lockdown.modeOn(prefs) || Lockdown.timedActive(context, prefs)
        val breaks = Lockdown.breaks(prefs)
        val left = Lockdown.activeUntil(context, prefs)?.let { it - System.currentTimeMillis() }?.takeIf { it > 0 }
        return listOf(
            row("status", getString(R.string.lockdown_status), R.string.info_lockdown_status, value = LockdownGate.status(context),
                note = LockdownGate.whyText(context)?.takeIf { on }) { countRecoveryTap() },
        ) + (if (Lockdown.several(context)) Lockdown.deviceList(context).map { device ->
            row("device.${device.bit}", LockdownGate.deviceName(context, device.bit), R.string.info_lockdown_device_status,
                value = LockdownGate.deviceStatus(context, device), level = 1)
        } else emptyList()) + listOf(
            if (!on) row("start", getString(R.string.lockdown_start), R.string.info_lockdown_start) { askLockFor() } else null,
            if (on) row("addTime", getString(R.string.lockdown_add_time), R.string.info_lockdown_add_time,
                value = left?.let { getString(R.string.lockdown_time_left, Formats.duration(it)) }) { askLockFor() } else null,
            if (on && Lockdown.onBreak(prefs)) row("endBreak", getString(R.string.lockdown_end_break), R.string.info_lockdown_end_break,
                value = LockdownGate.deviceStatus(context, Lockdown.Device.PHONE)) { gate.endBreak() }
            else if (on && breaks.on) row("takeBreak", getString(R.string.lockdown_take_break), R.string.info_lockdown_take_break,
                value = getString(R.string.lockdown_time_left, LockdownGate.poolText(Lockdown.updatePool(context, prefs).banked))) { gate.takeBreak() }
            else null,
            if (on && endable) row("endEarly", getString(R.string.lockdown_end_early), R.string.info_lockdown_end_early) { gate.endEarly() } else null,
            if (on && !Lockdown.modeOn(prefs)) row("keepOn", getString(R.string.lockdown_keep_on_button), R.string.info_lockdown_keep_on) { toggleManual() } else null,
        )
    }

    private fun countRecoveryTap() {
        if (!prefs.passwordRecoveryEnabled || !prefs.recoveryTap500 || !prefs.isLockdownPasswordSet) return
        recoveryTaps++
        if (recoveryTaps >= 500) {
            recoveryTaps = 0
            gate.showPassword()
        } else if (recoveryTaps % 50 == 0) requireContext().showToast(getString(R.string.recovery_tap_progress, recoveryTaps))
    }

    internal fun openSetup() {
        runCatching { findNavController().navigate(R.id.lockdownSetupFragment) }
    }

    /** Turning it on while a timer, schedule, place or screen time has it on keeps it on past their end, so that's asked first */
    private fun toggleManual() {
        val context = requireContext()
        val until = Lockdown.activeUntil(context, prefs)
        AppDialog(context)
            .setTitle(until?.let { getString(R.string.lockdown_keep_on_after, LockdownGate.time(context, it)) } ?: getString(R.string.lockdown_keep_on))
            .setMessage(R.string.lockdown_keep_on_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lockdown_keep_on_button) { _, _ ->
                Lockdown.turnOn(requireContext(), prefs)
                render()
            }
            .show()
    }

    /** Starts a timed lockdown on every switched-on device, or adds to the one running. It can't be shortened. */
    internal fun askLockFor() {
        val context = requireContext()
        val running = Lockdown.isOn(context, prefs)
        val minutes = listOf(15, 30, 60, 120, 240, 480, 1440)
        val names = minutes.map { (if (running) "+" else "") + Formats.duration(it * 60_000L) } + getString(R.string.lockdown_until_a_time) +
            listOfNotNull(getString(R.string.lockdown_until_off).takeIf { !running })
        AppDialog(context)
            .setTitle(if (running) getString(R.string.lockdown_add_time) else getString(R.string.lockdown_start_title))
            .setItems(names.toTypedArray()) { _, which ->
                if (which < minutes.size) {
                    val ms = minutes[which] * 60_000L
                    // Added to the end of whatever keeps it on now, on each device
                    if (running) {
                        Lockdown.addTime(context, ms, prefs)
                        return@setItems render()
                    }
                    return@setItems confirmLockFor(ms)
                }
                if (which > minutes.size) {
                    Lockdown.turnOn(requireContext(), prefs)
                    return@setItems render()
                }
                val now = java.time.LocalTime.now()
                TimePickerDialog(context, { _, hour, minute ->
                    val at = LocalDate.now().atTime(hour, minute).let { if (it.isAfter(LocalDateTime.now())) it else it.plusDays(1) }
                    val total = java.time.Duration.between(LocalDateTime.now(), at).toMillis()
                    val left = Lockdown.activeUntil(context, prefs)?.let { it - System.currentTimeMillis() } ?: 0L
                    if (total <= left) context.showToast(getString(R.string.lockdown_cant_shorten)) else confirmLockFor(total)
                }, (now.hour + 1) % 24, 0, DateFormat.is24HourFormat(context)).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Asks before locking for [totalMs] from now, since it can't be undone */
    private fun confirmLockFor(totalMs: Long) {
        val context = requireContext()
        AppDialog(context)
            .setTitle(getString(R.string.lockdown_lock_until_ask, LockdownGate.time(context, System.currentTimeMillis() + totalMs)))
            .setMessage(R.string.lockdown_lock_until_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lockdown_lock) { _, _ ->
                Lockdown.lockForTotal(context, totalMs, prefs)
                render()
            }
            .show()
    }

    // --- Blocks ---

    /**
     * Apps and websites, then on the phone: blocking a list or allowing only one (with the allowed and
     * always allowed apps), the settings changes locked, and accessibility when it's needed and off
     */
    internal fun blocksRows(): Rows {
        val context = requireContext()
        val items = BlockItems.effective(prefs.lockdownItems, LockdownSync.macInstalled(context), LockdownSync.tvInstalled(context))
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val locks = Lockdown.Lock.values()
        val limits = Limits.appsWithRules(context, prefs)
        val needsService = items.values.any { it.phone } || prefs.lockdownProtect || prefs.lockdownSystemSettings || limits > 0 || allow
        return Rows(listOf(
            row("appsAndSites", getString(R.string.lockdown_apps_and_sites), R.string.info_lockdown_blocks, value = blockedValue(items),
                control = Control.Opens, note = getString(R.string.lockdown_nothing_blocked).takeIf { !allow && items.values.none { it.shown } }) {
                open(LockdownPageFragment.BLOCKS)
            },
            segment("mode", getString(R.string.lockdown_during_phone), R.string.info_lockdown_during,
                listOf(getString(R.string.lockdown_block_short), getString(R.string.lockdown_allow_short)), prefs.lockdownAppMode) { pickMode(it) },
            row("allowed", getString(R.string.lockdown_allowed_apps), R.string.info_allowed_apps, level = 1, enabled = allow, control = Control.Opens,
                value = prefs.lockdownAllowedApps.count { context.isPackageInstalled(it) }.toString()) { open(LockdownPageFragment.ALLOWED) },
            row("essentials", getString(R.string.lockdown_always_allowed), R.string.info_lockdown_always_allowed, level = 1, enabled = allow, control = Control.Opens,
                value = (prefs.lockdownEssentials.count { context.isPackageInstalled(it) } + 1).toString()) { open(LockdownPageFragment.ESSENTIALS) },
            row("settingsChanges", getString(R.string.lockdown_settings_changes), R.string.info_lockdown_settings_changes,
                value = getString(R.string.n_of, locks.count { Lockdown.enabled(prefs, it) }, locks.size)),
        ) + locks.map { lockRow(it) } + listOf(
            // Only when it's needed and off, since nothing outside the launcher is blocked without it
            if (needsService && !isAccessServiceEnabled(context)) row("accessibility", getString(R.string.accessibility), R.string.info_lockdown_accessibility,
                value = onOff(false)) { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } } else null,
        ))
    }

    /** How many apps and sites each switched-on device blocks: "12", or "Phone 12 · Mac 5 · TV 2" */
    private fun blockedValue(items: Map<String, BlockItems.Item>): String {
        val context = requireContext()
        fun count(device: Lockdown.Device) = when (device) {
            Lockdown.Device.PHONE -> BlockItems.phoneApps(items).count { context.isPackageInstalled(it) } + BlockItems.phoneSites(items).size
            Lockdown.Device.MAC -> BlockItems.macApps(items).size + BlockItems.macSites(items).size
            Lockdown.Device.TV -> BlockItems.tvApps(items).size
        }
        return if (Lockdown.several(context)) Lockdown.deviceList(context).joinToString(" · ") { LockdownGate.deviceName(context, it.bit) + " " + count(it) }
            else count(Lockdown.Device.PHONE).toString()
    }

    internal fun modeName() = getString(if (prefs.lockdownAppMode == Lockdown.ALLOW) R.string.lockdown_only_allow else R.string.lockdown_block_these)

    /**
     * Blocking a list, or allowing only a list. Leaving the allowlist loosens; going to it is stricter
     * unless it allows an app that's blocked now.
     */
    private fun pickMode(which: Int) {
        if (which == prefs.lockdownAppMode) return
        val change = { prefs.lockdownAppMode = which; addedHere.clear(); render() }
        val unblocks = (prefs.lockdownAllowedApps + prefs.lockdownEssentials).any { it in prefs.lockdownBlockedApps }
        if (which == Lockdown.ALLOW && !unblocks) change() else gate.loosen(change)
    }

    /** A settings change locked or not; unlocking it loosens */
    private fun lockRow(lock: Lockdown.Lock): SettingRow {
        val context = requireContext()
        return switch("lock.${lock.name}", lockName(lock), lockInfo(lock), Lockdown.enabled(prefs, lock), level = 1) {
            if (Lockdown.enabled(prefs, lock)) gate.loosen { Lockdown.setEnabled(prefs, lock, false); render() }
            else {
                Lockdown.setEnabled(prefs, lock, true)
                render()
                val needsService = lock == Lockdown.Lock.PROTECT || lock == Lockdown.Lock.SYSTEM_SETTINGS
                if (needsService && !isAccessServiceEnabled(context)) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
    }

    private fun lockName(lock: Lockdown.Lock) = getString(when (lock) {
        Lockdown.Lock.APP_LIST -> R.string.lockdown_lock_app_list
        Lockdown.Lock.APP_CHANGES -> R.string.lockdown_lock_app_changes
        Lockdown.Lock.HIDDEN_APPS -> R.string.lockdown_lock_hidden_apps
        Lockdown.Lock.LAUNCHER -> R.string.lockdown_lock_launcher
        Lockdown.Lock.PROTECT -> R.string.lockdown_lock_protect
        Lockdown.Lock.SYSTEM_SETTINGS -> R.string.lockdown_lock_system
    })

    private fun lockInfo(lock: Lockdown.Lock) = when (lock) {
        Lockdown.Lock.APP_LIST -> R.string.info_lock_app_list
        Lockdown.Lock.APP_CHANGES -> R.string.info_lock_app_changes
        Lockdown.Lock.HIDDEN_APPS -> R.string.info_lock_hidden
        Lockdown.Lock.LAUNCHER -> R.string.info_lock_launcher
        Lockdown.Lock.PROTECT -> R.string.info_lock_protect
        Lockdown.Lock.SYSTEM_SETTINGS -> R.string.info_lock_system
    }

    // --- Turns on: schedules and places ---

    /** "Weekdays 10 pm–7 am · Home", "2 schedules", or Off */
    internal fun turnsOnValue(): String {
        val context = requireContext()
        val schedules = Lockdown.schedules(prefs)
        val places = prefs.lockdownPlaces.mapNotNull { LockdownGate.placeName(context, it) }
        return listOfNotNull(
            when (schedules.size) {
                0 -> null
                1 -> LockdownGate.scheduleName(context, schedules.first())
                else -> resources.getQuantityString(R.plurals.lockdown_schedules_count, schedules.size, schedules.size)
            },
            places.joinToString(", ").ifEmpty { null },
        ).joinToString(" · ").ifEmpty { getString(R.string.off) }
    }

    /** The schedules and the places, each held or tapped for Edit / Remove / Select, with Add under each */
    internal fun turnsOnRows(): Rows {
        val context = requireContext()
        val schedules = Lockdown.schedules(prefs).mapIndexed { i, s -> s to row("schedule.$i", LockdownGate.scheduleName(context, s), R.string.info_lockdown_schedule_item, level = 1) }
        val places = knownPlaces().filter { it.first in prefs.lockdownPlaces }.map { (id, name) -> Triple(id, name, row("place.$id", name, R.string.info_lockdown_place_item, level = 1)) }
        val items = listOf(row("schedules", getString(R.string.lockdown_schedule), R.string.info_lockdown_schedules)) +
            schedules.map { it.second } + addRow("addSchedule", R.string.info_lockdown_add_schedule) { addSchedule() } +
            row("places", getString(R.string.lockdown_at_places), R.string.info_lockdown_places) +
            places.map { it.third } + addRow("addPlace", R.string.info_lockdown_add_place) { addPlace() }
        return Rows(items) { views ->
            views.listHolding("schedules")?.let { scheduleList.attach(it, scrollView()) }
            schedules.forEach { (schedule, row) ->
                val view = views[row.key] ?: return@forEach
                scheduleList.item(view, schedule, label = row.title, about = row.about, actions = listOf(
                    HoldList.Action(getString(R.string.edit)) { editSchedule(schedule) },
                    HoldList.Action(getString(R.string.remove)) { removeSchedules(setOf(schedule)) },
                ))
            }
            views.listHolding("places")?.let { placeList.attach(it, scrollView()) }
            places.forEach { (id, name, row) ->
                val view = views[row.key] ?: return@forEach
                placeList.item(view, id, label = name, about = row.about, actions = listOf(HoldList.Action(getString(R.string.remove)) { removePlaces(setOf(id)) }))
            }
        }
    }

    /** Homes, work and named places, by their id in [Social] */
    private fun knownPlaces(): List<Pair<Int, String>> {
        val context = requireContext()
        return buildList {
            Social.homes(context).forEachIndexed { slot, home -> if (home != null) add(slot to home.name) }
            Social.work(context)?.let { add(Social.WORK to it.name) }
            Places.places(context).forEach { add(it.id to it.name) }
        }
    }

    private fun addSchedule() = pickSchedule(Lockdown.Schedule(Lockdown.WEEKDAYS, 22 * 60, 7 * 60)) { schedule ->
        val save = {
            prefs.lockdownSchedules = Lockdown.formatSchedules((Lockdown.schedules(prefs) + schedule).distinct())
            render()
        }
        if (schedule.covers(LocalDateTime.now()) && !Lockdown.isOn(requireContext(), prefs)) confirmStartsNow(save) else save()
    }

    /** Changing a schedule so it covers less of the week loosens */
    private fun editSchedule(old: Lockdown.Schedule) = pickSchedule(old) { new ->
        if (new == old) return@pickSchedule
        val before = Lockdown.schedules(prefs)
        val after = before.map { if (it == old) new else it }.distinct()
        val save = { prefs.lockdownSchedules = Lockdown.formatSchedules(after); render() }
        when {
            Lockdown.coverageLost(before, after) -> gate.loosen(save)
            new.covers(LocalDateTime.now()) && !Lockdown.isOn(requireContext(), prefs) -> confirmStartsNow(save)
            else -> save()
        }
    }

    private fun removeSchedules(picked: Set<Lockdown.Schedule>) = gate.loosen {
        prefs.lockdownSchedules = Lockdown.formatSchedules(Lockdown.schedules(prefs) - picked)
        render()
    }

    /** A place not chosen yet; one that's where the phone is now asks first, as it starts lockdown */
    private fun addPlace() {
        val context = requireContext()
        val others = knownPlaces().filter { it.first !in prefs.lockdownPlaces }
        if (others.isEmpty()) return context.showToast(getString(R.string.lockdown_no_places))
        AppDialog(context)
            .setTitle(R.string.lockdown_at_places)
            .setItems(others.map { it.second }.toTypedArray()) { _, which ->
                val id = others[which].first
                val save = { prefs.lockdownPlaces = prefs.lockdownPlaces + id; addedHere += "place:$id"; render() }
                val here = runCatching { Social.currentPlace(context) }.getOrNull() == id
                if (here && !Lockdown.isActive(context, prefs)) confirmStartsNow(save) else save()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Taking places off loosens, unless they were all added while the page was open */
    private fun removePlaces(picked: Set<Int>) {
        val change = { prefs.lockdownPlaces = prefs.lockdownPlaces - picked; picked.forEach { addedHere -= "place:$it" }; render() }
        if (picked.all { "place:$it" in addedHere }) change() else gate.loosen(change)
    }

    /** The days, then when it starts and when it ends, starting from [initial] */
    internal fun pickSchedule(initial: Lockdown.Schedule, onPicked: (Lockdown.Schedule) -> Unit) {
        val context = requireContext()
        val days = java.time.DayOfWeek.values()
        val ticked = days.map { initial.on(it) }.toBooleanArray()
        AppDialog(context)
            .setTitle(R.string.lockdown_schedule_days)
            .setMultiChoiceItems(days.map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) }.toTypedArray(), ticked) { _, i, on -> ticked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.next) { _, _ ->
                val bits = ticked.foldIndexed(0) { i, acc, on -> if (on) acc or (1 shl i) else acc }
                if (bits == 0) return@setPositiveButton context.showToast(getString(R.string.lockdown_choose_a_day))
                val is24 = DateFormat.is24HourFormat(context)
                TimePickerDialog(context, { _, startHour, startMinute ->
                    TimePickerDialog(context, { _, endHour, endMinute ->
                        onPicked(Lockdown.Schedule(bits, startHour * 60 + startMinute, endHour * 60 + endMinute))
                    }, initial.end / 60, initial.end % 60, is24).apply { setTitle(R.string.lockdown_schedule_ends) }.show()
                }, initial.start / 60, initial.start % 60, is24).apply { setTitle(R.string.lockdown_schedule_starts) }.show()
            }
            .show()
    }

    /** Something that turns lockdown on straight away is asked about first */
    internal fun confirmStartsNow(save: () -> Unit) {
        AppDialog(requireContext())
            .setTitle(R.string.lockdown_starts_now)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .show()
    }

    // --- Screen time: Limits, and Lock after ---

    internal fun screenTimeRows(): Rows {
        val context = requireContext()
        val limits = Limits.appsWithRules(context, prefs)
        val triggers = Lockdown.triggers(prefs)
        val used = Lockdown.screenTimeToday(context)
        val rows = triggers.mapIndexed { i, t -> t to row("trigger.$i", triggerName(context, t), R.string.info_lockdown_trigger_item, level = 1) }
        val running = Lockdown.triggerWindows(prefs).mapIndexed { i, w ->
            row("running.$i", getString(R.string.lockdown_on_until, LockdownGate.time(context, Lockdown.epoch(w.end))), R.string.info_lockdown_trigger_running, level = 1)
        }
        val items = listOf(
            row("limits", getString(R.string.limits), R.string.info_app_limits, control = Control.Opens,
                value = if (limits == 0) getString(R.string.off) else resources.getQuantityString(R.plurals.lockdown_apps_count, limits, limits)) {
                open(LockdownPageFragment.LIMITS)
            },
            row("lockAfter", getString(R.string.lockdown_lock_after), R.string.info_lockdown_triggers, value = when (triggers.size) {
                0 -> getString(R.string.off)
                1 -> null
                else -> triggers.size.toString()
            }),
            row("today", getString(R.string.lockdown_today), R.string.info_lockdown_today, level = 1,
                value = sourcesShown().joinToString(" · ") { sourceName(it) + " " + Formats.duration(used[it] ?: 0L) }),
        ) + rows.map { it.second } + addRow("addTrigger", R.string.info_lockdown_add_trigger) { pickTrigger(null) } + running
        return Rows(items) { views ->
            views.listHolding("lockAfter")?.let { triggerList.attach(it, scrollView()) }
            rows.forEach { (trigger, row) ->
                val view = views[row.key] ?: return@forEach
                triggerList.item(view, trigger, label = row.title, about = row.about, actions = listOf(
                    HoldList.Action(getString(R.string.edit)) { pickTrigger(trigger) },
                    HoldList.Action(getString(R.string.remove)) { removeTriggers(setOf(trigger)) },
                ))
            }
        }
    }

    /** Total and phone always; the Mac and TV when they're switched on or their screen time comes in */
    private fun sourcesShown(): List<Lockdown.Source> {
        val context = requireContext()
        return listOfNotNull(
            Lockdown.Source.TOTAL, Lockdown.Source.PHONE,
            Lockdown.Source.MAC.takeIf { Lockdown.macPaired(context) || app.olauncher.helper.DeviceTime.updated(context, app.olauncher.helper.DeviceTime.Device.MAC) > 0 },
            Lockdown.Source.TV.takeIf { Lockdown.tvPaired(context) || app.olauncher.helper.DeviceTime.updated(context, app.olauncher.helper.DeviceTime.Device.TV) > 0 },
        )
    }

    private fun sourceName(source: Lockdown.Source) = getString(when (source) {
        Lockdown.Source.TOTAL -> R.string.lockdown_source_total
        Lockdown.Source.PHONE -> R.string.lockdown_device_phone
        Lockdown.Source.MAC -> R.string.lockdown_device_mac
        Lockdown.Source.TV -> R.string.device_tv
    })

    /**
     * How much total screen time, then until when: a new trigger, or [old] changed. A change that
     * lets more screen time through or ends at another time loosens, unless it was added here.
     */
    private fun pickTrigger(old: Lockdown.Trigger?) {
        val context = requireContext()
        val amounts = listOf(30, 60, 90, 120, 150, 180, 240, 300, 360, 480, 600)
        val start = amounts.indexOf(old?.minutes ?: 180)
        choose(getString(R.string.lockdown_trigger_amount), amounts.map { minutesName(it) }, start, suggested = true) { a ->
            val until = old?.until ?: (6 * 60)
            TimePickerDialog(context, { _, hour, minute ->
                val trigger = Lockdown.Trigger(amounts[a], hour * 60 + minute)
                if (trigger == old) return@TimePickerDialog
                val save = {
                    val others = Lockdown.triggers(prefs).filter { it != old && it.key != trigger.key }
                    prefs.lockdownTriggers = Lockdown.formatTriggers(others + trigger)
                    if (old == null || old.key in addedHere) addedHere += trigger.key
                    Lockdown.checkTriggers(context, prefs)
                    render()
                }
                val now = Lockdown.totalScreenTimeToday(context) >= trigger.minutes * 60_000L
                when {
                    old != null && old.key !in addedHere && Lockdown.triggerLoosens(old, trigger) -> gate.loosen(save)
                    now && !Lockdown.isOn(context, prefs) -> confirmStartsNow(save)
                    else -> save()
                }
            }, until / 60, until % 60, DateFormat.is24HourFormat(context)).apply { setTitle(R.string.lockdown_trigger_until) }.show()
        }
    }

    /** Taking triggers off loosens, unless they were all added while the page was open */
    private fun removeTriggers(picked: Set<Lockdown.Trigger>) {
        val change = { prefs.lockdownTriggers = Lockdown.formatTriggers(Lockdown.triggers(prefs) - picked); render() }
        if (picked.all { it.key in addedHere }) change() else gate.loosen(change)
    }

    // --- Limits' "Before opening" ---

    /** How long the pause before an app is, whether it grows, and the lengths "Use for" offers */
    internal fun beforeOpeningRows(): Rows {
        val context = requireContext()
        val pauses = listOf(5, 10, 15, 20, 30, 45, 60)
        val sets = AppPause.USE_FOR_SETS
        return Rows(
            SettingLabel(getString(R.string.lockdown_before_opening)),
            row("pauseLength", getString(R.string.pause_length), R.string.info_pause_length, value = getString(R.string.seconds_value, prefs.pauseSeconds)) {
                choose(getString(R.string.pause_length), pauses.map { getString(R.string.seconds_value, it) }, pauses.indexOf(prefs.pauseSeconds)) {
                    val seconds = pauses[it]
                    if (seconds < prefs.pauseSeconds) gate.loosen { prefs.pauseSeconds = seconds; render() } else { prefs.pauseSeconds = seconds; render() }
                }
            },
            switch("pauseGrows", getString(R.string.pause_grows), R.string.info_pause_grows, prefs.pauseGrows) {
                // Not growing any more is shorter, so it loosens
                if (prefs.pauseGrows) gate.loosen { prefs.pauseGrows = false; render() } else { prefs.pauseGrows = true; render() }
            },
            row("useFor", getString(R.string.use_for_lengths), R.string.info_use_for_lengths,
                value = AppPause.useForLengths(context).joinToString(", ") { getString(R.string.minutes_short, it) }) {
                choose(getString(R.string.use_for_lengths), sets.map { set -> set.joinToString(", ") { getString(R.string.minutes_short, it) } },
                    sets.indexOf(AppPause.useForLengths(context))) {
                    val change = { AppPause.setUseForSet(context, it); render() }
                    // Longer times loosen
                    if (sets[it].max() > AppPause.useForLengths(context).max()) gate.loosen(change) else change()
                }
            },
        )
    }

    // --- Breaks ---

    private fun breaksValue(): String {
        val config = Lockdown.breaks(prefs)
        if (!config.on && !config.autoOn) return getString(R.string.off)
        val banked = LockdownGate.poolText(Lockdown.updatePool(requireContext(), prefs).banked)
        return listOfNotNull(
            getString(R.string.lockdown_pooled, banked).takeIf { config.on },
            autoBreakName(config).takeIf { config.autoOn },
        ).joinToString(" · ")
    }

    /** Earning breaks and the rules for them (greyed while none are earned), what's banked, and automatic breaks */
    internal fun breaksRows(): Rows {
        val context = requireContext()
        val config = Lockdown.breaks(prefs)
        val on = config.on
        val now = System.currentTimeMillis()
        val pool = Lockdown.updatePool(context, prefs)
        val banked = LockdownGate.poolText(pool.banked)
        val earnOptions = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30)
        val everyOptions = listOf(15, 30, 45, 60, 90, 120, 180, 240)
        val capOptions = listOf(0, 10, 15, 20, 30, 45, 60, 120)
        val types = listOf(Challenge.Type.NONE, Challenge.Type.HOLD, Challenge.Type.TAPS, Challenge.Type.PHRASE, Challenge.Type.RANDOM, Challenge.Type.PASSWORD)
        val poolRow = if (on) {
            val can = Lockdown.canBreak(context, prefs)
            val next = Breaks.nextEarn(config, pool, now)
            row("pool", getString(R.string.lockdown_break_pool), R.string.info_lockdown_break_pool, value = when {
                Lockdown.onBreak(prefs) -> banked + " · " + LockdownGate.deviceStatus(context, Lockdown.Device.PHONE)
                next == null -> getString(R.string.lockdown_break_pool_full, banked)
                can is Breaks.Can.NotLocked || can is Breaks.Can.OffNow -> banked + " · " + LockdownGate.cantBreakText(context, can)
                else -> getString(R.string.lockdown_break_pool_value, banked, Formats.minutes(config.minutes), Formats.duration(next - now))
            })
        } else null
        val offRows = config.off.mapIndexed { i, s -> s to row("off.$i", LockdownGate.scheduleName(context, s), R.string.info_lockdown_break_off_item, level = 2, enabled = on) }
        val cycles = listOf(0 to 0, 5 to 25, 5 to 50, 10 to 60, 15 to 90)
        val items = listOf(
            poolRow,
            row("earn", getString(R.string.lockdown_break_earn), R.string.info_break_earn, value = minutesName(config.minutes)) {
                choose(getString(R.string.lockdown_break_earn), earnOptions.map { minutesName(it) },
                    earnOptions.indexOf(config.minutes).coerceAtLeast(0)) { saveBreaks(config, config.copy(minutes = earnOptions[it])) }
            },
            row("every", getString(R.string.lockdown_break_every), R.string.info_break_every, level = 1, enabled = on,
                value = getString(R.string.lockdown_break_every_value, Formats.minutes(config.every))) {
                choose(getString(R.string.lockdown_break_every), everyOptions.map { Formats.minutes(it) },
                    everyOptions.indexOf(config.every).coerceAtLeast(0)) { saveBreaks(config, config.copy(every = everyOptions[it])) }
            },
            row("cap", getString(R.string.lockdown_break_cap), R.string.info_break_cap, level = 1, enabled = on, value = minutesName(config.cap)) {
                choose(getString(R.string.lockdown_break_cap), capOptions.map { minutesName(it) },
                    capOptions.indexOf(config.cap).coerceAtLeast(0)) { saveBreaks(config, config.copy(cap = capOptions[it])) }
            },
            switch("reset", getString(R.string.lockdown_break_reset), R.string.info_break_reset, config.reset, level = 1, enabled = on) {
                saveBreaks(config, config.copy(reset = !config.reset))
            },
            row("needs", getString(R.string.lockdown_break_needs), R.string.info_break_needs, level = 1, enabled = on,
                value = challengeName(config.challenge) + " · " + difficultyName(difficultyOf(config.challenge))) {
                pickChallenge(getString(R.string.lockdown_break_needs), config.challenge, types) { picked ->
                    val apply = { saveBreaks(config, config.copy(challenge = picked)) }
                    if (picked.type == Challenge.Type.PASSWORD && !prefs.isLockdownPasswordSet) gate.setNewPassword(warn = true) { apply() } else apply()
                }
            },
            if (config.challenge.type == Challenge.Type.PHRASE) row("phrase", getString(R.string.challenge_the_phrase), R.string.info_challenge_phrase,
                value = prefs.lockdownPhrase, level = 2, enabled = on) { editPhrase() } else null,
            switch("makeUp", getString(R.string.lockdown_break_make_up), R.string.info_break_make_up, config.makeUp, level = 1, enabled = on) {
                saveBreaks(config, config.copy(makeUp = !config.makeUp))
            },
            row("noBreaks", getString(R.string.lockdown_no_breaks_during), R.string.info_break_off, level = 1, enabled = on),
        ) + offRows.map { it.second } + listOf(
            addRow("addOff", R.string.info_lockdown_add_break_off, level = 2, enabled = on) {
                pickSchedule(Lockdown.Schedule(Lockdown.EVERY_DAY, 22 * 60, 6 * 60)) { saveBreaks(config, config.copy(off = (config.off + it).distinct())) }
            },
            // Automatic breaks don't need manual ones to be on
            cycle("auto", getString(R.string.lockdown_auto_breaks), R.string.info_auto_breaks,
                cycles.map { (m, e) -> autoBreakName(config.copy(autoMinutes = m, autoEvery = e)) }, cycles.indexOf(config.autoMinutes to config.autoEvery)) {
                val (m, e) = cycles[it]
                saveBreaks(config, config.copy(autoMinutes = m, autoEvery = e))
            },
        )
        return Rows(items) { views ->
            if (!on) return@Rows
            views.listHolding("noBreaks")?.let { breakOffList.attach(it, scrollView()) }
            offRows.forEach { (schedule, row) ->
                val view = views[row.key] ?: return@forEach
                breakOffList.item(view, schedule, label = row.title, about = row.about, actions = listOf(
                    HoldList.Action(getString(R.string.edit)) {
                        pickSchedule(schedule) { new -> Lockdown.breaks(prefs).let { now -> saveBreaks(now, now.copy(off = now.off.map { if (it == schedule) new else it }.distinct())) } }
                    },
                    HoldList.Action(getString(R.string.remove)) { removeBreakOff(setOf(schedule)) },
                ))
            }
        }
    }

    private fun removeBreakOff(picked: Set<Lockdown.Schedule>) {
        val config = Lockdown.breaks(prefs)
        saveBreaks(config, config.copy(off = config.off - picked))
    }

    /** Saves breaks' settings, through the unlock when they make breaks easier to get */
    private fun saveBreaks(old: Breaks.Config, new: Breaks.Config) {
        val change = { prefs.lockdownBreaks = new.encode(); render() }
        if (old.loosensTo(new, prefs.lockdownPhrase.length)) gate.loosen(change) else change()
    }

    /** Automatic breaks in a few words: "5m every 50m", or Off */
    internal fun autoBreakName(config: Breaks.Config) = if (!config.autoOn) getString(R.string.off)
        else getString(R.string.lockdown_auto_break_value, Formats.duration(config.autoMinutes * 60_000L), Formats.duration(config.autoEvery * 60_000L))

    // --- Unlock ---

    /** "Type 200 random characters · Hard" */
    internal fun unlockValue(): String {
        val challenge = Lockdown.challenge(prefs)
        return challengeName(challenge) + " · " + difficultyName(difficultyOf(challenge))
    }

    /** What unlocking needs, the phrase or password, and the ways to get a forgotten password back */
    internal fun unlockRows(): Rows {
        val challenge = Lockdown.challenge(prefs)
        val password = challenge.type == Challenge.Type.PASSWORD
        val recovery = prefs.passwordRecoveryEnabled
        fun method(key: String, title: Int, about: Int, get: () -> Boolean, toggle: () -> Unit) = if (password)
            switch(key, getString(title), about, get(), level = 2, enabled = recovery) { gate.loosen(toggle) } else null
        return Rows(
            row("needs", getString(R.string.lockdown_unlock_needs), R.string.info_lockdown_unlock, value = unlockValue()) {
                pickChallenge(getString(R.string.lockdown_unlock_needs), challenge, Challenge.Type.values().toList()) { setEndChallenge(it) }
            },
            if (challenge.type == Challenge.Type.PHRASE) row("phrase", getString(R.string.challenge_the_phrase), R.string.info_challenge_phrase,
                value = prefs.lockdownPhrase, level = 1) { editPhrase() } else null,
            if (password) row("changePassword", getString(R.string.change_lockdown_password), R.string.info_change_password, level = 1) {
                gate.requirePassword { gate.setNewPassword(warn = false) { render() } }
            } else null,
            if (password) switch("recovery", getString(R.string.lockdown_password_recovery), R.string.info_recovery, recovery, level = 1) {
                gate.loosen { prefs.passwordRecoveryEnabled = !prefs.passwordRecoveryEnabled; render() }
            } else null,
            method("tap500", R.string.recovery_tap_500, R.string.recovery_tap_500_info, { prefs.recoveryTap500 }) { prefs.recoveryTap500 = !prefs.recoveryTap500; render() },
            method("clue", R.string.recovery_clue, R.string.recovery_clue_info, { prefs.recoveryClue }) { toggleClue() },
            method("essay", R.string.recovery_essay, R.string.recovery_essay_info, { prefs.recoveryEssay }) { prefs.recoveryEssay = !prefs.recoveryEssay; render() },
        )
    }

    private fun toggleClue() {
        if (prefs.recoveryClue) {
            prefs.recoveryClue = false
            prefs.recoveryClueText = ""
            return render()
        }
        val input = EditText(requireContext()).apply { hint = getString(R.string.recovery_clue) }
        AppDialog(requireContext())
            .setTitle(R.string.recovery_clue)
            .setView(LockdownGate.padded(requireContext(), input))
            .setPositiveButton(R.string.save) { _, _ ->
                val clue = input.text.toString().trim()
                if (clue.isNotEmpty()) {
                    prefs.recoveryClue = true
                    prefs.recoveryClueText = clue
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- On screen ---

    private fun onScreenValue(): String {
        val context = requireContext()
        return listOfNotNull(
            getString(R.string.lockdown_home_widget).takeIf { prefs.lockdownHomeWidget },
            getString(R.string.lockdown_greyscale).takeIf { LockdownSystem.canGrayscale(context) && prefs.lockdownGrayscale },
            getString(R.string.lockdown_notification).takeIf { prefs.lockdownNotification && LockdownSystem.canNotify(context) },
        ).joinToString(" · ").ifEmpty { getString(R.string.off) }
    }

    /** The home screen widget, greyscale and the notification */
    internal fun onScreenRows(): Rows {
        val context = requireContext()
        val canGrey = LockdownSystem.canGrayscale(context)
        val notifies = prefs.lockdownNotification && LockdownSystem.canNotify(context)
        fun tap(minutes: Int) = getString(R.string.lockdown_a_tap, Formats.duration(minutes * 60_000L))
        val widgetOptions = listOf(0, 15, 30, 60, 120)
        return Rows(
            cycle("widget", getString(R.string.lockdown_home_widget), R.string.info_lockdown_home_widget,
                widgetOptions.map { if (it == 0) getString(R.string.off) else tap(it) },
                if (prefs.lockdownHomeWidget) widgetOptions.indexOf(prefs.lockdownWidgetMinutes) else 0) {
                prefs.lockdownHomeWidget = widgetOptions[it] > 0
                if (widgetOptions[it] > 0) prefs.lockdownWidgetMinutes = widgetOptions[it]
                render()
            },
            if (!canGrey) row("greyscale", getString(R.string.lockdown_greyscale), R.string.info_lockdown_greyscale,
                value = getString(R.string.lockdown_needs_setup)) { greyscaleSetup() }
            else switch("greyscale", getString(R.string.lockdown_greyscale), R.string.info_lockdown_greyscale, prefs.lockdownGrayscale) {
                if (prefs.lockdownGrayscale) gate.loosen { prefs.lockdownGrayscale = false; render() } else { prefs.lockdownGrayscale = true; render() }
            },
            switch("notification", getString(R.string.lockdown_notification), R.string.info_lockdown_notification, notifies) {
                when {
                    !LockdownSystem.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> { prefs.lockdownNotification = !prefs.lockdownNotification; render() }
                }
            },
        )
    }

    /** Greyscale needs a permission only a computer can give */
    internal fun greyscaleSetup() {
        val context = requireContext()
        val command = LockdownSystem.grantCommand(context)
        AppDialog(context)
            .setTitle(R.string.lockdown_greyscale)
            .setMessage(getString(R.string.lockdown_greyscale_setup) + "\n\n" + command)
            .setNegativeButton(R.string.lockdown_copy) { _, _ ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(command, command))
            }
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    // --- Devices ---

    /** The phone, always on, and the Mac and the TV, each switched on or off */
    internal fun devicesRows(): Rows = Rows(
        row("phone", getString(R.string.lockdown_device_phone), R.string.info_lockdown_phone, value = getString(R.string.on),
            note = getString(R.string.lockdown_blocks_phone)),
        row("mac", getString(R.string.lockdown_mac), R.string.info_lockdown_mac, value = deviceValue(Lockdown.Device.MAC),
            note = getString(R.string.lockdown_blocks_mac)) { deviceOptions(Lockdown.Device.MAC) },
        row("tv", getString(R.string.lockdown_tv), R.string.info_lockdown_tv, value = deviceValue(Lockdown.Device.TV),
            note = getString(R.string.lockdown_blocks_tv)) { deviceOptions(Lockdown.Device.TV) },
    )

    /** Off, or On with when it last checked in */
    private fun deviceValue(device: Lockdown.Device): String {
        val context = requireContext()
        if (!Lockdown.paired(context, device)) return getString(R.string.off)
        val seen = Lockdown.lastSeen(context, device)?.let { getString(R.string.lockdown_seen, LockdownGate.time(context, it)) }
        return listOfNotNull(getString(R.string.on), seen).joinToString(" · ")
    }

    /**
     * Switching the Mac or the TV on (the server the devices share is asked for the first time) or
     * off, which loosens; the pairing code stays to hand while either is on
     */
    private fun deviceOptions(device: Lockdown.Device) {
        val context = requireContext()
        if (!LockdownSync.linked(context)) return askServer(device)
        if (!Lockdown.paired(context, device)) return gate.setDeviceOn(device, true)
        AppDialog(context)
            .setTitle(if (device == Lockdown.Device.TV) R.string.lockdown_tv else R.string.lockdown_mac)
            .setItems(arrayOf(getString(R.string.lockdown_mac_code), getString(R.string.turn_off))) { _, which ->
                if (which == 0) showPairingCode() else gate.setDeviceOn(device, false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The server the devices share, once; then the pairing code to type on the Mac or the TV's computer */
    private fun askServer(device: Lockdown.Device) {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.lockdown_mac_url)
            setText(LockdownSync.url(context))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AppDialog(context)
            .setTitle(R.string.lockdown_mac_url)
            .setView(LockdownGate.padded(context, input))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://")) return@setPositiveButton context.showToast(getString(R.string.lockdown_mac_bad_url))
                LockdownSync.turnOn(context, url, device)
                render()
                showPairingCode()
            }
            .show()
    }

    private fun showPairingCode() {
        val context = requireContext()
        val code = LockdownSync.key(context)
        AppDialog(context)
            .setTitle(R.string.lockdown_mac_code)
            .setMessage(code)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.copy) { _, _ ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.lockdown_mac_code), code))
                context.showToast(getString(R.string.copied))
            }
            .show()
    }

    // --- What unlocking, loosening and breaks need ---

    /** A challenge in a few words: "100 taps", "Type 200 random characters", "15m wait" */
    internal fun challengeName(challenge: Challenge): String = when (challenge.type) {
        Challenge.Type.NONE -> getString(R.string.lockdown_unlock_nothing)
        Challenge.Type.HOLD -> getString(R.string.challenge_hold_n, if (challenge.amount < 60) getString(R.string.seconds_value, challenge.amount)
            else Formats.duration(challenge.amount * 1000L))
        Challenge.Type.TAPS -> resources.getQuantityString(R.plurals.challenge_taps, challenge.amount, challenge.amount)
        Challenge.Type.PHRASE -> if (challenge.amount <= 1) getString(R.string.challenge_phrase)
            else resources.getQuantityString(R.plurals.challenge_phrase_times, challenge.amount, challenge.amount)
        Challenge.Type.RANDOM -> resources.getQuantityString(R.plurals.challenge_random, challenge.amount, challenge.amount)
        Challenge.Type.PASSWORD -> getString(R.string.lockdown_password)
        Challenge.Type.WAIT -> getString(R.string.lockdown_unlock_wait, Formats.duration(challenge.amount * 60_000L))
        Challenge.Type.NEVER -> getString(R.string.challenge_never)
    }

    internal fun typeName(type: Challenge.Type) = getString(when (type) {
        Challenge.Type.NONE -> R.string.lockdown_unlock_nothing
        Challenge.Type.HOLD -> R.string.challenge_type_hold
        Challenge.Type.TAPS -> R.string.challenge_type_taps
        Challenge.Type.PHRASE -> R.string.challenge_type_phrase
        Challenge.Type.RANDOM -> R.string.challenge_type_random
        Challenge.Type.PASSWORD -> R.string.lockdown_password
        Challenge.Type.WAIT -> R.string.challenge_type_wait
        Challenge.Type.NEVER -> R.string.challenge_never
    })

    /** Very easy … Impossible */
    internal fun difficultyName(level: Int) = resources.getStringArray(R.array.challenge_difficulty).getOrElse(level) { "" }

    internal fun difficultyOf(challenge: Challenge) = challenge.difficulty(prefs.lockdownPhrase.length)

    /**
     * Picks a challenge from [types]: the type, then how much of it, each shown with how hard it is.
     * Choosing the password sets one if there isn't one. [onPicked] gets the choice.
     */
    internal fun pickChallenge(title: String, current: Challenge, types: List<Challenge.Type>, onPicked: (Challenge) -> Unit) {
        val selected = types.indexOf(current.type)
        choose(title, types.map { typeName(it) }, selected, suggested = true) { which ->
            val type = types[which]
            val amounts = Challenge.amount(type)
            if (amounts.isEmpty()) return@choose onPicked(Challenge(type))
            val start = if (type == current.type) current.amount else Challenge.defaultAmount(type)
            choose(typeName(type), amounts.map { amount ->
                val c = Challenge(type, amount)
                challengeName(c) + " · " + difficultyName(difficultyOf(c))
            }, amounts.indexOf(start), suggested = true) { onPicked(Challenge(type, amounts[it])) }
        }
    }

    /** Saves [new] as what unlocking needs, through the unlock when it's easier; a password is set first if there isn't one */
    internal fun setEndChallenge(new: Challenge) {
        val old = Lockdown.challenge(prefs)
        if (new == old) return
        val save = {
            val apply = {
                prefs.lockdownChallenge = new.encode()
                Lockdown.cancelWait(prefs)
                render()
            }
            if (new.type == Challenge.Type.PASSWORD && !prefs.isLockdownPasswordSet) gate.setNewPassword(warn = true) { apply() } else apply()
        }
        if (Challenge.loosens(old, new, prefs.lockdownPhrase.length)) gate.loosen(save) else save()
    }

    /** The phrase typed out; a shorter one loosens */
    internal fun editPhrase() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(prefs.lockdownPhrase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6
        }
        AppDialog(context)
            .setTitle(R.string.challenge_the_phrase)
            .setView(LockdownGate.padded(context, input))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val phrase = input.text.toString().trim().replace(Regex("\\s+"), " ")
                if (phrase.length < 4) return@setPositiveButton
                val save = { prefs.lockdownPhrase = phrase; render() }
                if (phrase.length < prefs.lockdownPhrase.length) gate.loosen(save) else save()
            }
            .show()
    }

    /** Minutes as "30m", "1h", "1h 30m", or Off for 0 */
    internal fun minutesName(minutes: Int) = if (minutes <= 0) getString(R.string.off) else Formats.duration(minutes * 60_000L)
}
