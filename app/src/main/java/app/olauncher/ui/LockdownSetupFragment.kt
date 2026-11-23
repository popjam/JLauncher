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
 * Lockdown's setup, one step at a time, with the Lockdown page's names, order and rows: Blocks,
 * Turns on, Unlock, then the permissions it needs, and Ready, a summary. Shown the first time
 * Lockdown is opened with nothing set, and from "Set up" on the Lockdown page. Each step changes the
 * same settings as the page, straight away; anything that loosens lockdown goes through the unlock as
 * it does there.
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
            0 -> blocksStep()
            1 -> stepCard(R.string.lockdown_turns_on).rows(turnsOnRows())
            2 -> stepCard(R.string.lockdown_unlock).rows(unlockRows())
            3 -> permissionsStep()
            else -> readyStep()
        }
        buttons()
    }

    private fun stepCard(title: Int) = section(getString(title), getString(R.string.n_of, step + 1, STEPS))

    // --- 1. Blocks ---

    private fun blocksStep() {
        val context = requireContext()
        stepCard(R.string.lockdown_blocks).rows(blocksRows())
        if (prefs.lockdownAppMode == Lockdown.ALLOW) return
        // The usual time sinks that are on this phone, filled in when blocked
        val all = apps ?: return
        val blocked = prefs.lockdownBlockedApps
        val suggested = all.filter { Lockdown.canBlock(context, it.appPackage) && (it.appPackage in Limits.APP_SITES || it.appPackage in blocked) }
        if (suggested.isEmpty()) return
        val card = section(getString(R.string.lockdown_blocked_count_title, all.count { it.appPackage in blocked }))
        card.add(chips(suggested.map { app ->
            val on = app.appPackage in blocked
            chip(app.appLabel, on = on) {
                if (on) gate.loosen { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps - app.appPackage; render() }
                else { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps + app.appPackage; render() }
            }
        }))
    }

    // --- 4. Permissions ---

    private fun permissionsStep() {
        val context = requireContext()
        val canGrey = LockdownSystem.canGrayscale(context)
        stepCard(R.string.lockdown_permissions).rows(Rows(listOf(
            row(getString(R.string.accessibility), onOff(isAccessServiceEnabled(context)), R.string.info_lockdown_accessibility) {
                settings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            row(getString(R.string.lockdown_usage_access), onOff(context.appUsagePermissionGranted()), R.string.info_usage_access) {
                settings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            row(getString(R.string.lockdown_notification), onOff(LockdownSystem.canNotify(context)), R.string.info_lockdown_notification) {
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
            row(getString(R.string.lockdown_greyscale), getString(if (canGrey) R.string.lockdown_ready else R.string.lockdown_needs_setup),
                R.string.info_lockdown_greyscale) { if (!canGrey) greyscaleSetup() },
        )))
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
        stepCard(R.string.lockdown_ready).rows(Rows(listOf(
            row(getString(R.string.lockdown_blocks), modeName() + " · " + chosen, null) { go(0) },
            row(getString(R.string.lockdown_turns_on), turnsOnValue(), null) { go(1) },
            row(getString(R.string.lockdown_unlock), unlockValue(), null) { go(2) },
            row(getString(R.string.lockdown_permissions), if (off == 0) getString(R.string.lockdown_all_on) else getString(R.string.lockdown_n_off, off), null) { go(3) },
        )))
    }

    // --- Moving between steps ---

    private fun buttons() {
        val card = untitled()
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
        card.add(android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            pills.forEach { addView(it) }
        })
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
            Lockdown.schedules(prefs).isEmpty() && Limits.rules(prefs).isEmpty() && !prefs.lockdownModeOn
    }
}
