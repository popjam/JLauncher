package app.olauncher.ui

import android.Manifest
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getAppsList
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import kotlinx.coroutines.launch

/**
 * Lockdown's setup, one step at a time, with the Lockdown page's names, order and rows: Blocks,
 * Turns on, Unlock, then the permissions it needs, and Ready, a summary. Shown the first time
 * Lockdown is opened with nothing set, and from "Run setup again" on the Lockdown page. Each step
 * changes the same settings as the page, straight away; anything that loosens lockdown goes through
 * the unlock as it does there.
 */
class LockdownSetupFragment : LockdownBaseFragment() {

    private var step = 0
    private var apps: List<AppModel.App>? = null

    /** A second tap after "Don't allow" goes to the system's page instead (see [permissionsStep]) */
    override val openSettingsWhenDenied = false

    override fun title() = getString(R.string.lockdown_setup_title)
    override fun icon() = StatIcons.LOCK

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt(STEP) ?: 0
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STEP, step)
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs.lockdownSetupOffered = true
        viewLifecycleOwner.lifecycleScope.launch {
            apps = getAppsList(requireContext(), prefs, includeRegularApps = true, includeHiddenApps = true)
                .filterIsInstance<AppModel.App>().distinctBy { it.appPackage }
            render()
        }
    }

    override fun build() {
        when (step) {
            0 -> blocksStep()
            1 -> show(stepLabel(R.string.lockdown_turns_on) + turnsOnRows())
            2 -> show(stepLabel(R.string.lockdown_unlock) + unlockRows())
            3 -> permissionsStep()
            else -> readyStep()
        }
        buttons()
    }

    /** The step's name and how far through it is: "Blocks · 1 of 5" */
    private fun stepLabel(title: Int) = Rows(SettingLabel(getString(title) + " · " + getString(R.string.n_of, step + 1, STEPS)))

    // --- 1. Blocks ---

    private fun blocksStep() {
        val context = requireContext()
        show(stepLabel(R.string.lockdown_blocks) + blocksRows())
        if (prefs.lockdownAppMode == Lockdown.ALLOW) return
        // The usual time sinks that are on this phone, filled in when blocked
        val all = apps ?: return
        val blocked = prefs.lockdownBlockedApps
        val suggested = all.filter { Lockdown.canBlock(context, it.appPackage) && (it.appPackage in Limits.APP_SITES || it.appPackage in blocked) }
        if (suggested.isEmpty()) return
        show(SettingLabel(getString(R.string.lockdown_blocked_count_title, all.count { it.appPackage in blocked })),
            SettingView(FlowLayout(context).apply {
                setPadding(0, 6.dpToPx(), 0, 8.dpToPx())
                suggested.forEach { app ->
                    val on = app.appPackage in blocked
                    addView(chip(app.appLabel, on) {
                        if (on) gate.loosen { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps - app.appPackage; render() }
                        else { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps + app.appPackage; render() }
                    })
                }
            }))
    }

    /** A chip: filled when [on] */
    private fun chip(text: String, on: Boolean, onTap: () -> Unit): TextView {
        val context = requireContext()
        return TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = Style.TEXT_SMALL
            setTextColor(if (on) context.getColorFromAttr(R.attr.primaryShadeDarkColor).let { androidx.core.graphics.ColorUtils.setAlphaComponent(it, 255) } else ink)
            background = GradientDrawable().apply {
                cornerRadius = Style.radiusSmall
                if (on) setColor(faint(230)) else setStroke(1.dpToPx(), faint(80))
            }
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            setOnClickListener { onTap() }
        }
    }

    // --- 4. Permissions ---

    private fun permissionsStep() {
        val context = requireContext()
        val canGrey = LockdownSystem.canGrayscale(context)
        show(stepLabel(R.string.lockdown_permissions) + Rows(
            row("accessibility", getString(R.string.accessibility), R.string.info_lockdown_accessibility, value = onOff(isAccessServiceEnabled(context))) {
                settings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            row("usage", getString(R.string.lockdown_usage_access), R.string.info_usage_access, value = onOff(context.appUsagePermissionGranted())) {
                settings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            row("notification", getString(R.string.lockdown_notification), R.string.info_lockdown_notification, value = onOff(LockdownSystem.canNotify(context))) {
                when {
                    LockdownSystem.canNotify(context) -> settings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not() && asked ->
                        settings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                        asked = true
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
            row("greyscale", getString(R.string.lockdown_greyscale), R.string.info_lockdown_greyscale,
                value = getString(if (canGrey) R.string.lockdown_ready else R.string.lockdown_needs_setup)) { if (!canGrey) greyscaleSetup() },
        ))
    }

    /** Whether notifications were asked for on this page, so a second tap after "Don't allow" goes to settings */
    private var asked = false

    private fun settings(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    /** How many of the permissions lockdown needs are off; greyscale is optional */
    private fun permissionsOff(): Int {
        val context = requireContext()
        return listOf(isAccessServiceEnabled(context), context.appUsagePermissionGranted(), LockdownSystem.canNotify(context)).count { !it }
    }

    // --- 5. Ready ---

    private fun readyStep() {
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val chosen = (if (allow) prefs.lockdownAllowedApps else prefs.lockdownBlockedApps).count { pkg -> apps?.any { it.appPackage == pkg } ?: true }
        val off = permissionsOff()
        show(stepLabel(R.string.lockdown_ready) + Rows(
            row("blocks", getString(R.string.lockdown_blocks), R.string.info_lockdown_blocks_section, value = modeName() + " · " + chosen, control = Control.Opens) { go(0) },
            row("turnsOn", getString(R.string.lockdown_turns_on), R.string.info_lockdown_turns_on, value = turnsOnValue(), control = Control.Opens) { go(1) },
            row("unlock", getString(R.string.lockdown_unlock), R.string.info_lockdown_unlock, value = unlockValue(), control = Control.Opens) { go(2) },
            row("permissions", getString(R.string.lockdown_permissions), R.string.info_lockdown_permissions, control = Control.Opens,
                value = if (off == 0) getString(R.string.lockdown_all_on) else getString(R.string.lockdown_n_off, off)) { go(3) },
        ))
    }

    // --- Moving between steps ---

    private fun buttons() {
        val last = step == STEPS - 1
        val pills = listOfNotNull(
            if (step > 0) pill(getString(R.string.back)) { go(step - 1) } else null,
            if (!last) pill(getString(R.string.next)) { go(step + 1) } else null,
            if (last && !prefs.lockdownModeOn) pill(getString(R.string.lockdown_turn_on_now)) {
                prefs.lockdownModeOn = true
                finish()
            } else null,
            if (last) pill(getString(R.string.okay)) { finish() } else null,
        )
        show(SettingView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20.dpToPx(), 0, 0)
            pills.forEach { addView(it) }
        }))
    }

    private fun go(to: Int) {
        holdLists.forEach { it.end() }
        step = to.coerceIn(0, STEPS - 1)
        render()
        scrollView()?.scrollTo(0, 0)
    }

    private fun finish() {
        LockdownSystem.sync(requireContext())
        findNavController().popBackStack()
    }

    companion object {
        private const val STEP = "step"
        private const val STEPS = 5

        /** Whether nothing's been set up yet, so the setup is offered */
        fun isFresh(prefs: app.olauncher.data.Prefs) = prefs.lockdownBlockedApps.isEmpty() && prefs.lockdownAllowedApps.isEmpty() &&
            Lockdown.schedules(prefs).isEmpty() && Limits.rules(prefs).values.all { it.isEmpty } && !prefs.lockdownModeOn
    }
}
