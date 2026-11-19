package app.olauncher.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updatePadding
import app.olauncher.R
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
 * Lockdown's page: what's on now and why, with Start; then what it blocks, what starts it by itself,
 * what ending it early needs, extras, and today's numbers. The details of each are a page down.
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
        app.olauncher.helper.LockdownSync.pull(requireContext(), force = true)
        if (arguments?.getBoolean(LOCK_FOR) == true) {
            arguments?.remove(LOCK_FOR)
            askLockFor()
        }
        // The first time, with nothing set up, the setup opens
        if (!prefs.lockdownSetupOffered) {
            prefs.lockdownSetupOffered = true
            if (LockdownSetupFragment.isFresh(prefs)) openSetup()
        }
        // Times, stages and waits move on while the page is open
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 30_000)
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
            main.postDelayed(this, 30_000)
        }
    }

    override fun build() {
        statusCard()
        blocksSection()
        startsSection()
        endingSection()
        extrasSection()
        todaySection()
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
        val active = Lockdown.isActive(context, prefs)
        val stages = Limits.stages(prefs)
        val used = Limits.totalToday(context)
        val stage = Limits.stage(stages, used)
        // With no lockdown on, today's stage is the news
        val big = if (!active && stage > 0) getString(R.string.lockdown_stage_now, stage, LockdownGate.stageName(context, stage))
            else LockdownGate.status(context)
        card.add(TextView(context, null, 0, R.style.TextLarge).apply {
            text = big
            textSize = 26f
            setTextColor(ink)
        })
        LockdownGate.whyText(context)?.let { card.add(card.textView(14f, 165).apply { text = it; updatePadding(top = 4.dpToPx()) }) }
        LockdownGate.screenTimeLine(context)?.let { (line, _) ->
            card.add(card.textView(14f, 165).apply { text = line; updatePadding(top = 10.dpToPx()) })
            card.add(StageBar(context, stages, used, ink, labels = true), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dpToPx()))
        }
        // Off: one Start, which asks for how long. On: what can be done with the lockdown that's on
        val buttons = FlowLayout(context).apply {
            updatePadding(top = 10.dpToPx())
            when {
                !active -> addView(pill(getString(R.string.lockdown_start)) { askLockFor() })
                prefs.lockdownModeOn -> addView(pill(getString(R.string.lockdown_turn_off)) { toggleManual() })
                Lockdown.timedActive(context, prefs) -> {
                    addView(pill(getString(R.string.lockdown_add_time)) { askLockFor() })
                    addView(pill(getString(R.string.lockdown_end_now)) { gate.endTimed() })
                }
                else -> {
                    addView(pill(getString(R.string.lockdown_lock_for)) { askLockFor() })
                    addView(pill(getString(R.string.lockdown_keep_on_button)) { toggleManual() })
                }
            }
        }
        card.add(buttons)
    }

    private fun Card.add(view: View, params: LinearLayout.LayoutParams) = root.addView(view, root.indexOfChild(scene), params)

    private fun openSetup() {
        runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.lockdownSetupFragment) }
    }

    /** Turning it on while a timer, schedule, place or stage has it on keeps it on past their end, so that's asked first */
    private fun toggleManual() {
        val context = requireContext()
        if (prefs.lockdownModeOn) return gate.loosen { prefs.lockdownModeOn = false; render() }
        val until = Lockdown.activeUntil(context, prefs)
        if (!Lockdown.isActive(context, prefs)) {
            prefs.lockdownModeOn = true
            return render()
        }
        AppDialog(context)
            .setTitle(until?.let { getString(R.string.lockdown_keep_on_after, LockdownGate.time(context, it)) } ?: getString(R.string.lockdown_keep_on))
            .setMessage(R.string.lockdown_keep_on_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lockdown_keep_on_button) { _, _ ->
                prefs.lockdownModeOn = true
                render()
            }
            .show()
    }

    // --- What it blocks ---

    private fun blocksSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_what_it_blocks))
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val chosen = (if (allow) prefs.lockdownAllowedApps else prefs.lockdownBlockedApps).count { context.isPackageInstalled(it) }
        val sites = prefs.lockdownSites.size
        val locks = Lockdown.Lock.values()
        val limits = Limits.appsWithRules(context, prefs)
        val needsService = chosen > 0 || prefs.lockdownProtect || prefs.lockdownSystemSettings || sites > 0 || limits > 0
        card.add(
            row(card, getString(R.string.lockdown_apps), when {
                allow -> getString(R.string.lockdown_all_but, chosen)
                chosen == 0 -> getString(R.string.lockdown_none_chosen)
                else -> chosen.toString()
            }, if (allow) R.string.info_allowed_apps else R.string.info_blocked_apps) {
                open(LockdownPageFragment.BLOCKED)
            }.also { if (!allow && chosen == 0) it.valueColor(0xFFEF5350.toInt()) },
            row(card, getString(R.string.lockdown_websites), if (sites == 0) getString(R.string.lockdown_none) else sites.toString(),
                R.string.info_lockdown_websites) { open(LockdownPageFragment.WEBSITES) },
            row(card, getString(R.string.lockdown_settings_changes), getString(R.string.lockdown_n_of, locks.count { Lockdown.enabled(prefs, it) }, locks.size),
                R.string.info_lockdown_settings_changes) { open(LockdownPageFragment.PROTECTION) },
            // Only when it's needed and off, since nothing outside the launcher is blocked without it
            if (needsService && !isAccessServiceEnabled(context)) row(card, getString(R.string.accessibility), onOff(false), R.string.info_lockdown_accessibility) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else null,
        )
    }

    // --- Starts by itself ---

    private fun startsSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_starts_by_itself))
        val schedules = Lockdown.schedules(prefs)
        val places = prefs.lockdownPlaces.mapNotNull { LockdownGate.placeName(context, it) }
        val stages = Limits.stages(prefs)
        card.add(
            row(card, getString(R.string.lockdown_on_a_schedule), when (schedules.size) {
                0 -> getString(R.string.off)
                1 -> LockdownGate.scheduleName(context, schedules.first())
                else -> schedules.size.toString()
            }, R.string.info_lockdown_schedules) { if (schedules.isEmpty()) addSchedule() else showSchedules() },
            row(card, getString(R.string.lockdown_at_places), if (places.isEmpty()) getString(R.string.off) else places.joinToString(", "),
                R.string.info_lockdown_places) { open(LockdownPageFragment.PLACES) },
            row(card, getString(R.string.lockdown_after_screen_time), if (stages.all { it <= 0 }) getString(R.string.off)
                else stages.joinToString(" · ") { minutesName(it) }, R.string.info_lockdown_stages) { open(LockdownPageFragment.STAGES) },
        )
    }

    // --- Ending it early ---

    private fun endingSection() {
        val card = section(getString(R.string.lockdown_ending_early))
        card.add(row(card, getString(R.string.lockdown_needs), unlockName(Lockdown.unlock(prefs)), R.string.info_lockdown_unlock_with) {
            open(LockdownPageFragment.UNLOCK)
        })
    }

    // --- Extras ---

    private fun extrasSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_extras))
        val canGrey = LockdownSystem.canGrayscale(context)
        fun tap(minutes: Int) = getString(R.string.lockdown_a_tap, LockdownGate.duration(context, minutes * 60_000L))
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
            row(card, getString(R.string.lockdown_mac), macValue(), R.string.info_lockdown_mac) { macOptions() },
            row(card, getString(R.string.lockdown_setup), null, R.string.info_lockdown_setup) { openSetup() },
        )
    }

    // --- Mac ---

    /** Off, or on with how many requests it made today (what it costs) */
    private fun macValue(): String {
        val context = requireContext()
        if (!LockdownSync.isOn(context)) return getString(R.string.off)
        val last = LockdownSync.lastRequest(context).takeIf { it > 0 }?.let { LockdownGate.time(context, it) }
        return getString(R.string.lockdown_mac_value, LockdownSync.requestsToday(context)) + (last?.let { " · $it" } ?: "")
    }

    private fun macOptions() {
        val context = requireContext()
        if (!LockdownSync.isOn(context)) return askMacUrl()
        AppDialog(context)
            .setTitle(R.string.lockdown_mac)
            .setItems(arrayOf(getString(R.string.lockdown_mac_code), getString(R.string.turn_off))) { _, which ->
                if (which == 0) showMacCode()
                // Taking the Mac out of lockdown loosens it
                else gate.loosen { LockdownSync.turnOff(context); render() }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** The database both share, once; then the pairing code to type on the Mac */
    private fun askMacUrl() {
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
                LockdownSync.turnOn(context, url)
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

    /** Starts a timed lockdown, or adds to the one running. It can't be shortened. */
    private fun askLockFor() {
        val context = requireContext()
        val running = Lockdown.timedActive(context, prefs)
        val minutes = listOf(15, 30, 60, 120, 240, 480, 1440)
        // Starting from off can also be until it's turned off
        val untilOff = !running && !Lockdown.isActive(context, prefs)
        val names = minutes.map { (if (running) "+" else "") + LockdownGate.duration(context, it * 60_000L) } + getString(R.string.lockdown_until_a_time) +
            listOfNotNull(getString(R.string.lockdown_until_off).takeIf { untilOff })
        AppDialog(context)
            .setTitle(if (running) R.string.lockdown_add_time else R.string.lockdown_start_title)
            .setItems(names.toTypedArray()) { _, which ->
                val left = Lockdown.timedMsLeft(context, prefs)
                if (which < minutes.size) return@setItems confirmLockFor(left + minutes[which] * 60_000L)
                if (which > minutes.size) {
                    prefs.lockdownModeOn = true
                    return@setItems render()
                }
                val now = java.time.LocalTime.now()
                TimePickerDialog(context, { _, hour, minute ->
                    val at = LocalDate.now().atTime(hour, minute).let { if (it.isAfter(LocalDateTime.now())) it else it.plusDays(1) }
                    val total = java.time.Duration.between(LocalDateTime.now(), at).toMillis()
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

    /** The days, then when it starts and ends; one that starts lockdown now is asked about first */
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
                        if (schedule.covers(LocalDateTime.now()) && !Lockdown.isActive(context, prefs)) confirmStartsNow(save) else save()
                    }, 7, 0, is24).apply { setTitle(R.string.lockdown_schedule_ends) }.show()
                }, 22, 0, is24).apply { setTitle(R.string.lockdown_schedule_starts) }.show()
            }
            .show()
    }

    // --- Today ---

    private fun todaySection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_today))
        val streak = Lockdown.streak(context, prefs)
        card.add(
            row(card, getString(R.string.lockdown_blocked_attempts), Lockdown.blockedToday(prefs).toString(), R.string.info_blocked_attempts, onTap = null),
            streak?.let { row(card, getString(R.string.lockdown_streak), resources.getQuantityString(R.plurals.lockdown_days, it, it), R.string.info_streak, onTap = null) },
        )
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

/**
 * Today's screen time on a bar, with a mark at each screen time stage; with [labels], each mark's time
 * above it and its name below.
 */
class StageBar(context: Context, private val stages: List<Int>, private val usedMs: Long, private val ink: Int, private val labels: Boolean) : View(context) {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * resources.displayMetrics.scaledDensity
        textAlign = android.graphics.Paint.Align.CENTER
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val set = stages.withIndex().filter { it.value > 0 }
        // The bar runs a little past the last stage, or past today's use if that's more
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
        set.forEach { (i, minutes) ->
            val mx = x(minutes * 60_000L)
            paint.color = ink
            canvas.drawRect(mx - d, y - 10 * d, mx + d, y + 10 * d, paint)
            if (labels) {
                text.color = ink
                canvas.drawText(LockdownGate.duration(context, minutes * 60_000L), mx, y - 14 * d, text)
                text.color = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 150)
                canvas.drawText(LockdownGate.stageShort(context, i + 1), mx, y + 24 * d, text)
            }
        }
    }
}
