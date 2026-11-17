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
import app.olauncher.helper.Lockdown
import app.olauncher.helper.PasswordHelper
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
        Lockdown.timedEnd(context, prefs)?.let { end ->
            return context.showToast(context.getString(R.string.lockdown_locked_until, time(context, end)))
        }
        when (Lockdown.unlock(prefs)) {
            Lockdown.Unlock.PASSWORD -> requirePassword(action)
            Lockdown.Unlock.WAIT -> askWait()
            Lockdown.Unlock.NOTHING -> action()
        }
    }

    // --- Waiting ---

    private fun askWait() {
        val prefs = prefs
        when (val state = Lockdown.waitState(context, prefs)) {
            is Lockdown.WaitState.Waiting -> AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.lockdown_unlocks_in, duration(context, state.msLeft)))
                .setPositiveButton(R.string.okay, null)
                .setNegativeButton(R.string.lockdown_stop_waiting) { _, _ ->
                    Lockdown.cancelWait(prefs)
                    onChanged()
                }
                .show()
            else -> AlertDialog.Builder(context)
                .setTitle(R.string.lockdown_wait_title)
                .setMessage(context.getString(R.string.lockdown_wait_message, duration(context, prefs.lockdownWaitMinutes * 60_000L),
                    duration(context, Lockdown.READY_MS)))
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
            AlertDialog.Builder(context)
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
        val builder = AlertDialog.Builder(context)
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
        AlertDialog.Builder(context)
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
            AlertDialog.Builder(context).setTitle(R.string.your_clue).setMessage(prefs.recoveryClueText).setPositiveButton(R.string.okay, null).show()
        }
        if (prefs.recoveryEssay) ways += context.getString(R.string.recovery_essay) to { essay() }
        if (ways.isEmpty()) return context.showToast(context.getString(R.string.no_recovery_methods))
        AlertDialog.Builder(context)
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
        AlertDialog.Builder(context)
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
        AlertDialog.Builder(context)
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

        /** Why [appLabel] didn't open */
        fun blockedMessage(context: Context, appLabel: String): String =
            Lockdown.activeUntil(context)?.let { context.getString(R.string.lockdown_blocked_until, appLabel, time(context, it)) }
                ?: context.getString(R.string.lockdown_blocked, appLabel)

        /** Says it's locked when [lock] is, and returns whether it is */
        fun stopIfLocked(context: Context, lock: Lockdown.Lock): Boolean {
            if (!Lockdown.isLocked(context, lock)) return false
            context.showToast(lockedMessage(context))
            return true
        }

        /** Lockdown's state in a few words */
        fun status(context: Context): String {
            val prefs = Prefs(context)
            if (!Lockdown.isActive(context, prefs)) {
                return Lockdown.nextScheduled(prefs)?.let { context.getString(R.string.lockdown_off_next, time(context, it)) }
                    ?: context.getString(R.string.off)
            }
            val on = Lockdown.activeUntil(context, prefs)?.let { context.getString(R.string.lockdown_on_until, time(context, it)) }
                ?: context.getString(R.string.on)
            if (Lockdown.timedActive(context, prefs) || Lockdown.unlock(prefs) != Lockdown.Unlock.WAIT) return on
            return when (val wait = Lockdown.waitState(context, prefs)) {
                is Lockdown.WaitState.Waiting -> on + " · " + context.getString(R.string.lockdown_unlocks_in, duration(context, wait.msLeft))
                is Lockdown.WaitState.Ready -> on + " · " + context.getString(R.string.lockdown_unlocked_for, duration(context, wait.msLeft))
                else -> on
            }
        }
    }
}
