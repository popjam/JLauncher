package app.olauncher.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.updatePadding
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.helper.Breaks
import app.olauncher.helper.Challenge
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSync
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Lockdown's page: the status card (what's on now and why, with Start, Add time, End early and a
 * break), then its sections: Blocks, Turns on, Screen time, Breaks, Unlock, On screen and Devices.
 * Turns on, Lock after, Breaks, Unlock, On screen and Devices open and close in place, one at a time;
 * only the long lists (apps and websites, the allowed apps, Limits) have pages of their own.
 */
class LockdownFragment : LockdownBaseFragment() {

    companion object {
        /** Argument: open straight at "Lock for a set time" (from the Quick Settings tile) */
        const val LOCK_FOR = "lockFor"
    }

    private var recoveryTaps = 0

    private val triggerList = HoldList<Lockdown.Trigger>(onRemove = { removeTriggers(it) }, tapOpensMenu = true)
    private val breakOffList = HoldList<Lockdown.Schedule>(onRemove = { removeBreakOff(it) }, tapOpensMenu = true)
    override val holdLists: List<HoldList<*>> get() = super.holdLists + listOf(triggerList, breakOffList)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.lockdownNotification = granted
        render()
        // Turned down for good: only the system settings can allow it now
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
        }
    }

    override fun title() = getString(R.string.lockdown)
    override fun showIcon() = true

    override fun onResume() {
        super.onResume()
        LockdownSync.pull(requireContext(), force = true)
        if (arguments?.getBoolean(LOCK_FOR) == true) {
            arguments?.remove(LOCK_FOR)
            askLockFor()
        }
        // The first time, with nothing set up, the setup opens
        if (!prefs.lockdownSetupOffered) {
            prefs.lockdownSetupOffered = true
            if (LockdownSetupFragment.isFresh(prefs)) openSetup()
        }
        // Times, breaks and waits move on while the page is open
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 15_000)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!isAdded) return
            Limits.refresh(requireContext())
            // Not while picking several, so the ticks stay put
            if (holdLists.none { it.selecting }) render()
            main.postDelayed(this, 15_000)
        }
    }

    override fun build() {
        statusCard()
        section(getString(R.string.lockdown_blocks)).rows(blocksRows())
        Card(getString(R.string.lockdown_turns_on)).collapsible("turnsOn", turnsOnValue(), R.string.info_lockdown_turns_on, turnsOnRows())
        screenTimeSection()
        breaksSection()
        Card(getString(R.string.lockdown_unlock)).collapsible("unlock", unlockValue(), R.string.info_lockdown_unlock, unlockRows())
        onScreenSection()
        devicesSection()
        untitled().rows(Rows(listOf(
            row(getString(R.string.lockdown_setup), null, R.string.info_lockdown_setup) { openSetup() },
            row(getString(R.string.lockdown_stats), null, R.string.info_lockdown_stats) {
                app.olauncher.helper.AutoStats.id(requireContext(), app.olauncher.helper.AutoStats.LOCKDOWN)?.let { openStat(it) }
                    ?: requireContext().showToast(getString(R.string.no_data))
            },
        )))
    }

    // --- Now ---

    private fun statusCard() {
        val context = requireContext()
        val card = untitled()
        // Tapping the status 500 times shows the password, when that way of recovering it is on
        card.root.setOnClickListener {
            if (!prefs.passwordRecoveryEnabled || !prefs.recoveryTap500 || !prefs.isLockdownPasswordSet) return@setOnClickListener
            recoveryTaps++
            if (recoveryTaps >= 500) {
                recoveryTaps = 0
                gate.showPassword()
            } else if (recoveryTaps % 50 == 0) context.showToast(getString(R.string.recovery_tap_progress, recoveryTaps))
        }
        val several = Lockdown.several(context)
        val on = Lockdown.isOn(context, prefs)
        card.add(TextView(context, null, 0, R.style.TextLarge).apply {
            text = LockdownGate.status(context)
            textSize = Style.TEXT_LARGE
            setTextColor(ink)
        })
        LockdownGate.whyText(context)?.takeIf { on }?.let { card.add(card.textView(Style.TEXT_BODY, 165).apply { text = it; updatePadding(top = 4.dpToPx()) }) }
        // Each device on its own line, when there's more than the phone
        if (several) card.add(card.textView(Style.TEXT_BODY, 215).apply {
            text = Lockdown.deviceList(context).joinToString("\n") { LockdownGate.deviceName(context, it.bit) + " · " + LockdownGate.deviceStatus(context, it) }
            setLineSpacing(0f, 1.25f)
            updatePadding(top = 10.dpToPx())
        })
        val endable = Lockdown.modeOn(prefs) || Lockdown.timedActive(context, prefs)
        val breaks = Lockdown.breaks(prefs)
        val buttons = FlowLayout(context).apply {
            updatePadding(top = 10.dpToPx())
            if (!on) addView(pill(getString(R.string.lockdown_start)) { askLockFor() })
            else {
                addView(pill(getString(R.string.lockdown_add_time)) { askLockFor() })
                if (endable) addView(pill(getString(R.string.lockdown_end_early)) { gate.endEarly() })
                if (!Lockdown.modeOn(prefs)) addView(pill(getString(R.string.lockdown_keep_on_button)) { toggleManual() })
            }
            if (on && Lockdown.onBreak(prefs)) addView(pill(getString(R.string.lockdown_end_break)) { gate.endBreak() })
            else if (breaks.on && on) addView(pill(getString(R.string.lockdown_take_break)) { gate.takeBreak() })
        }
        card.add(buttons)
    }

    private fun openSetup() {
        runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.lockdownSetupFragment) }
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

    // --- Screen time: Limits, and Lock after ---

    private fun screenTimeSection() {
        val context = requireContext()
        val limits = Limits.appsWithRules(context, prefs)
        val triggers = Lockdown.triggers(prefs)
        val used = Lockdown.screenTimeToday(context)
        val today = row(getString(R.string.lockdown_today), sourcesShown().joinToString(" · ") { sourceName(it) + " " + Formats.duration(used[it] ?: 0L) },
            null, level = 1, onTap = null)
        val rows = triggers.map { it to row(triggerName(context, it), null, null, level = 1, onTap = null) }
        val running = Lockdown.triggerWindows(prefs).map { w ->
            row(getString(R.string.lockdown_on_until, LockdownGate.time(context, Lockdown.epoch(w.end))), null, null, level = 1, onTap = null)
        }
        val lockAfter = collapsibleGroup(sections, "lockAfter", getString(R.string.lockdown_lock_after), when (triggers.size) {
            0 -> getString(R.string.off)
            1 -> triggerName(context, triggers.first())
            else -> triggers.size.toString()
        }, getString(R.string.info_lockdown_triggers), listOf(today) + rows.map { it.second } + addRow { pickTrigger(null) } + running)
        triggerList.attach(lockAfter.rows, scrollView())
        rows.forEach { (trigger, view) ->
            triggerList.item(view, trigger, label = triggerName(context, trigger), actions = listOf(
                HoldList.Action(getString(R.string.edit)) { pickTrigger(trigger) },
                HoldList.Action(getString(R.string.remove)) { removeTriggers(setOf(trigger)) },
            ))
        }
        section(getString(R.string.lockdown_screen_time)).rows(Rows(listOf(
            row(getString(R.string.limits), if (limits == 0) getString(R.string.off) else resources.getQuantityString(R.plurals.lockdown_apps_count, limits, limits),
                R.string.info_app_limits) { open(LockdownPageFragment.LIMITS) },
            lockAfter.view,
        )))
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

    // --- Breaks ---

    private fun breaksSection() {
        val context = requireContext()
        val config = Lockdown.breaks(prefs)
        val value = if (!config.on && !config.autoOn) getString(R.string.off) else listOfNotNull(
            Formats.duration(config.minutes * 60_000L).takeIf { config.on },
            config.perDay.takeIf { it > 0 && config.on }?.let { getString(R.string.lockdown_n_a_day, it) },
            autoBreakName(config).takeIf { config.autoOn },
        ).joinToString(" · ")
        val rows = mutableListOf<View?>(
            row(getString(R.string.lockdown_break_length), minutesName(config.minutes), R.string.info_break_length) {
                val options = listOf(0, 1, 2, 5, 10, 15, 30)
                choose(getString(R.string.lockdown_break_length), options.map { minutesName(it) },
                    options.indexOf(config.minutes).coerceAtLeast(0)) { save(config, config.copy(minutes = options[it])) }
            },
            // Automatic breaks don't need manual ones to be on
            row(getString(R.string.lockdown_auto_breaks), autoBreakName(config), R.string.info_auto_breaks) {
                val cycles = listOf(0 to 0, 5 to 25, 5 to 50, 10 to 60, 15 to 90)
                val current = cycles.indexOf(config.autoMinutes to config.autoEvery).coerceAtLeast(0)
                choose(getString(R.string.lockdown_auto_breaks), cycles.map { (m, e) -> autoBreakName(config.copy(autoMinutes = m, autoEvery = e)) }, current) {
                    val (m, e) = cycles[it]
                    save(config, config.copy(autoMinutes = m, autoEvery = e))
                }
            },
        )
        val offRows = if (config.on) config.off.map { it to row(LockdownGate.scheduleName(context, it), null, null, level = 1, onTap = null) } else emptyList()
        if (config.on) {
            val taken = Breaks.countSince(Lockdown.breakLog(prefs), Lockdown.epoch(LocalDate.now().atStartOfDay()))
            val can = Lockdown.canBreak(context, prefs)
            val state = when {
                Lockdown.onBreak(prefs) -> LockdownGate.deviceStatus(context, Lockdown.Device.PHONE)
                can == Breaks.Can.Yes -> getString(R.string.lockdown_break_ready)
                else -> LockdownGate.cantBreakText(context, can)
            }
            val types = listOf(Challenge.Type.NONE, Challenge.Type.HOLD, Challenge.Type.TAPS, Challenge.Type.PHRASE, Challenge.Type.RANDOM, Challenge.Type.PASSWORD)
            val limits = listOf(0, 1, 2, 3, 4, 5, 10)
            fun limitName(n: Int) = if (n <= 0) getString(R.string.lockdown_none) else n.toString()
            fun limitRow(title: Int, value: Int, set: (Int) -> Breaks.Config) = row(getString(title), limitName(value), R.string.info_break_limits) {
                choose(getString(title), limits.map { limitName(it) }, limits.indexOf(value).coerceAtLeast(0)) { save(config, set(limits[it])) }
            }
            rows += listOf(
                row(getString(R.string.lockdown_today), resources.getQuantityString(R.plurals.lockdown_breaks_taken, taken, taken) + " · " + state, null, onTap = null),
                row(getString(R.string.lockdown_break_needs), challengeName(config.challenge) + " · " + difficultyName(difficultyOf(config.challenge)),
                    R.string.info_break_needs) {
                    pickChallenge(getString(R.string.lockdown_break_needs), config.challenge, types) { picked ->
                        val apply = { save(config, config.copy(challenge = picked)) }
                        if (picked.type == Challenge.Type.PASSWORD && !prefs.isLockdownPasswordSet) gate.setNewPassword(warn = true) { apply() } else apply()
                    }
                },
                if (config.challenge.type == Challenge.Type.PHRASE) row(getString(R.string.challenge_the_phrase), prefs.lockdownPhrase, null, level = 1) { editPhrase() } else null,
                row(getString(R.string.lockdown_break_gap), if (config.cooldown <= 0) getString(R.string.lockdown_none) else minutesName(config.cooldown),
                    R.string.info_break_gap) {
                    val options = listOf(0, 5, 10, 15, 30, 60, 120, 240)
                    choose(getString(R.string.lockdown_break_gap), options.map { if (it == 0) getString(R.string.lockdown_none) else minutesName(it) },
                        options.indexOf(config.cooldown).coerceAtLeast(0)) { save(config, config.copy(cooldown = options[it])) }
                },
                limitRow(R.string.lockdown_per_hour, config.perHour) { config.copy(perHour = it) },
                limitRow(R.string.lockdown_per_day, config.perDay) { config.copy(perDay = it) },
                limitRow(R.string.lockdown_per_week, config.perWeek) { config.copy(perWeek = it) },
                row(getString(R.string.lockdown_break_stack), onOff(config.stack), R.string.info_break_stack) { save(config, config.copy(stack = !config.stack)) },
                row(getString(R.string.lockdown_break_make_up), onOff(config.makeUp), R.string.info_break_make_up) { save(config, config.copy(makeUp = !config.makeUp)) },
                row(getString(R.string.lockdown_no_breaks_during), null, R.string.info_break_off, onTap = null),
            )
            rows += offRows.map { it.second }
            rows += addRow { pickSchedule(Lockdown.Schedule(Lockdown.EVERY_DAY, 22 * 60, 6 * 60)) { save(config, config.copy(off = (config.off + it).distinct())) } }
        }
        val list = Card(getString(R.string.lockdown_breaks)).collapsible("breaks", value, R.string.info_lockdown_breaks, Rows(rows))
        breakOffList.attach(list, scrollView())
        offRows.forEach { (schedule, view) ->
            breakOffList.item(view, schedule, label = LockdownGate.scheduleName(context, schedule), actions = listOf(
                HoldList.Action(getString(R.string.edit)) {
                    pickSchedule(schedule) { new -> Lockdown.breaks(prefs).let { now -> save(now, now.copy(off = now.off.map { if (it == schedule) new else it }.distinct())) } }
                },
                HoldList.Action(getString(R.string.remove)) { removeBreakOff(setOf(schedule)) },
            ))
        }
    }

    private fun removeBreakOff(picked: Set<Lockdown.Schedule>) {
        val config = Lockdown.breaks(prefs)
        save(config, config.copy(off = config.off - picked))
    }

    /** Saves breaks' settings, through the unlock when they make breaks easier to get */
    private fun save(old: Breaks.Config, new: Breaks.Config) {
        val change = { prefs.lockdownBreaks = new.encode(); render() }
        if (old.loosensTo(new, prefs.lockdownPhrase.length)) gate.loosen(change) else change()
    }

    // --- On screen ---

    private fun onScreenSection() {
        val context = requireContext()
        val canGrey = LockdownSystem.canGrayscale(context)
        val notifies = prefs.lockdownNotification && LockdownSystem.canNotify(context)
        fun tap(minutes: Int) = getString(R.string.lockdown_a_tap, Formats.duration(minutes * 60_000L))
        val value = listOfNotNull(
            getString(R.string.lockdown_home_widget).takeIf { prefs.lockdownHomeWidget },
            getString(R.string.lockdown_greyscale).takeIf { canGrey && prefs.lockdownGrayscale },
            getString(R.string.lockdown_notification).takeIf { notifies },
        ).joinToString(" · ").ifEmpty { getString(R.string.off) }
        Card(getString(R.string.lockdown_on_screen)).collapsible("onScreen", value, null, Rows(listOf(
            row(getString(R.string.lockdown_home_widget), if (prefs.lockdownHomeWidget) tap(prefs.lockdownWidgetMinutes) else getString(R.string.off),
                R.string.info_lockdown_home_widget) {
                val options = listOf(0, 15, 30, 60, 120)
                choose(getString(R.string.lockdown_home_widget), options.map { if (it == 0) getString(R.string.off) else tap(it) },
                    if (prefs.lockdownHomeWidget) options.indexOf(prefs.lockdownWidgetMinutes) else 0) {
                    prefs.lockdownHomeWidget = options[it] > 0
                    if (options[it] > 0) prefs.lockdownWidgetMinutes = options[it]
                    render()
                }
            },
            row(getString(R.string.lockdown_greyscale), if (!canGrey) getString(R.string.lockdown_needs_setup) else onOff(prefs.lockdownGrayscale),
                R.string.info_lockdown_greyscale) {
                when {
                    !canGrey -> greyscaleSetup()
                    prefs.lockdownGrayscale -> gate.loosen { prefs.lockdownGrayscale = false; render() }
                    else -> { prefs.lockdownGrayscale = true; render() }
                }
            },
            row(getString(R.string.lockdown_notification), onOff(notifies), R.string.info_lockdown_notification) {
                when {
                    !LockdownSystem.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> { prefs.lockdownNotification = !prefs.lockdownNotification; render() }
                }
            },
        )))
    }

    // --- Devices ---

    private fun devicesSection() {
        val context = requireContext()
        val on = Lockdown.deviceList(context).fold(0) { mask, device -> mask or device.bit }
        Card(getString(R.string.lockdown_devices)).collapsible("devices", LockdownGate.deviceName(context, on), R.string.info_lockdown_devices, Rows(listOf(
            noted(row(getString(R.string.lockdown_device_phone), getString(R.string.on), R.string.info_lockdown_phone, onTap = null),
                getString(R.string.lockdown_blocks_phone)),
            noted(row(getString(R.string.lockdown_mac), deviceValue(Lockdown.Device.MAC), R.string.info_lockdown_mac) { deviceOptions(Lockdown.Device.MAC) },
                getString(R.string.lockdown_blocks_mac)),
            noted(row(getString(R.string.lockdown_tv), deviceValue(Lockdown.Device.TV), R.string.info_lockdown_tv) { deviceOptions(Lockdown.Device.TV) },
                getString(R.string.lockdown_blocks_tv)),
        )))
    }

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
        val input = android.widget.EditText(context).apply {
            hint = getString(R.string.lockdown_mac_url)
            setText(LockdownSync.url(context))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
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

    // --- Starting and adding time ---

    /** Starts a timed lockdown on every switched-on device, or adds to the one running. It can't be shortened. */
    private fun askLockFor() {
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
}

/** "Total over 4h · until 7 am": the day's total screen time across every device */
fun triggerName(context: Context, trigger: Lockdown.Trigger): String {
    val until = Formats.time(context, Formats.minutesOfDay(trigger.until))
    return context.getString(R.string.lockdown_trigger_name, context.getString(R.string.lockdown_source_total),
        Formats.duration(trigger.minutes * 60_000L), until)
}

/** Today's use of an app on a bar, with a mark at its limit ([limits], minutes) */
class LimitBar(context: Context, private val limits: List<Int>, private val usedMs: Long, private val ink: Int) : View(context) {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val set = limits.withIndex().filter { it.value > 0 }
        // The bar runs a little past the last mark, or past today's use if that's more
        val span = maxOf((set.maxOfOrNull { it.value } ?: 60) * 60_000L * 6 / 5, usedMs * 21 / 20).coerceAtLeast(1)
        val left = 4 * d
        val right = width - 4 * d
        val y = height / 2f
        val h = 8 * d
        fun x(ms: Long) = left + (right - left) * (ms.toFloat() / span).coerceIn(0f, 1f)
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 30)
        canvas.drawRoundRect(left, y - h / 2, right, y + h / 2, h / 2, h / 2, paint)
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 215)
        canvas.drawRoundRect(left, y - h / 2, x(usedMs).coerceAtLeast(left + h), y + h / 2, h / 2, h / 2, paint)
        set.forEach { (_, minutes) ->
            val mx = x(minutes * 60_000L)
            paint.color = ink
            canvas.drawRect(mx - d, y - 10 * d, mx + d, y + 10 * d, paint)
        }
    }
}
