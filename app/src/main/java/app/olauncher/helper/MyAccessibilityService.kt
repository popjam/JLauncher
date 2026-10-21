package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentPauseBinding
import app.olauncher.ui.PauseScreen

/**
 * Handles the lock and recents gestures, and the wellness screen for apps opened from outside the
 * launcher (links, recent apps, notifications), which it covers with an overlay.
 */
class MyAccessibilityService : AccessibilityService() {

    private var gateView: View? = null
    private var gatePackage: String? = null
    private var gateScreen: PauseScreen? = null

    // The app on screen, ignoring windows that sit on top of it like the status bar and keyboard
    private var foregroundPackage: String? = null
    private var keyboardPackages = emptySet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        keyboardPackages = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.map { it.packageName }.toSet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (event.packageName == packageName) onLauncherClick(event)
        }
    }

    private fun onLauncherClick(event: AccessibilityEvent) {
        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            when (source.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                getString(R.string.recents_layout_description) -> {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }
        } catch (e: Exception) {
            return
        }
    }

    private fun onWindowChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            // Only the home screen counts, not the launcher's other windows such as the overlay
            if (event.className != MainActivity::class.java.name) return
            foregroundPackage = pkg
            AppPause.onHomeShown()
            removeGate()
            return
        }
        if (pkg == SYSTEM_UI || pkg in keyboardPackages) return
        foregroundPackage = pkg
        // Left for another app (or recents) without choosing
        if (gatePackage != null && gatePackage != pkg) removeGate()
        if (gatePackage == null && AppPause.needsGate(Prefs(this), pkg)) showGate(pkg)
    }

    // --- Wellness screen ---

    private fun showGate(pkg: String) {
        try {
            val prefs = Prefs(this)
            val context = themedContext()
            val root = object : FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                    if (event.action == KeyEvent.ACTION_UP) cancelGate()
                    return true
                }
            }
            val binding = FragmentPauseBinding.inflate(LayoutInflater.from(context), root, true)
            // Solid black, so none of the app shows through (the launcher's own shade is see-through)
            binding.root.setBackgroundColor(Color.BLACK)
            val screen = PauseScreen(
                binding,
                pkg,
                appLabel(pkg),
                pauseSeconds = if (prefs.pauseAppsOn) prefs.pauseSeconds else null,
                askUseFor = AppPause.useForActive(prefs),
                onOpen = { minutes ->
                    AppPause.grantPass(pkg, minutes)
                    removeGate()
                },
                onCancel = { cancelGate() },
            )
            // Focusable, so the back button reaches it instead of the app underneath
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, params)
            gateView = root
            gatePackage = pkg
            gateScreen = screen
            screen.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelGate() {
        removeGate()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun removeGate() {
        val view = gateView ?: return
        gateScreen?.stop()
        gateView = null
        gatePackage = null
        gateScreen = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Closes [pkg] if it's still on screen when its time runs out. */
    private fun timeUp(pkg: String) {
        if (foregroundPackage != pkg) return
        removeGate()
        performGlobalAction(GLOBAL_ACTION_HOME)
        showToast(getString(R.string.time_up, appLabel(pkg)))
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Always the dark theme, whatever the launcher uses, so the text reads on the black overlay. */
    private fun themedContext(): Context {
        val config = Configuration(resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        return ContextThemeWrapper(createConfigurationContext(config), R.style.AppTheme)
    }

    override fun onInterrupt() {

    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        removeGate()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeGate()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"

        // Runs in the launcher's process, so the launcher can reach it directly
        private var instance: MyAccessibilityService? = null

        val isRunning get() = instance != null

        fun closeIfShowing(packageName: String) {
            instance?.timeUp(packageName)
        }
    }
}
