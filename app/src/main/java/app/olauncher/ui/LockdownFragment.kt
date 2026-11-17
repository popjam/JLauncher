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
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.dpToPx
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Lockdown's page: what's on now and why, then when it turns on, the apps it covers, how it's
 * unlocked, what it protects, and today's numbers.
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
        whenSection()
        appsSection()
        unlockSection()
        protectionSection()
        todaySection()
    }

    // --- Now ---

    private fun statusCard() {
        val context = requireContext()
        val card = Card("")
        card.scene.visibility = View.GONE
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
        val buttons = FlowLayout(context).apply {
            updatePadding(top = 10.dpToPx())
            addView(pill(getString(R.string.lockdown_lock_for)) { askLockFor() })
            addView(pill(getString(if (prefs.lockdownModeOn) R.string.lockdown_turn_off else R.string.lockdown_turn_on)) { toggleManual() })
            addView(pill(getString(R.string.lockdown_setup)) { openSetup() })
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
        AlertDialog.Builder(context)
            .setTitle(until?.let { getString(R.string.lockdown_keep_on_after, LockdownGate.time(context, it)) } ?: getString(R.string.lockdown_keep_on))
            .setMessage(R.string.lockdown_keep_on_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lockdown_keep_on_button) { _, _ ->
                prefs.lockdownModeOn = true
                render()
            }
            .show()
    }

    // --- When ---

    private fun whenSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_when))
        val schedules = Lockdown.schedules(prefs)
        val places = prefs.lockdownPlaces.mapNotNull { LockdownGate.placeName(context, it) }
        card.add(
            row(card, getString(R.string.lockdown_schedules), if (schedules.isEmpty()) getString(R.string.off) else schedules.size.toString(),
                R.string.info_lockdown_schedules) { if (schedules.isEmpty()) addSchedule() else showSchedules() },
            row(card, getString(R.string.lockdown_places), if (places.isEmpty()) getString(R.string.off) else places.joinToString(", "),
                R.string.info_lockdown_places) { open(LockdownPageFragment.PLACES) },
            row(card, getString(R.string.lockdown_home_widget), onOff(prefs.lockdownHomeWidget), R.string.info_lockdown_home_widget) {
                prefs.lockdownHomeWidget = !prefs.lockdownHomeWidget
                render()
            },
            if (prefs.lockdownHomeWidget) row(card, getString(R.string.lockdown_each_tap), LockdownGate.duration(context, prefs.lockdownWidgetMinutes * 60_000L),
                R.string.info_lockdown_each_tap, level = 1) {
                val options = listOf(15, 30, 60, 120)
                choose(getString(R.string.lockdown_each_tap), options.map { LockdownGate.duration(context, it * 60_000L) },
                    options.indexOf(prefs.lockdownWidgetMinutes)) { prefs.lockdownWidgetMinutes = options[it]; render() }
            } else null,
        )
    }

    /** Starts a timed lockdown, or adds to the one running. It can't be shortened. */
    private fun askLockFor() {
        val context = requireContext()
        val running = Lockdown.timedActive(context, prefs)
        val minutes = listOf(15, 30, 60, 120, 240, 480, 1440)
        val names = minutes.map { (if (running) "+" else "") + LockdownGate.duration(context, it * 60_000L) } + getString(R.string.lockdown_until_a_time)
        AlertDialog.Builder(context)
            .setTitle(if (running) R.string.lockdown_add_time else R.string.lockdown_lock_for)
            .setItems(names.toTypedArray()) { _, which ->
                val left = Lockdown.timedMsLeft(context, prefs)
                if (which < minutes.size) return@setItems confirmLockFor(left + minutes[which] * 60_000L)
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
        AlertDialog.Builder(context)
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
        AlertDialog.Builder(context)
            .setTitle(R.string.lockdown_schedules)
            .setItems(schedules.map { LockdownGate.scheduleName(context, it) }.toTypedArray()) { _, i ->
                val schedule = schedules[i]
                AlertDialog.Builder(context)
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
        AlertDialog.Builder(context)
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

    // --- Apps ---

    private fun appsSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_apps))
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val chosen = (if (allow) prefs.lockdownAllowedApps else prefs.lockdownBlockedApps).count { context.isPackageInstalled(it) }
        val limits = Limits.rules(prefs).keys.count { context.isPackageInstalled(it) } +
            (prefs.pausedApps + prefs.useForApps + prefs.lockdownBlockedApps).count { it !in Limits.rules(prefs) && context.isPackageInstalled(it) }
        val stages = Limits.stages(prefs)
        val needsService = chosen > 0 || prefs.lockdownProtect || prefs.lockdownSystemSettings || prefs.lockdownSites.isNotEmpty() || limits > 0
        card.add(
            row(card, getString(R.string.lockdown_during), getString(if (allow) R.string.lockdown_only_allow else R.string.lockdown_block_these),
                R.string.info_lockdown_during) { pickMode() },
            row(card, getString(if (allow) R.string.lockdown_allowed_apps else R.string.blocked_apps), chosen.toString(),
                if (allow) R.string.info_allowed_apps else R.string.info_blocked_apps) {
                open(if (allow) LockdownPageFragment.ALLOWED else LockdownPageFragment.BLOCKED)
            },
            row(card, getString(R.string.lockdown_app_limits), resources.getQuantityString(R.plurals.lockdown_apps_count, limits, limits),
                R.string.info_app_limits) { open(LockdownPageFragment.LIMITS) },
            row(card, getString(R.string.lockdown_stages), if (stages.all { it <= 0 }) getString(R.string.off)
                else stages.joinToString(" · ") { minutesName(it) }, R.string.info_lockdown_stages) { open(LockdownPageFragment.STAGES) },
            row(card, getString(R.string.lockdown_essentials), (prefs.lockdownEssentials.count { context.isPackageInstalled(it) } + 1).toString(),
                R.string.info_lockdown_essentials, level = 1) { open(LockdownPageFragment.ESSENTIALS) },
            row(card, getString(R.string.lockdown_websites), prefs.lockdownSites.size.toString(), R.string.info_lockdown_websites) {
                open(LockdownPageFragment.WEBSITES)
            },
            // Only when it's needed and off, since nothing outside the launcher is blocked without it
            if (needsService && !isAccessServiceEnabled(context)) row(card, getString(R.string.accessibility), onOff(isAccessServiceEnabled(context)), R.string.info_lockdown_accessibility) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else null,
        )
    }

    /**
     * Blocking a list, or allowing only a list. Leaving the allowlist loosens; going to it is stricter
     * unless it allows an app that's blocked now.
     */
    private fun pickMode() {
        val options = listOf(getString(R.string.lockdown_block_these), getString(R.string.lockdown_only_allow))
        choose(getString(R.string.lockdown_during), options, prefs.lockdownAppMode) { which ->
            if (which == prefs.lockdownAppMode) return@choose
            val change = { prefs.lockdownAppMode = which; render() }
            val unblocks = (prefs.lockdownAllowedApps + prefs.lockdownEssentials).any { it in prefs.lockdownBlockedApps }
            if (which == Lockdown.ALLOW && !unblocks) change() else gate.loosen(change)
        }
    }

    // --- Unlock ---

    private fun unlockSection() {
        val card = section(getString(R.string.lockdown_unlock))
        val unlock = Lockdown.unlock(prefs)
        val password = unlock == Lockdown.Unlock.PASSWORD
        val recovery = password && prefs.passwordRecoveryEnabled
        fun method(title: Int, about: Int, get: () -> Boolean, toggle: () -> Unit) = if (recovery) row(card, getString(title), onOff(get()), about, level = 2) {
            gate.loosen(toggle)
        } else null
        card.add(
            row(card, getString(R.string.lockdown_unlock_with), unlockName(unlock), R.string.info_lockdown_unlock_with) { pickUnlock() },
            if (password) row(card, getString(R.string.change_lockdown_password), null, R.string.info_change_password, level = 1) {
                gate.requirePassword { gate.setNewPassword(warn = false) { render() } }
            } else null,
            if (password) row(card, getString(R.string.lockdown_password_recovery), onOff(prefs.passwordRecoveryEnabled), R.string.info_recovery, level = 1) {
                gate.loosen { prefs.passwordRecoveryEnabled = !prefs.passwordRecoveryEnabled; render() }
            } else null,
            method(R.string.recovery_tap_500, R.string.recovery_tap_500_info, { prefs.recoveryTap500 }) { prefs.recoveryTap500 = !prefs.recoveryTap500; render() },
            method(R.string.recovery_clue, R.string.recovery_clue_info, { prefs.recoveryClue }) { toggleClue() },
            method(R.string.recovery_essay, R.string.recovery_essay_info, { prefs.recoveryEssay }) { prefs.recoveryEssay = !prefs.recoveryEssay; render() },
        )
    }

    private fun toggleClue() {
        if (prefs.recoveryClue) {
            prefs.recoveryClue = false
            prefs.recoveryClueText = ""
            return render()
        }
        val input = android.widget.EditText(requireContext()).apply { hint = getString(R.string.enter_clue) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.enter_clue)
            .setView(LockdownGate.padded(requireContext(), input))
            .setPositiveButton(R.string.okay) { _, _ ->
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

    // --- Protection ---

    private fun protectionSection() {
        val context = requireContext()
        val card = section(getString(R.string.lockdown_protection))
        val locks = Lockdown.Lock.values().map { lock ->
            row(card, lockName(lock), onOff(Lockdown.enabled(prefs, lock)), lockInfo(lock)) {
                if (Lockdown.enabled(prefs, lock)) gate.loosen { Lockdown.setEnabled(prefs, lock, false); render() }
                else {
                    Lockdown.setEnabled(prefs, lock, true)
                    render()
                    val needsService = lock == Lockdown.Lock.PROTECT || lock == Lockdown.Lock.SYSTEM_SETTINGS
                    if (needsService && !isAccessServiceEnabled(context)) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
        }
        val canGrey = LockdownSystem.canGrayscale(context)
        card.add(
            *locks.toTypedArray(),
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
        )
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
        AlertDialog.Builder(requireContext())
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
