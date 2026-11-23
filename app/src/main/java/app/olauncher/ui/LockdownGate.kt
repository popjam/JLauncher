package app.olauncher.ui

import android.content.Context
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.Breaks
import app.olauncher.helper.Challenge
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.PasswordHelper
import app.olauncher.helper.Places
import app.olauncher.helper.Social
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Asking to loosen lockdown, from [fragment]: the password (and the ways to get it back), the wait,
 * or nothing, as chosen. Also how lockdown's times read.
 *
 * Made once the fragment is attached. Its dialogs keep working (on the activity) if the fragment's
 * page is closed under them, as it is when JLauncher goes to the background.
 */
class LockdownGate(fragment: Fragment, private val onChanged: () -> Unit = {}) {

    private val context: Context = fragment.requireContext()
    private val prefs get() = Prefs(context)

    /** Does [action] if lockdown allows loosening now, after asking for whatever it needs */
    fun loosen(action: () -> Unit) {
        val prefs = prefs
        if (Lockdown.isOpen(context, prefs)) return action()
        Lockdown.Device.values().mapNotNull { Lockdown.timedEnd(context, prefs, it) }.maxOrNull()?.let { end ->
            return locked(context, context.getString(R.string.lockdown_locked_until, time(context, end)))
        }
        val challenge = Lockdown.challenge(prefs)
        when (challenge.type) {
            Challenge.Type.NONE -> action()
            Challenge.Type.NEVER -> locked(context, lockedMessage(context))
            Challenge.Type.PASSWORD -> requirePassword(action)
            Challenge.Type.WAIT -> askWait()
            else -> run(challenge, context.getString(R.string.lockdown_loosen_title)) {
                Lockdown.startGrace()
                action()
            }
        }
    }

    /**
     * Ends lockdown on [devices] early: switched on and timed lockdowns end; schedules, screen time,
     * places and stages carry on. Needs what ending early is set to need.
     */
    fun endEarly(devices: Int) {
        val prefs = prefs
        val end = {
            Lockdown.turnOff(prefs, devices)
            Lockdown.endTimed(prefs, devices)
            if (Lockdown.challenge(prefs).type == Challenge.Type.WAIT) Lockdown.cancelWait(prefs)
            app.olauncher.helper.LockdownSystem.sync(context)
            onChanged()
        }
        val challenge = Lockdown.challenge(prefs)
        when (challenge.type) {
            Challenge.Type.NONE -> confirmEnd(end)
            Challenge.Type.NEVER -> AppDialog(context).setTitle(R.string.lockdown_cant_end).setPositiveButton(R.string.okay, null).show()
            Challenge.Type.PASSWORD -> if (prefs.isLockdownPasswordSet) requirePassword(end) else confirmEnd(end)
            Challenge.Type.WAIT -> if (Lockdown.waitState(context, prefs) is Lockdown.WaitState.Ready) end() else askWait()
            else -> run(challenge, context.getString(R.string.lockdown_end_title)) { end() }
        }
    }

    /** The phone's timed lockdown (and the Mac's, matched) ended early */
    fun endTimed() = endEarly(Lockdown.startDevices(context, prefs))

    private fun confirmEnd(end: () -> Unit) {
        AppDialog(context)
            .setTitle(R.string.lockdown_end_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lockdown_end_now) { _, _ -> end() }
            .show()
    }

    // --- Breaks ---

    /** Takes a break on [device], after whatever taking one needs; says why not when it can't be taken */
    fun takeBreak(device: Lockdown.Device = Lockdown.Device.PHONE, onStarted: () -> Unit = {}) {
        val prefs = prefs
        val config = Lockdown.breaks(prefs)
        when (val can = Lockdown.canBreak(context, prefs, device)) {
            Breaks.Can.Yes -> {}
            else -> return context.showToast(cantBreakText(context, can))
        }
        val start = {
            // Checked again, in case time passed while the challenge was done
            if (Lockdown.canBreak(context, prefs, device) == Breaks.Can.Yes) {
                Lockdown.startBreak(context, prefs, device)
                app.olauncher.helper.LockdownSystem.sync(context)
                context.showToast(context.getString(R.string.lockdown_break_until, time(context, Lockdown.breakUntil(prefs, device))))
                onChanged()
                onStarted()
            }
        }
        val challenge = config.challenge
        when (challenge.type) {
            Challenge.Type.NONE -> start()
            Challenge.Type.PASSWORD -> if (prefs.isLockdownPasswordSet) askPassword(context.getString(R.string.enter_password)) { password ->
                if (PasswordHelper.verifyPassword(password, prefs.lockdownPasswordHash)) start()
                else context.showToast(context.getString(R.string.password_incorrect))
            } else start()
            else -> run(challenge, context.getString(R.string.lockdown_break_title, config.minutes)) { start() }
        }
    }

    /** Ends [device]'s break now, so lockdown blocks again */
    fun endBreak(device: Lockdown.Device = Lockdown.Device.PHONE) {
        Lockdown.endBreak(prefs, device)
        app.olauncher.helper.LockdownSystem.sync(context)
        onChanged()
    }

    // --- Challenges done there and then ---

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Asks for [challenge] (one done on the spot: holding, tapping, typing) under [title], then does [onPass] */
    fun run(challenge: Challenge, title: String, onPass: () -> Unit) {
        when (challenge.type) {
            Challenge.Type.HOLD -> hold(challenge.amount, title, onPass)
            Challenge.Type.TAPS -> taps(challenge.amount, title, onPass)
            Challenge.Type.PHRASE -> type(title, List(challenge.amount.coerceAtLeast(1)) { prefs.lockdownPhrase }, ignoreSpaces = false, onPass)
            Challenge.Type.RANDOM -> type(title, listOf(Challenge.randomText(challenge.amount)), ignoreSpaces = true, onPass)
            Challenge.Type.PASSWORD -> requirePassword(onPass)
            Challenge.Type.NEVER -> locked(context, lockedMessage(context))
            else -> onPass()
        }
    }

    /** A countdown that has to stay open on screen; leaving it starts it again */
    private fun hold(seconds: Int, title: String, onPass: () -> Unit) {
        val text = TextView(context, null, 0, R.style.TextLarge).apply {
            textSize = 40f
            gravity = android.view.Gravity.CENTER
        }
        var left = seconds
        val dialog = AppDialog(context)
            .setTitle(title)
            .setMessage(R.string.lockdown_hold_message)
            .setView(padded(context, text))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lockdown_continue) { _, _ -> onPass() }
            .create()
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                text.text = clockText(left * 1000L)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = left <= 0
                if (left > 0) {
                    left--
                    main.postDelayed(this, 1000)
                }
            }
        }
        dialog.setOnShowListener { tick.run() }
        dialog.setOnDismissListener { main.removeCallbacks(tick) }
        // Leaving the screen starts it again
        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { focused -> if (!focused && left > 0) left = seconds }
        dialog.show()
    }

    /** A button tapped [count] times */
    private fun taps(count: Int, title: String, onPass: () -> Unit) {
        var done = 0
        lateinit var dialog: AlertDialog
        val counter = TextView(context, null, 0, R.style.TextLarge).apply {
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            text = context.getString(R.string.lockdown_taps_left, count)
        }
        val button = TextView(context, null, 0, R.style.TextLarge).apply {
            text = context.getString(R.string.lockdown_tap)
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(2.dpToPx(), androidx.core.graphics.ColorUtils.setAlphaComponent(currentTextColor, 160))
            }
            setOnClickListener {
                done++
                counter.text = context.getString(R.string.lockdown_taps_left, count - done)
                if (done >= count) {
                    dialog.dismiss()
                    onPass()
                }
            }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 8.dpToPx())
            addView(counter)
            addView(button, LinearLayout.LayoutParams(140.dpToPx(), 140.dpToPx()).apply { topMargin = 16.dpToPx() })
        }
        dialog = AppDialog(context).setTitle(title).setView(box).setNegativeButton(R.string.cancel, null).create()
        dialog.show()
    }

    /** Each of [texts] typed out in turn, exactly, with pasting turned off */
    private fun type(title: String, texts: List<String>, ignoreSpaces: Boolean, onPass: () -> Unit) {
        var index = 0
        val shown = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            // Not selectable, so it can't be copied
            setTextIsSelectable(false)
        }
        val progress = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = 13f
            alpha = 0.6f
            setPadding(0, 6.dpToPx(), 0, 0)
        }
        val input = EditText(context).apply {
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isLongClickable = false
            customSelectionActionModeCallback = noPaste
            customInsertionActionModeCallback = noPaste
        }
        fun show() {
            shown.text = texts[index]
            progress.text = if (texts.size > 1) context.getString(R.string.lockdown_n_of, index + 1, texts.size) else ""
            input.setText("")
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(shown)
            addView(progress)
            addView(input)
        }
        show()
        val dialog = AppDialog(context)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.okay, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!Challenge.matches(input.text.toString(), texts[index], ignoreSpaces)) {
                    context.showToast(context.getString(R.string.lockdown_not_matching))
                    return@setOnClickListener
                }
                index++
                if (index >= texts.size) {
                    dialog.dismiss()
                    onPass()
                } else show()
            }
        }
        dialog.show()
    }

    /** Takes paste out of the text box's menus */
    private val noPaste = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = true.also { strip(menu) }
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = true.also { strip(menu) }
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
        private fun strip(menu: android.view.Menu?) {
            menu?.removeItem(android.R.id.paste)
            menu?.removeItem(android.R.id.pasteAsPlainText)
            menu?.removeItem(android.R.id.autofill)
        }
    }

    // --- Waiting ---

    private fun askWait() {
        val prefs = prefs
        val waitMs = Lockdown.challenge(prefs).amount * 60_000L
        when (val state = Lockdown.waitState(context, prefs)) {
            is Lockdown.WaitState.Waiting -> AppDialog(context)
                .setTitle(context.getString(R.string.lockdown_unlocks_in, duration(context, state.msLeft)))
                .setPositiveButton(R.string.okay, null)
                .setNegativeButton(R.string.lockdown_stop_waiting) { _, _ ->
                    Lockdown.cancelWait(prefs)
                    onChanged()
                }
                .show()
            else -> AppDialog(context)
                .setTitle(R.string.lockdown_wait_title)
                .setMessage(context.getString(R.string.lockdown_wait_message, duration(context, waitMs), duration(context, Lockdown.READY_MS)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.lockdown_start_wait) { _, _ ->
                    Lockdown.startWait(context, prefs)
                    onChanged()
                }
                .show()
        }
    }

    // --- The password ---

    /** Does [action] once the password is given, or straight away when there isn't one */
    fun requirePassword(action: () -> Unit) {
        val prefs = prefs
        if (!prefs.isLockdownPasswordSet) return action()
        askPassword(context.getString(R.string.enter_password)) { password ->
            if (PasswordHelper.verifyPassword(password, prefs.lockdownPasswordHash)) {
                Lockdown.startGrace()
                action()
            } else context.showToast(context.getString(R.string.password_incorrect))
        }
    }

    /** Sets a new password, after warning what losing it means; [onSet] once it's saved */
    fun setNewPassword(warn: Boolean, onSet: () -> Unit) {
        if (warn) {
            AppDialog(context)
                .setTitle(R.string.catastrophic_warning)
                .setMessage(R.string.lockdown_password_warning)
                .setPositiveButton(R.string.proceed) { _, _ -> setNewPassword(false, onSet) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        askPassword(context.getString(R.string.set_lockdown_password), generate = onSet) { first ->
            if (first.length < 4) return@askPassword context.showToast(context.getString(R.string.password_too_short))
            askPassword(context.getString(R.string.confirm_password)) { second ->
                if (first != second) return@askPassword context.showToast(context.getString(R.string.passwords_do_not_match))
                savePassword(first, onSet)
            }
        }
    }

    private fun savePassword(password: String, onSet: () -> Unit) {
        val prefs = prefs
        prefs.lockdownPasswordHash = PasswordHelper.hashPassword(password)
        prefs.lockdownPasswordPlaintext = password
        context.showToast(context.getString(R.string.password_set_success))
        onSet()
    }

    fun clearPassword() {
        val prefs = prefs
        prefs.lockdownPasswordHash = ""
        prefs.lockdownPasswordPlaintext = ""
    }

    /** [generate], when given, offers a made-up password and is called once that's saved */
    private fun askPassword(title: String, generate: (() -> Unit)? = null, onEntered: (String) -> Unit) {
        val context = context
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = context.getString(R.string.enter_password)
        }
        val show = android.widget.CheckBox(context).apply {
            text = context.getString(R.string.show)
            setOnCheckedChangeListener { _, checked ->
                input.inputType = InputType.TYPE_CLASS_TEXT or if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                input.setSelection(input.text.length)
            }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(input)
            addView(show)
        }
        val builder = AppDialog(context)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.okay) { _, _ -> onEntered(input.text.toString()) }
            .setNegativeButton(R.string.close, null)
        if (generate != null) builder.setNeutralButton(R.string.generate_password) { _, _ -> generatePassword(generate) }
        else if (prefs.passwordRecoveryEnabled) builder.setNeutralButton(R.string.recover_password) { _, _ -> recoveryOptions() }
        builder.show()
    }

    private fun generatePassword(onSet: () -> Unit) {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
        val random = java.security.SecureRandom()
        val password = (1..32).map { chars[random.nextInt(chars.length)] }.joinToString("")
        AppDialog(context)
            .setTitle(R.string.catastrophic_warning)
            .setMessage(context.getString(R.string.generated_password_warning, password))
            .setPositiveButton(R.string.proceed) { _, _ -> savePassword(password, onSet) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Getting the password back ---

    private fun recoveryOptions() {
        val context = context
        val prefs = prefs
        val ways = mutableListOf<Pair<String, () -> Unit>>()
        if (prefs.recoveryTap500) ways += context.getString(R.string.recovery_tap_500) to { context.showToast(context.getString(R.string.recovery_tap_500_info)) }
        if (prefs.recoveryClue) ways += context.getString(R.string.recovery_clue) to {
            AppDialog(context).setTitle(R.string.your_clue).setMessage(prefs.recoveryClueText).setPositiveButton(R.string.okay, null).show()
        }
        if (prefs.recoveryEssay) ways += context.getString(R.string.recovery_essay) to { essay() }
        if (ways.isEmpty()) return context.showToast(context.getString(R.string.no_recovery_methods))
        AppDialog(context)
            .setTitle(R.string.recover_password)
            .setItems(ways.map { it.first }.toTypedArray()) { _, which -> ways[which].second() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun essay() {
        val input = EditText(context).apply {
            hint = context.getString(R.string.essay_prompt)
            maxLines = 10
            // No pasting it in
            customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = true.also { menu?.removeItem(android.R.id.paste) }
                override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = true.also { menu?.removeItem(android.R.id.paste) }
                override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
                override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
            }
        }
        AppDialog(context)
            .setTitle(R.string.recovery_essay)
            .setView(padded(context, input))
            .setPositiveButton(R.string.okay) { _, _ ->
                val text = input.text.toString()
                if (text.length >= 500) showPassword() else context.showToast(context.getString(R.string.essay_too_short, text.length))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showPassword() {
        val password = prefs.lockdownPasswordPlaintext.ifEmpty { return }
        AppDialog(context)
            .setTitle(R.string.your_password_is)
            .setView(padded(context, TextView(context).apply {
                text = password
                textSize = 20f
                setTextIsSelectable(true)
            }))
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    companion object {

        /** [view] with a dialog's margins, for a dialog's own view */
        fun padded(context: Context, view: View) = FrameLayout(context).apply {
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(view)
        }

        /** A time of day, with the day when it isn't today */
        fun time(context: Context, epochMs: Long): String {
            val at = Lockdown.localTime(epochMs)
            // Tonight's midnight is "midnight", not tomorrow's 00:00
            if (at.toLocalTime() == java.time.LocalTime.MIDNIGHT && at.toLocalDate() == LocalDate.now().plusDays(1)) return context.getString(R.string.lockdown_midnight)
            val clock = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            val pattern = if (at.toLocalDate() == LocalDate.now()) clock else "EEE $clock"
            return at.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }

        /** Minutes and hours, rounded up to the minute */
        fun duration(context: Context, ms: Long): String {
            val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours == 0L -> context.getString(R.string.minutes_short, rest.toInt())
                rest == 0L -> context.getString(R.string.hours_short, hours.toInt())
                else -> context.getString(R.string.hours_short, hours.toInt()) + " " + context.getString(R.string.minutes_short, rest.toInt())
            }
        }

        /** Why something can't be done right now */
        fun lockedMessage(context: Context): String {
            val prefs = Prefs(context)
            return Lockdown.activeUntil(context, prefs)?.let { context.getString(R.string.lockdown_locked_until, time(context, it)) }
                ?: context.getString(R.string.lockdown_locked)
        }

        /** The block screen's reason line */
        /** The block screen's headline: "Blocked until 22:00", "Blocked until midnight", "Daily limit reached" */
        fun reasonText(context: Context, block: Lockdown.Block): String = when (block.reason) {
            Lockdown.Reason.TIME_LIMIT, Lockdown.Reason.OPENS_LIMIT -> context.getString(R.string.lockdown_reason_limit)
            Lockdown.Reason.NOT_ALLOWED -> block.until?.let { context.getString(R.string.lockdown_reason_not_allowed_until, endName(context, it)) }
                ?: context.getString(R.string.lockdown_reason_not_allowed)
            Lockdown.Reason.BLOCKED, Lockdown.Reason.STAGE -> block.until?.let { context.getString(R.string.lockdown_reason_blocked_until, endName(context, it)) }
                ?: context.getString(R.string.lockdown_reason_blocked)
        }

        /** "midnight", or the time */
        private fun endName(context: Context, until: Long) = time(context, until)

        /** The block screen's detail line: what was used, and when it ends */
        fun detailText(context: Context, block: Lockdown.Block, pkg: String?): String {
            val prefs = Prefs(context)
            // The end is in the headline, except for limits
            val ends = when (block.reason) {
                Lockdown.Reason.TIME_LIMIT, Lockdown.Reason.OPENS_LIMIT -> context.getString(R.string.lockdown_resets_midnight)
                else -> if (block.until == null) context.getString(R.string.lockdown_until_turned_off) else null
            }
            val used = when (block.reason) {
                Lockdown.Reason.TIME_LIMIT -> pkg?.let { context.getString(R.string.lockdown_used, duration(context, Limits.rule(prefs, it).minutes * 60_000L)) }
                Lockdown.Reason.OPENS_LIMIT -> pkg?.let { val n = Limits.rule(prefs, it).opens
                    context.resources.getQuantityString(R.plurals.lockdown_opens, n, n) }
                Lockdown.Reason.STAGE -> context.getString(R.string.lockdown_screen_time_used, duration(context, Limits.totalToday(context)))
                else -> null
            }
            return listOfNotNull(used, ends).joinToString(" · ")
        }

        /** Why lockdown is on, in a few words, for under the status */
        fun whyText(context: Context): String? {
            val prefs = Prefs(context)
            return when (Lockdown.why(context, prefs)) {
                Lockdown.Why.MANUAL -> context.getString(R.string.lockdown_why_manual)
                Lockdown.Why.TIMER -> context.getString(R.string.lockdown_why_timer)
                Lockdown.Why.SCHEDULE -> Lockdown.schedules(prefs).firstOrNull { it.covers(java.time.LocalDateTime.now()) }
                    ?.let { context.getString(R.string.lockdown_why_schedule, scheduleName(context, it)) }
                Lockdown.Why.PLACE -> placeName(context, Social.currentPlace(context))?.let { context.getString(R.string.lockdown_why_place, it) }
                Lockdown.Why.STAGE -> context.getString(R.string.lockdown_why_stage)
                Lockdown.Why.SCREEN_TIME -> context.getString(R.string.lockdown_why_screen_time)
                Lockdown.Why.OTHER_DEVICE -> context.getString(R.string.lockdown_why_matched)
                null -> null
            }
        }

        /** A duration, rounded down to the minute, with 0 for none */
        fun durationOrNone(context: Context, ms: Long): String =
            if (ms < 60_000L) context.getString(R.string.minutes_short, 0) else duration(context, ms / 60_000L * 60_000L)

        /** "0:42" or "1:05:00", for a countdown */
        fun clockText(ms: Long): String {
            val s = ((ms + 999) / 1000).coerceAtLeast(0)
            return if (s >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
        }

        /** Why a break can't be taken now */
        fun cantBreakText(context: Context, can: Breaks.Can): String = when (can) {
            Breaks.Can.Yes -> ""
            Breaks.Can.Off -> context.getString(R.string.lockdown_breaks_off)
            Breaks.Can.NotLocked -> context.getString(R.string.lockdown_break_not_locked)
            is Breaks.Can.OnBreak -> context.getString(R.string.lockdown_break_until, time(context, can.until))
            is Breaks.Can.Cooldown -> context.getString(R.string.lockdown_break_next, time(context, can.until))
            is Breaks.Can.OffNow -> context.getString(R.string.lockdown_break_not_now, time(context, can.until))
            is Breaks.Can.Limit -> context.getString(when (can.period) {
                "hour" -> R.string.lockdown_break_limit_hour
                "day" -> R.string.lockdown_break_limit_day
                else -> R.string.lockdown_break_limit_week
            }, can.count) + " · " + context.getString(R.string.lockdown_break_next, time(context, can.until))
        }

        /** A device's name, or the devices': "Phone", "Phone and TV", "All devices" */
        fun deviceName(context: Context, devices: Int): String {
            fun name(device: Lockdown.Device) = context.getString(when (device) {
                Lockdown.Device.PHONE -> R.string.lockdown_device_phone
                Lockdown.Device.MAC -> R.string.lockdown_device_mac
                Lockdown.Device.TV -> R.string.lockdown_device_tv
            })
            val chosen = Lockdown.Device.values().filter { Lockdown.covers(devices, it) }
            return when (chosen.size) {
                0, 1 -> name(chosen.firstOrNull() ?: Lockdown.Device.PHONE)
                2 -> context.getString(R.string.list_and, name(chosen[0]), name(chosen[1]))
                else -> context.getString(R.string.lockdown_device_all)
            }
        }

        /** One device's state in a few words: "On until 07:00", "Break until 22:05", "Off" */
        fun deviceStatus(context: Context, device: Lockdown.Device): String {
            val prefs = Prefs(context)
            if (!Lockdown.isOn(context, prefs, device)) return context.getString(R.string.off)
            if (Lockdown.onBreak(prefs, device)) return context.getString(R.string.lockdown_break_until, time(context, Lockdown.breakUntil(prefs, device)))
            return Lockdown.activeUntil(context, prefs, device)?.let { context.getString(R.string.lockdown_on_until, time(context, it)) }
                ?: context.getString(R.string.on)
        }

        /** Today's screen time against the next stage, and how far along as a percent of the last stage; null without stages */
        fun screenTimeLine(context: Context): Pair<String, Int?>? {
            val stages = Limits.stages(Prefs(context))
            val last = stages.lastOrNull { it > 0 } ?: return null
            val used = Limits.totalToday(context)
            val next = Limits.nextStage(stages, used)
            val text = if (next == null) context.getString(R.string.lockdown_screen_time, duration(context, used))
                else context.getString(R.string.lockdown_screen_time_next, duration(context, used), stageShort(context, next.first),
                    duration(context, next.second * 60_000L))
            return text to (used * 100 / (last * 60_000L)).toInt().coerceIn(0, 100)
        }

        fun stageName(context: Context, stage: Int): String = context.getString(when (stage) {
            1 -> R.string.lockdown_stage_slow
            2 -> R.string.lockdown_stage_block
            else -> R.string.lockdown_stage_lockdown
        })

        /** A stage's one-word name, under its mark on the bar */
        fun stageShort(context: Context, stage: Int): String = context.getString(when (stage) {
            1 -> R.string.lockdown_stage_slow_short
            2 -> R.string.lockdown_stage_block_short
            else -> R.string.lockdown_stage_lockdown_short
        })

        /** The notification's title */
        fun notificationTitle(context: Context): String {
            val prefs = Prefs(context)
            if (Lockdown.onBreak(prefs, Lockdown.Device.PHONE))
                return context.getString(R.string.lockdown_break_until, time(context, Lockdown.breakUntil(prefs, Lockdown.Device.PHONE)))
            return Lockdown.activeUntil(context)?.let { context.getString(R.string.lockdown_on_until_title, time(context, it)) }
                ?: context.getString(R.string.lockdown_on_title)
        }

        /** A place's name from its id in [Social]: a home, work, or a named place */
        fun placeName(context: Context, id: Int): String? = when {
            id in 0 until Social.HOMES -> Social.homes(context).getOrNull(id)?.name
            id == Social.WORK -> Social.work(context)?.name
            Social.isNamed(id) -> Places.place(context, id)?.name
            else -> null
        }

        /** "Weekdays 18:00–22:00" */
        fun scheduleName(context: Context, schedule: Lockdown.Schedule): String {
            val days = when (schedule.days) {
                Lockdown.EVERY_DAY -> context.getString(R.string.lockdown_every_day)
                Lockdown.WEEKDAYS -> context.getString(R.string.lockdown_weekdays)
                Lockdown.WEEKENDS -> context.getString(R.string.lockdown_weekends)
                else -> java.time.DayOfWeek.values().filter { schedule.on(it) }
                    .joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }
            }
            if (schedule.start == schedule.end) return days + " " + context.getString(R.string.lockdown_all_day).lowercase(Locale.getDefault())
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            fun at(minutes: Int) = java.time.LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
            return days + " " + at(schedule.start) + "–" + at(schedule.end)
        }

        /** Says [message] in lockdown's sheet, or a toast when the accessibility service isn't on */
        fun locked(context: Context, message: String) {
            if (!app.olauncher.helper.MyAccessibilityService.showLocked(message)) context.showToast(message)
        }

        /** Says it's locked when [lock] is, and returns whether it is */
        fun stopIfLocked(context: Context, lock: Lockdown.Lock): Boolean {
            if (!Lockdown.isLocked(context, lock)) return false
            locked(context, lockedMessage(context))
            return true
        }

        /** Lockdown's state in a few words */
        fun status(context: Context): String {
            val prefs = Prefs(context)
            if (!Lockdown.isOn(context, prefs)) {
                Lockdown.deviceList(context).firstOrNull { it != Lockdown.Device.PHONE && Lockdown.isOn(context, prefs, it) }?.let {
                    return context.getString(R.string.lockdown_device_only, deviceName(context, it.bit), deviceStatus(context, it))
                }
                return Lockdown.nextScheduled(prefs)?.let { context.getString(R.string.lockdown_off_next, time(context, it)) }
                    ?: context.getString(R.string.off)
            }
            if (Lockdown.onBreak(prefs, Lockdown.Device.PHONE)) return deviceStatus(context, Lockdown.Device.PHONE)
            val on = Lockdown.activeUntil(context, prefs)?.let { context.getString(R.string.lockdown_on_until, time(context, it)) }
                ?: context.getString(R.string.on)
            if (Lockdown.timedActive(context, prefs) || Lockdown.challenge(prefs).type != Challenge.Type.WAIT) return on
            return when (val wait = Lockdown.waitState(context, prefs)) {
                is Lockdown.WaitState.Waiting -> on + " · " + context.getString(R.string.lockdown_unlocks_in, duration(context, wait.msLeft))
                is Lockdown.WaitState.Ready -> on + " · " + context.getString(R.string.lockdown_unlocked_for, duration(context, wait.msLeft))
                else -> on
            }
        }
    }
}
