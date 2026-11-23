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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.updatePadding
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.helper.BlockItems
import app.olauncher.helper.Breaks
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSync
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.dpToPx
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Lockdown's page: what's on now on each device and why, with Start, Add time, End early and a break;
 * then what it blocks, the devices, what starts it by itself, breaks, what ending it early needs, and
 * extras. The details of each are a page down.
 */
class LockdownFragment : LockdownBaseFragment() {

    companion object {
        /** Argument: open straight at "Lock for a set time" (from the Quick Settings tile) */
        const val LOCK_FOR = "lockFor"
    }

    private var recoveryTaps = 0

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
            render()
            main.postDelayed(this, 15_000)
        }
    }

    override fun build() {
        statusCard()
        blocksSection()
        devicesSection()
        startsSection()
        breaksSection()
        endingSection()
        extrasSection()
    }

    // --- Now ---

    private fun statusCard() {
        val context = requireContext()
        val card = Card("")
        card.panel()
        // No title row: the status is the card's heading
        (card.titleView.parent as? View)?.visibility = View.GONE
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
                Lockdown.turnOn(prefs)
                render()
            }
            .show()
    }

    // --- What it blocks ---

    private fun blocksSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_what_it_blocks))
        val items = BlockItems.effective(prefs.lockdownItems, LockdownSync.macInstalled(context), LockdownSync.tvInstalled(context))
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val phone = if (allow) getString(R.string.lockdown_all_but, prefs.lockdownAllowedApps.count { context.isPackageInstalled(it) })
            else (BlockItems.phoneApps(items).count { context.isPackageInstalled(it) } + BlockItems.phoneSites(items).size).toString()
        fun count(device: Lockdown.Device) = when (device) {
            Lockdown.Device.PHONE -> phone
            Lockdown.Device.MAC -> (BlockItems.macApps(items).size + BlockItems.macSites(items).size).toString()
            Lockdown.Device.TV -> BlockItems.tvApps(items).size.toString()
        }
        val value = if (Lockdown.several(context)) Lockdown.deviceList(context).joinToString(" · ") { LockdownGate.deviceName(context, it.bit) + " " + count(it) }
            else phone
        val locks = Lockdown.Lock.values()
        val limits = Limits.appsWithRules(context, prefs)
        val needsService = items.values.any { it.phone } || prefs.lockdownProtect || prefs.lockdownSystemSettings || limits > 0 || allow
        card.add(
            row(card, getString(R.string.lockdown_apps_and_sites), value, R.string.info_lockdown_blocks) { open(LockdownPageFragment.BLOCKS) }
                .also { if (!allow && items.values.none { it.shown }) it.valueColor(Style.DANGER) },
            row(card, getString(R.string.lockdown_settings_changes), getString(R.string.lockdown_n_of, locks.count { Lockdown.enabled(prefs, it) }, locks.size),
                R.string.info_lockdown_settings_changes) { open(LockdownPageFragment.PROTECTION) },
            // Only when it's needed and off, since nothing outside the launcher is blocked without it
            if (needsService && !isAccessServiceEnabled(context)) row(card, getString(R.string.accessibility), onOff(false), R.string.info_lockdown_accessibility) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else null,
        )
    }

    // --- The devices ---

    private fun devicesSection() {
        val card = section(getString(R.string.lockdown_devices))
        card.add(
            row(card, getString(R.string.lockdown_mac), deviceValue(Lockdown.Device.MAC), R.string.info_lockdown_mac) { deviceOptions(Lockdown.Device.MAC) },
            row(card, getString(R.string.lockdown_tv), deviceValue(Lockdown.Device.TV), R.string.info_lockdown_tv) { deviceOptions(Lockdown.Device.TV) },
        )
    }

    // --- Starts by itself ---

    private fun startsSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_starts_by_itself))
        val schedules = Lockdown.schedules(prefs)
        val places = prefs.lockdownPlaces.mapNotNull { LockdownGate.placeName(context, it) }
        val triggers = Lockdown.triggers(prefs)
        card.add(
            row(card, getString(R.string.lockdown_on_a_schedule), when (schedules.size) {
                0 -> getString(R.string.off)
                1 -> LockdownGate.scheduleName(context, schedules.first())
                else -> schedules.size.toString()
            }, R.string.info_lockdown_schedules) { if (schedules.isEmpty()) addSchedule() else showSchedules() },
            row(card, getString(R.string.lockdown_after_screen_time), when (triggers.size) {
                0 -> getString(R.string.off)
                1 -> triggerName(context, triggers.first())
                else -> triggers.size.toString()
            }, R.string.info_lockdown_triggers) { open(LockdownPageFragment.TRIGGERS) },
            row(card, getString(R.string.lockdown_at_places), if (places.isEmpty()) getString(R.string.off) else places.joinToString(", "),
                R.string.info_lockdown_places) { open(LockdownPageFragment.PLACES) },
        )
    }

    // --- Breaks ---

    private fun breaksSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_breaks))
        val config = Lockdown.breaks(prefs)
        val value = if (!config.on && !config.autoOn) getString(R.string.off) else listOfNotNull(
            Formats.duration(config.minutes * 60_000L).takeIf { config.on },
            config.perDay.takeIf { it > 0 && config.on }?.let { getString(R.string.lockdown_n_a_day, it) },
            autoBreakName(config).takeIf { config.autoOn },
        ).joinToString(" · ")
        card.add(row(card, getString(R.string.lockdown_breaks), value, R.string.info_lockdown_breaks) { open(LockdownPageFragment.BREAKS) })
        if (config.on) {
            val today = Lockdown.epoch(LocalDate.now().atStartOfDay())
            val taken = Breaks.countSince(Lockdown.breakLog(prefs), today)
            val can = Lockdown.canBreak(context, prefs)
            val state = when {
                Lockdown.onBreak(prefs) -> LockdownGate.deviceStatus(context, Lockdown.Device.PHONE)
                can == Breaks.Can.Yes -> getString(R.string.lockdown_break_ready)
                else -> LockdownGate.cantBreakText(context, can)
            }
            card.add(line(card, getString(R.string.lockdown_breaks_today, taken) + " · " + state))
        }
    }

    // --- Ending it early ---

    private fun endingSection() {
        val card = section(getString(R.string.lockdown_ending_early))
        val challenge = Lockdown.challenge(prefs)
        card.add(row(card, getString(R.string.lockdown_needs), challengeName(challenge) + " · " + difficultyName(difficultyOf(challenge)),
            R.string.info_lockdown_unlock_with) { open(LockdownPageFragment.UNLOCK) })
    }

    // --- Extras ---

    private fun extrasSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_extras))
        val canGrey = LockdownSystem.canGrayscale(context)
        fun tap(minutes: Int) = getString(R.string.lockdown_a_tap, Formats.duration(minutes * 60_000L))
        card.add(
            row(card, getString(R.string.lockdown_home_widget), if (prefs.lockdownHomeWidget) tap(prefs.lockdownWidgetMinutes) else getString(R.string.off),
                R.string.info_lockdown_home_widget) {
                val options = listOf(0, 15, 30, 60, 120)
                choose(getString(R.string.lockdown_home_widget), options.map { if (it == 0) getString(R.string.off) else tap(it) },
                    if (prefs.lockdownHomeWidget) options.indexOf(prefs.lockdownWidgetMinutes) else 0) {
                    prefs.lockdownHomeWidget = options[it] > 0
                    if (options[it] > 0) prefs.lockdownWidgetMinutes = options[it]
                    render()
                }
            },
            row(card, getString(R.string.lockdown_grayscale), if (!canGrey) getString(R.string.lockdown_needs_setup) else onOff(prefs.lockdownGrayscale),
                R.string.info_lockdown_grayscale) {
                when {
                    !canGrey -> grayscaleSetup()
                    prefs.lockdownGrayscale -> gate.loosen { prefs.lockdownGrayscale = false; render() }
                    else -> { prefs.lockdownGrayscale = true; render() }
                }
            },
            row(card, getString(R.string.lockdown_notification), onOff(prefs.lockdownNotification && LockdownSystem.canNotify(context)),
                R.string.info_lockdown_notification) {
                when {
                    !LockdownSystem.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> { prefs.lockdownNotification = !prefs.lockdownNotification; render() }
                }
            },
            row(card, getString(R.string.lockdown_setup), null, R.string.info_lockdown_setup) { openSetup() },
            row(card, getString(R.string.lockdown_stats), null, R.string.info_lockdown_stats) {
                app.olauncher.helper.AutoStats.id(context, app.olauncher.helper.AutoStats.LOCKDOWN)?.let { openStat(it) }
                    ?: context.showToast(getString(R.string.no_data))
            },
        )
    }

    // --- The Mac and the TV ---

    /** Off, or On with when it last checked in */
    private fun deviceValue(device: Lockdown.Device): String {
        val context = requireContext()
        if (!Lockdown.paired(context, device)) return getString(R.string.off)
        val seen = Lockdown.lastSeen(context, device)?.let { getString(R.string.lockdown_seen, LockdownGate.time(context, it)) }
        return listOfNotNull(getString(R.string.on), seen).joinToString(" · ")
    }

    /**
     * Switching the Mac or the TV on (the shared database is asked for the first time) or off, which
     * loosens; the pairing code stays to hand while either is on
     */
    private fun deviceOptions(device: Lockdown.Device) {
        val context = requireContext()
        if (!LockdownSync.linked(context)) return askMacUrl(device)
        if (!Lockdown.paired(context, device)) return gate.setDeviceOn(device, true)
        AppDialog(context)
            .setTitle(if (device == Lockdown.Device.TV) R.string.lockdown_tv else R.string.lockdown_mac)
            .setItems(arrayOf(getString(R.string.lockdown_mac_code), getString(R.string.turn_off))) { _, which ->
                if (which == 0) showMacCode() else gate.setDeviceOn(device, false)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** The database the devices share, once; then the pairing code to type on the Mac or the TV's PC */
    private fun askMacUrl(device: Lockdown.Device) {
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
            .setPositiveButton(R.string.okay) { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://")) return@setPositiveButton context.showToast(getString(R.string.lockdown_mac_bad_url))
                LockdownSync.turnOn(context, url, device)
                render()
                showMacCode()
            }
            .show()
    }

    private fun showMacCode() {
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
        val dialog = AppDialog(context)
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
                    Lockdown.turnOn(prefs)
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
        dialog.show()
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

    // --- Schedules ---

    /** The schedules: tapping one removes it, which loosens lockdown */
    private fun showSchedules() {
        val context = requireContext()
        val schedules = Lockdown.schedules(prefs)
        AppDialog(context)
            .setTitle(R.string.lockdown_schedules)
            .setItems(schedules.map { LockdownGate.scheduleName(context, it) }.toTypedArray()) { _, i ->
                val schedule = schedules[i]
                AppDialog(context)
                    .setTitle(LockdownGate.scheduleName(context, schedule))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.lockdown_remove) { _, _ ->
                        gate.loosen {
                            prefs.lockdownSchedules = Lockdown.formatSchedules(Lockdown.schedules(prefs) - schedule)
                            render()
                        }
                    }
                    .show()
            }
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.add) { _, _ -> addSchedule() }
            .show()
    }

    /** The days, and when it starts and ends; one that starts lockdown now is asked about first */
    private fun addSchedule() {
        val context = requireContext()
        val days = java.time.DayOfWeek.values()
        val ticked = days.map { it.value <= 5 }.toBooleanArray()
        AppDialog(context)
            .setTitle(R.string.lockdown_schedule_days)
            .setMultiChoiceItems(days.map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) }.toTypedArray(), ticked) { _, i, on -> ticked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.okay) { _, _ ->
                val bits = ticked.foldIndexed(0) { i, acc, on -> if (on) acc or (1 shl i) else acc }
                if (bits == 0) return@setPositiveButton context.showToast(getString(R.string.lockdown_choose_a_day))
                val is24 = DateFormat.is24HourFormat(context)
                TimePickerDialog(context, { _, startHour, startMinute ->
                    TimePickerDialog(context, { _, endHour, endMinute ->
                        val schedule = Lockdown.Schedule(bits, startHour * 60 + startMinute, endHour * 60 + endMinute)
                        val save = {
                            prefs.lockdownSchedules = Lockdown.formatSchedules(Lockdown.schedules(prefs) + schedule)
                            render()
                        }
                        if (schedule.covers(LocalDateTime.now()) && !Lockdown.isOn(context, prefs)) confirmStartsNow(save) else save()
                    }, 7, 0, is24).apply { setTitle(R.string.lockdown_schedule_ends) }.show()
                }, 22, 0, is24).apply { setTitle(R.string.lockdown_schedule_starts) }.show()
            }
            .show()
    }

    /** Something that turns lockdown on straight away is asked about first */
    private fun confirmStartsNow(save: () -> Unit) {
        AppDialog(requireContext())
            .setTitle(R.string.lockdown_starts_now)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ -> save() }
            .show()
    }
}

/** "Total over 4h · until 07:00": the day's total screen time across every device */
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
