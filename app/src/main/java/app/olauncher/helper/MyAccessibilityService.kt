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
import app.olauncher.ui.LockdownGate
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
        main.postDelayed(recheck, RECHECK_MS)
    }

    /**
     * Lockdown can start by itself (a schedule, a stage) and limits run out while an app is open, and
     * that app may not open another window for a long time, so the app on screen is checked again now
     * and then. Grayscale and the notification are kept up to date at the same time.
     */
    private val recheck = object : Runnable {
        override fun run() {
            val service = this@MyAccessibilityService
            Limits.refresh(service)
            checkSystemOnTop()
            foregroundPackage?.takeIf { it != packageName && blockView == null && !systemOnTop }?.let { pkg ->
                val prefs = Prefs(service)
                val block = Lockdown.blockFor(service, pkg, opening = false, prefs)
                if (block != null) {
                    removeGate()
                    showBlock(appLabel(pkg), block, pkg)
                } else warnIfLow(pkg, prefs)
            }
            LockdownSystem.sync(service)
            main.postDelayed(this, RECHECK_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onWindowChanged(event)
                guardSoon(event)
                siteSoon(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                guardSoon(event)
                siteSoon(event)
            }
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
            systemOnTop = false
            AppPause.onHomeShown()
            removeGate()
            removeBlock()
            return
        }
        if (pkg == SYSTEM_UI || pkg in keyboardPackages) return
        if (!Lockdown.canBlock(this, pkg)) {
            // A system screen (a call, a permission dialog, the share sheet, recents) isn't an app: it's
            // never covered, and going back to the app from it isn't a new open. An app that was blocked
            // is blocked again when it comes back.
            blockPackage?.let { coveredBefore = it }
            systemOnTop = true
            removeBlock()
            if (gatePackage != null) removeGate()
            return
        }
        val opening = pkg != foregroundPackage || pkg == coveredBefore
        coveredBefore = null
        systemOnTop = false
        foregroundPackage = pkg
        // The block screen is already over this app
        if (blockPackage == pkg) return
        removeBlock()
        val prefs = Prefs(this)
        Lockdown.blockFor(this, pkg, opening, prefs)?.let { block ->
            removeGate()
            showBlock(appLabel(pkg), block, pkg)
            return
        }
        if (opening && Limits.rule(prefs, pkg).opens > 0) Limits.countOpen(this, pkg)
        warnIfLow(pkg, prefs)
        // Left for another app (or recents) without choosing
        if (gatePackage != null && gatePackage != pkg) removeGate()
        if (gatePackage == null && AppPause.needsGate(this, prefs, pkg)) showGate(pkg)
    }

    // --- Lockdown ---

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Says once a day when an app has only a few minutes of its limit left */
    private fun warnIfLow(pkg: String, prefs: Prefs) {
        val rule = Limits.rule(prefs, pkg)
        if (!Limits.shouldWarn(rule, Limits.usedToday(this, pkg), Limits.warnedToday(this, pkg))) return
        Limits.setWarned(this, pkg)
        val left = rule.minutes * 60_000L - Limits.usedToday(this, pkg)
        showToast(getString(R.string.lockdown_left_today, appLabel(pkg), LockdownGate.duration(this, left)))
    }

    // The block screen: over a blocked app or site, with why and until when, and Close
    private var blockView: View? = null
    private var blockPackage: String? = null
    /** The app the block screen was over when a system screen came up */
    private var coveredBefore: String? = null
    /** A system screen (a call, a permission dialog) is in front of the app, so nothing is put over it */
    private var systemOnTop = false

    /**
     * Closing a dialog such as the share sheet gives the app back its focus without a new window, so
     * whether a system screen is still in front is checked against the active window.
     */
    private fun checkSystemOnTop() {
        if (!systemOnTop) return
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return
        if (active == SYSTEM_UI || active in keyboardPackages) return
        if (active == packageName || Lockdown.canBlock(this, active)) {
            systemOnTop = false
            foregroundPackage = active
            // Back to the app the block screen was over: it's checked as an open, as when its window comes back
            if (active == coveredBefore) {
                coveredBefore = null
                Lockdown.blockFor(this, active, opening = true)?.let { block ->
                    removeGate()
                    showBlock(appLabel(active), block, active)
                }
            }
        }
    }
    private var lastCounted: String? = null
    private var lastCountedAt = 0L

    private fun showBlock(name: String, block: Lockdown.Block, pkg: String?) {
        removeBlock()
        // One attempt, not one for every window the app opens
        val key = pkg ?: name
        val now = android.os.SystemClock.uptimeMillis()
        if (key != lastCounted || now - lastCountedAt > 5_000) Lockdown.countBlocked(this)
        lastCounted = key
        lastCountedAt = now
        try {
            val context = themedContext()
            val root = object : FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                    if (event.action == KeyEvent.ACTION_UP) closeBlock()
                    return true
                }
            }
            root.setBackgroundColor(Color.BLACK)
            root.addView(BlockScreen.view(context, name, LockdownGate.reasonText(this, block), LockdownGate.detailText(this, block, pkg)) { closeBlock() },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, params)
            blockView = root
            holdAudio()
            blockPackage = pkg ?: foregroundPackage
        } catch (e: Exception) {
            e.printStackTrace()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun closeBlock() {
        removeBlock()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun removeBlock() {
        val view = blockView ?: return
        blockView = null
        blockPackage = null
        releaseAudio()
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    // Taking audio focus while the block screen shows stops a blocked app's video or music playing underneath
    private var audioFocus: Any? = null

    private fun holdAudio() {
        if (audioFocus != null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).requestAudioFocus(request) }
        audioFocus = request
    }

    private fun releaseAudio() {
        val request = audioFocus ?: return
        audioFocus = null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        runCatching { (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(request as android.media.AudioFocusRequest) }
    }

    /** Shows the block screen from the launcher, for an app it was asked to open */
    private fun blockFromLauncher(name: String, block: Lockdown.Block, pkg: String) {
        removeGate()
        showBlock(name, block, pkg)
        // So leaving the launcher for the app (if it opens anyway) doesn't count as a new open
        blockPackage = pkg
    }

    // Websites: the address in Chrome or Firefox, once the page settles
    private val siteCheck = Runnable { checkSite() }

    private fun siteSoon(event: AccessibilityEvent) {
        if (event.packageName?.toString() !in BROWSERS) return
        main.removeCallbacks(siteCheck)
        main.postDelayed(siteCheck, 400)
    }

    private fun checkSite() {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val ids = BROWSERS[root.packageName?.toString()] ?: return
        val bar = ids.asSequence().flatMap { runCatching { root.findAccessibilityNodeInfosByViewId(it) }.getOrNull().orEmpty().asSequence() }.firstOrNull()
            ?: return
        // Not while an address is being typed
        if (bar.isFocused) return
        val host = Limits.host(bar.text?.toString() ?: return) ?: return
        if (blockView != null) return
        Lockdown.blockForSite(this, host)?.let { showBlock(host, it, null) }
    }

    private val guard = Runnable { guardNow() }

    /**
     * When the settings or an installer changes what it shows, checks it once things settle, since
     * pages change many times as they draw.
     */
    private fun guardSoon(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in SETTINGS_PACKAGES && pkg !in INSTALLER_PACKAGES) return
        main.removeCallbacks(guard)
        main.postDelayed(guard, 250)
    }

    /**
     * While lockdown protects JLauncher, leaves its app info, accessibility and uninstall pages; while
     * it locks system settings, leaves the pages that could undo it (Date & time and the like).
     */
    private fun guardNow() {
        val protect = Lockdown.isLocked(this, Lockdown.Lock.PROTECT)
        val system = Lockdown.isLocked(this, Lockdown.Lock.SYSTEM_SETTINGS)
        if (!protect && !system) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val pkg = root.packageName?.toString() ?: return
        val installer = pkg in INSTALLER_PACKAGES
        if (!installer && pkg !in SETTINGS_PACKAGES) return
        // JLauncher's own pages (app info, storage, accessibility, uninstall) could undo lockdown too, so either lock leaves them
        val leave = showsJLauncher(root, pkg, installer) || (system && !installer && showsSystemPage(root, pkg))
        if (!leave) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        showToast(LockdownGate.lockedMessage(this))
    }

    /** Whether a settings page's title is one of the pages that could undo lockdown */
    private fun showsSystemPage(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val titles = words("com.android.settings", listOf("date_and_time", "development_settings_title", "manage_device_admin",
            "high_power_apps", "home_app", "default_home_app_title", "usage_access_title", "usage_access"),
            "Date & time", "Developer options", "Device admin apps", "Battery optimization", "Battery optimisation", "Home app", "Default home app",
            "Usage access", "Device & app usage")
        if (pageTitles(root).any { title -> titles.any { it.equals(title, ignoreCase = true) } }) return true
        // The default apps pages belong to the permission controller, whose title isn't always on the page as text:
        // any of its pages naming the home app is left, which is the picker and the list it's chosen from
        if (pkg !in PERMISSION_PACKAGES) return false
        val home = words(pkg, listOf("role_home_label", "role_home_short_label", "home_app"), "Default home app", "Home app")
        return anyText(root) { text -> home.any { it.equals(text, ignoreCase = true) } }
    }

    /** Whether any text on the page matches [match] */
    private fun anyText(root: AccessibilityNodeInfo, match: (String) -> Boolean): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val node = queue.removeFirst()
            seen++
            if (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any { match(it.trim()) }) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    /** What a settings page shows as its title: the collapsing toolbar's or action bar's, and the first text on the page */
    private fun pageTitles(root: AccessibilityNodeInfo): List<String> {
        val found = mutableListOf<String>()
        var first: String? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val node = queue.removeFirst()
            seen++
            val id = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            if (id.endsWith("collapsing_toolbar")) node.contentDescription?.toString()?.trim()?.let { found += it }
            if ((id.endsWith("action_bar_title") || id.endsWith("toolbar_title")) && text.isNotEmpty()) found += text
            if (first == null && text.isNotEmpty()) first = text
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return found + listOfNotNull(first)
    }

    /**
     * Whether the page is about JLauncher: an installer asking to uninstall it (not to update it), or
     * a settings page with its name and either its accessibility description or an app info button
     * (Uninstall, Force stop).
     */
    private fun showsJLauncher(root: AccessibilityNodeInfo, pkg: String, installer: Boolean): Boolean {
        val label = appLabel(packageName)
        val description = getString(R.string.accessibility_service_description)
        val words = if (installer) words(pkg, listOf("uninstall_application_text", "uninstall_application_text_all_users",
            "uninstall_application_text_user", "uninstall_update_text", "uninstall_application_title"), "Uninstall")
        // App info's buttons, and its Storage page's, where clearing storage would wipe lockdown
        else words("com.android.settings", listOf("uninstall_text", "force_stop", "clear_user_data_text", "clear_cache_btn_text", "disable_text"),
            "Uninstall", "Force stop", "Disable", "Clear storage", "Clear data", "Clear cache")
        var named = false
        var aboutApp = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val node = queue.removeFirst()
            seen++
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).map { it.trim() }.forEach { text ->
                if (if (installer) text.contains(label) else text == label) named = true
                if (installer) {
                    if (words.any { text.contains(it, ignoreCase = true) }) aboutApp = true
                } else if (text == description || words.any { it.equals(text, ignoreCase = true) }) aboutApp = true
            }
            if (named && aboutApp) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private val words = HashMap<String, Pair<java.util.Locale, List<String>>>()

    /** The strings [names] as the app [pkg] words them in the phone's language, and [fallback] */
    private fun words(pkg: String, names: List<String>, vararg fallback: String): List<String> {
        val locale = resources.configuration.locales[0]
        words[pkg]?.takeIf { it.first == locale }?.let { return it.second }
        val found = runCatching {
            val res = packageManager.getResourcesForApplication(pkg)
            names.mapNotNull { name -> res.getIdentifier(name, "string", pkg).takeIf { it != 0 }?.let { res.getString(it) } }
        }.getOrDefault(emptyList())
            // Some are formats with the app's name in them; the words before it are enough
            .map { it.substringBefore("%").trim() }.filter { it.length >= 4 }
        val list = (found + fallback).distinct()
        words[pkg] = locale to list
        return list
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
                pauseSeconds = if (AppPause.pauses(this, prefs, pkg)) prefs.pauseSeconds else null,
                askUseFor = AppPause.asksUseFor(prefs, pkg),
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
        checkSystemOnTop()
        if (foregroundPackage != pkg || systemOnTop) return
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
        main.removeCallbacks(guard)
        main.removeCallbacks(recheck)
        main.removeCallbacks(siteCheck)
        removeBlock()
        removeGate()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacks(guard)
        main.removeCallbacks(recheck)
        main.removeCallbacks(siteCheck)
        removeBlock()
        removeGate()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val RECHECK_MS = 15_000L

        /** Where JLauncher can be turned off or uninstalled from */
        private val SETTINGS_PACKAGES = setOf("com.android.settings", "com.miui.securitycenter",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller")
        /** Where the default apps, the home app among them, are chosen */
        private val PERMISSION_PACKAGES = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
        /** Browsers whose address bar is read for blocked sites, with the bar's view ids */
        private val BROWSERS = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "org.mozilla.firefox:id/url_bar_title"),
        )

        private val INSTALLER_PACKAGES = setOf("com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller")

        // Runs in the launcher's process, so the launcher can reach it directly
        private var instanceRef: java.lang.ref.WeakReference<MyAccessibilityService>? = null
        private var instance: MyAccessibilityService?
            get() = instanceRef?.get()
            set(value) {
                instanceRef = value?.let { java.lang.ref.WeakReference(it) }
            }

        val isRunning get() = instance != null

        fun closeIfShowing(packageName: String) {
            instance?.timeUp(packageName)
        }

        /** Shows the block screen for an app the launcher was asked to open; false when the service isn't running */
        fun showBlock(name: String, block: Lockdown.Block, packageName: String): Boolean {
            val service = instance ?: return false
            service.blockFromLauncher(name, block, packageName)
            return true
        }
    }
}
