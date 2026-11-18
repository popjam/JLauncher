package app.olauncher.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.getAppsList
import app.olauncher.helper.isAccessServiceEnabled
import kotlinx.coroutines.launch

/**
 * Lockdown's setup, one step at a time: which apps, when, how it's unlocked, the permissions it
 * needs, then a summary. Shown the first time Lockdown is opened with nothing set, and from "Set up"
 * on the Lockdown page. Each step changes the same settings as the Lockdown page, straight away;
 * anything that loosens lockdown goes through the unlock as it does there.
 */
class LockdownSetupFragment : LockdownBaseFragment() {

    private var step = 0
    private var apps: List<AppModel.App>? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.lockdownNotification = granted
        render()
    }

    override fun title() = getString(R.string.lockdown_setup_title)
    override fun showIcon() = true

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
            0 -> appsStep()
            1 -> whenStep()
            2 -> unlockStep()
            3 -> permissionsStep()
            else -> readyStep()
        }
        buttons()
    }

    private fun stepCard(title: Int) = section(getString(title), getString(R.string.lockdown_step, step + 1, STEPS))

    // --- 1. Apps ---

    private fun appsStep() {
        val context = requireContext()
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val mode = stepCard(R.string.lockdown_apps)
        mode.add(chips(listOf(
            chip(getString(R.string.lockdown_block_these), on = !allow) { setMode(Lockdown.BLOCK) },
            chip(getString(R.string.lockdown_only_allow), on = allow) { setMode(Lockdown.ALLOW) },
        )))
        val all = apps ?: return
        if (allow) {
            val allowed = all.filter { it.appPackage in prefs.lockdownAllowedApps }
            val card = section(getString(R.string.lockdown_allowed_count_title, allowed.size))
            card.add(chips(allowed.map { app -> chip(app.appLabel, on = true) { open(LockdownPageFragment.ALLOWED) } }),
                row(card, getString(R.string.lockdown_choose_apps), null, R.string.info_allowed_apps) { open(LockdownPageFragment.ALLOWED) })
        } else {
            // The usual time sinks that are on this phone, ticked when blocked
            val blocked = prefs.lockdownBlockedApps
            val suggested = all.filter { Lockdown.canBlock(context, it.appPackage) && (it.appPackage in Limits.APP_SITES || it.appPackage in blocked) }
            val card = section(getString(R.string.lockdown_blocked_count_title, all.count { it.appPackage in blocked }))
            card.add(chips(suggested.map { app ->
                val on = app.appPackage in blocked
                chip(app.appLabel, on = on) {
                    if (on) gate.loosen { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps - app.appPackage; render() }
                    else { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps + app.appPackage; render() }
                }
            }), row(card, getString(R.string.lockdown_more_apps), null, R.string.info_blocked_apps) { open(LockdownPageFragment.BLOCKED) })
        }
    }

    /** As on the Lockdown page: the allowlist is stricter unless it allows a blocked app */
    private fun setMode(mode: Int) {
        if (mode == prefs.lockdownAppMode) return
        val change = { prefs.lockdownAppMode = mode; render() }
        val unblocks = (prefs.lockdownAllowedApps + prefs.lockdownEssentials).any { it in prefs.lockdownBlockedApps }
        if (mode == Lockdown.ALLOW && !unblocks) change() else gate.loosen(change)
    }

    // --- 2. When ---

    private fun whenStep() {
        val context = requireContext()
        val card = stepCard(R.string.lockdown_when)
        val schedules = Lockdown.schedules(prefs)
        card.add(chips(PRESETS.map { preset ->
            val on = preset in schedules
            chip(LockdownGate.scheduleName(context, preset), on = on) {
                if (on) gate.loosen { prefs.lockdownSchedules = Lockdown.formatSchedules(Lockdown.schedules(prefs) - preset); render() }
                else {
                    val save = { prefs.lockdownSchedules = Lockdown.formatSchedules(Lockdown.schedules(prefs) + preset); render() }
                    if (preset.covers(java.time.LocalDateTime.now()) && !Lockdown.isActive(context, prefs)) startsNow(save) else save()
                }
            }
        }))
        // Schedules made on the Lockdown page are listed too
        schedules.filter { it !in PRESETS }.forEach { card.add(row(card, LockdownGate.scheduleName(context, it), null, R.string.info_lockdown_schedules, onTap = null)) }
        val stages = Limits.stages(prefs)
        val on = stages.any { it > 0 }
        card.add(row(card, getString(R.string.lockdown_stages), if (on) stages.joinToString(" · ") { minutesName(it) } else getString(R.string.off),
            R.string.info_lockdown_stages) {
            if (on) open(LockdownPageFragment.STAGES)
            else { prefs.lockdownStages = Limits.formatStages(Limits.STAGE_DEFAULTS); render() }
        })
    }

    private fun startsNow(save: () -> Unit) {
        AppDialog(requireContext())
            .setTitle(R.string.lockdown_starts_now)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ -> save() }
            .show()
    }

    // --- 3. Unlock ---

    private fun unlockStep() {
        val card = stepCard(R.string.lockdown_unlock)
        card.add(row(card, getString(R.string.lockdown_unlock_with), unlockName(Lockdown.unlock(prefs)), R.string.info_lockdown_unlock_with) { pickUnlock() })
    }

    // --- 4. Permissions ---

    private fun permissionsStep() {
        val context = requireContext()
        val card = stepCard(R.string.lockdown_permissions)
        val canGrey = LockdownSystem.canGrayscale(context)
        card.add(
            row(card, getString(R.string.accessibility), onOff(isAccessServiceEnabled(context)), R.string.info_lockdown_accessibility) {
                settings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            row(card, getString(R.string.lockdown_usage_access), onOff(context.appUsagePermissionGranted()), R.string.info_usage_access) {
                settings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            row(card, getString(R.string.lockdown_notification), onOff(LockdownSystem.canNotify(context)), R.string.info_lockdown_notification) {
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
            row(card, getString(R.string.lockdown_grayscale), getString(if (canGrey) R.string.lockdown_ready else R.string.lockdown_needs_setup),
                R.string.info_lockdown_grayscale) { if (!canGrey) grayscaleSetup() },
        )
    }

    /** Whether notifications were asked for on this page, so a second tap after "Don't allow" goes to settings */
    private var asked = false

    private fun settings(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    /** How many of the permissions lockdown needs are off; grayscale is optional */
    private fun permissionsOff(): Int {
        val context = requireContext()
        return listOf(isAccessServiceEnabled(context), context.appUsagePermissionGranted(), LockdownSystem.canNotify(context)).count { !it }
    }

    // --- 5. Ready ---

    private fun readyStep() {
        val context = requireContext()
        val card = stepCard(R.string.lockdown_ready)
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val chosen = (if (allow) prefs.lockdownAllowedApps else prefs.lockdownBlockedApps).count { pkg -> apps?.any { it.appPackage == pkg } ?: true }
        val stages = Limits.stages(prefs)
        val times = Lockdown.schedules(prefs).map { LockdownGate.scheduleName(context, it) } +
            listOfNotNull(stages.takeIf { s -> s.any { it > 0 } }?.let { getString(R.string.lockdown_stages) })
        val off = permissionsOff()
        card.add(
            row(card, getString(R.string.lockdown_apps), getString(if (allow) R.string.lockdown_only_allow else R.string.lockdown_block_these) + " · " + chosen, null) { go(0) },
            row(card, getString(R.string.lockdown_when), times.joinToString(", ").ifEmpty { getString(R.string.lockdown_manual_only) }, null) { go(1) },
            row(card, getString(R.string.lockdown_unlock_with), unlockName(Lockdown.unlock(prefs)), null) { go(2) },
            row(card, getString(R.string.lockdown_permissions), if (off == 0) getString(R.string.lockdown_all_on) else getString(R.string.lockdown_n_off, off), null) { go(3) },
        )
    }

    // --- Moving between steps ---

    private fun buttons() {
        val card = section("")
        (card.titleView.parent as? View)?.visibility = View.GONE
        val last = step == STEPS - 1
        val pills = listOfNotNull(
            if (step > 0) pill(getString(R.string.back)) { go(step - 1) } else null,
            if (!last) pill(getString(R.string.lockdown_next)) { go(step + 1) } else null,
            if (last && !prefs.lockdownModeOn) pill(getString(R.string.lockdown_turn_on_now)) {
                prefs.lockdownModeOn = true
                finish()
            } else null,
            if (last) pill(getString(R.string.lockdown_finish)) { finish() } else null,
        )
        card.add(android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            pills.forEach { addView(it) }
        })
    }

    private fun go(to: Int) {
        step = to.coerceIn(0, STEPS - 1)
        render()
        (view as? android.view.ViewGroup)?.let { root -> root.findScroll()?.scrollTo(0, 0) }
    }

    private fun android.view.ViewGroup.findScroll(): android.widget.ScrollView? {
        if (this is android.widget.ScrollView) return this
        for (i in 0 until childCount) (getChildAt(i) as? android.view.ViewGroup)?.findScroll()?.let { return it }
        return null
    }

    private fun finish() {
        LockdownSystem.sync(requireContext())
        findNavController().popBackStack()
    }

    companion object {
        private const val STEP = "step"
        private const val STEPS = 5

        /** Ready-made schedules: every night, and the working day */
        private val PRESETS = listOf(
            Lockdown.Schedule(Lockdown.EVERY_DAY, 22 * 60, 7 * 60),
            Lockdown.Schedule(Lockdown.WEEKDAYS, 9 * 60, 17 * 60),
        )

        /** Whether nothing's been set up yet, so the setup is offered */
        fun isFresh(prefs: app.olauncher.data.Prefs) = prefs.lockdownBlockedApps.isEmpty() && prefs.lockdownAllowedApps.isEmpty() &&
            Lockdown.schedules(prefs).isEmpty() && Limits.stages(prefs).none { it > 0 } && Limits.rules(prefs).isEmpty() && !prefs.lockdownModeOn
    }
}
