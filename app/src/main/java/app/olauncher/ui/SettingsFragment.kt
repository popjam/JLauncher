package app.olauncher.ui

import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.BackupManager
import app.olauncher.data.Constants
import app.olauncher.data.MoodStore
import app.olauncher.data.Prefs
import app.olauncher.helper.AppUpdate
import app.olauncher.helper.Arrangement
import app.olauncher.helper.AutoStats
import app.olauncher.helper.Formats
import app.olauncher.helper.Lockdown
import app.olauncher.helper.People
import app.olauncher.helper.Places
import app.olauncher.helper.Social
import app.olauncher.helper.Spending
import app.olauncher.helper.SwipeTargets
import app.olauncher.helper.Tracking
import app.olauncher.helper.Weather
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * Settings: a list of categories, each a page of its own ([PAGE]), with a search over every page's
 * settings (Lockdown's included) above it. Lockdown is the first category and opens its own page.
 *
 * On a page: on/off settings are switches, two or three short choices a segmented control, four or
 * five a value tapped through, and anything else opens a list or a box to type in. A setting that
 * depends on another is indented under it, and greyed while that one's off. Holding any row explains
 * it. A row lockdown is blocking says Locked. Red rows (Reset, Delete, Import) are last on their page
 * and ask first.
 */
class SettingsFragment : LockdownBaseFragment() {

    companion object {
        /** Argument: the page to show, one of the keys below; none for the list of them */
        const val PAGE = "page"

        /** Argument: as [PAGE], from pages that open Settings at their own settings ([MONEY], [TRACKING]) */
        const val OPEN = "open"

        const val LOCKDOWN = "lockdown"
        const val HOME = "home"
        const val GESTURES = "gestures"
        const val APPS = "apps"
        const val CHECK_IN = "checkIn"
        const val MONEY = "money"
        const val PLACES = "places"
        const val TRACKING = "tracking"
        const val STATS = "stats"
        const val NOTES = "notes"
        const val NEWSLETTER = "newsletter"
        const val BACKUP = "backup"
        const val ABOUT = "about"

        /** Apps' list of hidden apps */
        const val HIDDEN_APPS = "hiddenApps"

        val CATEGORIES = listOf(LOCKDOWN, HOME, GESTURES, APPS, CHECK_IN, MONEY, PLACES, TRACKING, STATS, NOTES, NEWSLETTER, BACKUP, ABOUT)

        /** Which setting asked for the accessibility service, to turn on once it's allowed */
        const val ASKED_FOR_LOCK = "lock"
        const val ASKED_FOR_RECENTS = "recents"
    }

    private lateinit var viewModel: MainViewModel
    private val moodStore by lazy { MoodStore(requireContext()) }

    /** The page shown; null for the list of categories */
    private val page: String? get() = (arguments?.getString(PAGE) ?: arguments?.getString(OPEN))?.takeIf { it in CATEGORIES || it == HIDDEN_APPS }

    // --- Things picked or allowed elsewhere ---

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            prefs.weatherOn = true
            viewModel.getWeather(force = true)
        } else {
            requireContext().showToast(getString(R.string.weather_location_denied))
            if (!shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION))
                openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
        }
        render()
    }

    private val askCallLog = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val context = requireContext().applicationContext
        Thread { runCatching { People.refresh(context, force = true) } }.start()
        render()
    }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        // Drive can be slow, so off the main thread
        val context = requireContext().applicationContext
        Thread {
            val exported = runCatching { BackupManager.export(context, uri) }.getOrDefault(false)
            main.post { context.showToast(context.getString(if (exported) R.string.backup_exported else R.string.export_failed)) }
        }.start()
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        Thread {
            val backup = runCatching { BackupManager.read(context, uri) }.getOrNull()
            main.post {
                if (!isAdded) return@post
                if (backup == null) context.showToast(getString(R.string.import_failed)) else confirmImport(backup)
            }
        }.start()
    }

    private val pickBackupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val link = {
            BackupManager.setBackupFolder(requireContext(), uri)
            render()
            showRestoreSummary()
        }
        val context = requireContext().applicationContext
        Thread {
            val existing = runCatching { BackupManager.findBackupInFolder(context, uri)?.let { BackupManager.read(context, it) } }.getOrNull()
            main.post { if (isAdded) folderPicked(existing, link) }
        }.start()
    }

    /** A backup folder's been picked: use it, or first ask whether to restore the backup already in it */
    private fun folderPicked(existing: JSONObject?, link: () -> Unit) {
        if (existing == null) {
            link()
            return
        }
        // Don't silently overwrite either side: ask which to keep
        AppDialog(requireContext())
            .setTitle(R.string.backup_folder)
            .setMessage(getString(R.string.backup_found_message) + savedAt(existing))
            .setCancelable(false)
            .setPositiveButton(R.string.restore) { _, _ -> restoreBackup(existing, link) }
            .setNegativeButton(R.string.keep_current) { _, _ -> link() }
            .show()
    }

    /** "Saved on 3 Nov 2026, 09:12", after a blank line, or nothing when the backup doesn't say */
    private fun savedAt(backup: JSONObject) = BackupManager.savedAt(backup)?.let {
        "\n\n" + getString(R.string.backup_saved_on, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(it))
    }.orEmpty()

    private val createReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        val days = reportDays
        Thread {
            val ok = runCatching { Report.write(context, uri, days) }.isSuccess
            view?.post { if (isAdded) context.showToast(getString(if (ok) R.string.report_done else R.string.report_failed)) }
        }.start()
    }
    private var reportDays = 30

    // --- The page ---

    override fun title() = page?.let { pageTitle(it) } ?: getString(R.string.settings)

    override fun icon() = if (page == null) StatIcons.GEAR else null

    /** On the list of categories: the search box and the hint under it */
    override fun top(): View? {
        if (page != null) return null
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(box(context, R.string.settings_search_hint, { query = it; render() }).apply { setText(query) })
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                setText(R.string.settings_hold_hint)
                textSize = Style.TEXT_SMALL
                setTextColor(faint(115))
                setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            })
        }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View {
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.hiddenApps.observe(viewLifecycleOwner) {
            hiddenAppModels = it.orEmpty()
            render()
        }
    }

    override fun onResume() {
        if (page == null || page == ABOUT) checkUpdate()
        viewModel.getHiddenApps()
        // Permissions and services may have changed in the system's settings
        viewModel.isOlauncherDefault()
        finishAccessibilityAsk(requireContext(), Prefs(requireContext()))
        super.onResume()
        showRestoreSummary()
    }

    override val holdLists: List<HoldList<*>> get() = super.holdLists + hiddenList

    override fun build() {
        when (val shown = page) {
            null -> if (query.isBlank()) show(categoryRows()) else show(searchRows())
            else -> show(pageRows(shown))
        }
    }

    private fun pageTitle(key: String) = getString(when (key) {
        LOCKDOWN -> R.string.lockdown
        HOME -> R.string.home_screen
        GESTURES -> R.string.gestures
        APPS -> R.string.apps
        CHECK_IN -> R.string.check_in
        MONEY -> R.string.settings_spending
        PLACES -> R.string.places_title_short
        TRACKING -> R.string.tracking
        STATS -> R.string.stats_title
        NOTES -> R.string.settings_notes
        NEWSLETTER -> R.string.newsletter
        BACKUP -> R.string.backup
        HIDDEN_APPS -> R.string.hidden_apps
        else -> R.string.about
    })

    private fun pageAbout(key: String) = when (key) {
        LOCKDOWN -> R.string.info_lockdown
        HOME -> R.string.info_page_home
        GESTURES -> R.string.info_page_gestures
        APPS -> R.string.info_page_apps
        CHECK_IN -> R.string.info_page_check_in
        MONEY -> R.string.info_page_money
        PLACES -> R.string.info_page_places
        TRACKING -> R.string.info_page_tracking
        STATS -> R.string.info_page_stats
        NOTES -> R.string.info_page_notes
        NEWSLETTER -> R.string.info_page_newsletter
        BACKUP -> R.string.info_page_backup
        else -> R.string.info_page_about
    }

    private fun pageRows(key: String): Rows = when (key) {
        HOME -> homeRows()
        GESTURES -> gesturesRows()
        APPS -> appsRows()
        HIDDEN_APPS -> hiddenAppsRows()
        CHECK_IN -> checkInRows()
        MONEY -> moneyRows()
        PLACES -> placesRows()
        TRACKING -> trackingRows()
        STATS -> statsRows()
        NOTES -> notesRows()
        NEWSLETTER -> newsletterRows()
        BACKUP -> backupRows()
        ABOUT -> aboutRows()
        else -> lockdownRows()
    }

    /** Each category, opening its page; nothing on the right but › */
    private fun categoryRows() = Rows(CATEGORIES.map { key ->
        row("category.$key", pageTitle(key), pageAbout(key), control = Control.Opens) { openPage(key) }
    })

    /** A category's page, lighting up the row [highlight] if given; Lockdown's is a page of its own */
    private fun openPage(key: String, highlight: String? = null) {
        runCatching {
            if (key == LOCKDOWN) findNavController().navigate(R.id.lockdownFragment, bundleOf(HIGHLIGHT to highlight))
            else findNavController().navigate(R.id.settingsFragment, bundleOf(PAGE to key, HIGHLIGHT to highlight))
        }
    }

    /** Builds this page again from scratch, with the theme and text size as they are now, without leaving it */
    private fun rebuildPage() {
        val nav = findNavController()
        val id = nav.currentDestination?.id ?: return
        runCatching { nav.navigate(id, arguments, NavOptions.Builder().setPopUpTo(id, true).build()) }
    }

    /** Whether lockdown keeps JLauncher's own system pages (app info, accessibility, usage access) from opening now */
    private fun systemPagesLocked(): Boolean {
        val context = requireContext()
        return Lockdown.isLocked(context, Lockdown.Lock.PROTECT, prefs) || Lockdown.isLocked(context, Lockdown.Lock.SYSTEM_SETTINGS, prefs)
    }

    // --- Search ---

    private var query = ""

    /** A setting found by search: its row, the page it's on ("Lockdown › Screen time"), and how to open that page at it */
    private class Found(val row: SettingRow, val path: String, val open: (String) -> Unit)

    /** Every setting on every page, Lockdown's included */
    private fun everything(): List<Found> {
        val found = mutableListOf<Found>()
        fun add(rows: Rows, path: String, open: (String) -> Unit) = rows.items.filterIsInstance<SettingRow>().forEach { found += Found(it, path, open) }
        val lockdown = getString(R.string.lockdown)
        add(lockdownRows(), lockdown) { openPage(LOCKDOWN, it) }
        SECTIONS.forEach { section -> add(sectionRows(section), lockdown + " › " + sectionTitle(section)) { openSection(section, it) } }
        add(beforeOpeningRows(), listOf(lockdown, sectionTitle(SCREEN_TIME), getString(R.string.limits)).joinToString(" › ")) {
            open(LockdownPageFragment.LIMITS, highlight = it)
        }
        CATEGORIES.filter { it != LOCKDOWN }.forEach { key -> add(pageRows(key), pageTitle(key)) { openPage(key, it) } }
        return found
    }

    /**
     * The settings matching the search, by name, what holding them says, or their page's name, each
     * with its page under it. Switches work here; tapping anything else opens its page at it.
     */
    private fun searchRows(): Rows {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(text: String) = text.lowercase(Locale.getDefault()).let { t -> words.all { it in t } }
        val found = everything().filter { matches(it.row.title + "\n" + it.row.about + "\n" + it.path) }
        if (found.isEmpty()) return Rows(SettingView(note(getString(R.string.settings_search_none, query.trim())).apply { gravity = Gravity.CENTER }))
        return Rows(found.mapIndexed { i, f ->
            val row = f.row
            val control = row.control
            val base = row.copy(key = "found.$i", level = 0, note = f.path)
            when {
                control is Control.Switch && row.enabled -> base
                control is Control.Switch -> base.copy(control = Control.Opens, value = onOff(control.on), onTap = { f.open(row.key) })
                control is Control.Segment -> base.copy(control = Control.Opens, value = control.options.getOrNull(control.selected), onTap = { f.open(row.key) })
                control is Control.Cycle -> base.copy(control = Control.Opens, value = control.options.getOrNull(control.selected), onTap = { f.open(row.key) })
                else -> base.copy(control = Control.Opens, enabled = true, onTap = { f.open(row.key) })
            }
        })
    }

    // --- Home screen ---

    private fun homeRows(): Rows {
        val context = requireContext()
        val alignments = listOf(Gravity.START, Gravity.CENTER, Gravity.END)
        val clock = listOf(Constants.DateTime.ON, Constants.DateTime.DATE_ONLY, Constants.DateTime.OFF)
        val themes = listOf(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val screenTime = context.appUsagePermissionGranted()
        val screenTimeShown = screenTime && Arrangement.SCREEN_TIME !in Arrangement.hidden(context, Arrangement.HOME)
        val weather = Weather.isEnabled(context)
        val sizes = TEXT_SIZES.map { it.first } + if (isTablet(context)) listOf(1.6f, 2.0f) else emptyList()
        val size = sizes.indexOfFirst { Math.abs(it - prefs.textSizeScale) < 0.05f }
        return Rows(
            row("appsOnHome", getString(R.string.apps_on_home_screen), R.string.info_home_apps, value = prefs.homeAppsNum.toString()) {
                choose(getString(R.string.apps_on_home_screen), (0..8).map { it.toString() }, prefs.homeAppsNum) {
                    prefs.homeAppsNum = it
                    viewModel.refreshHome(true)
                    render()
                }
            },
            cycle("homePages", getString(R.string.home_pages), R.string.info_home_pages, (1..5).map { it.toString() }, prefs.homePages - 1) {
                prefs.homePages = it + 1
                if (prefs.homePage >= prefs.homePages) prefs.homePage = prefs.homePages - 1
                viewModel.refreshHome(true)
                render()
            },
            segment("clock", getString(R.string.clock), R.string.info_clock, listOf(R.string.on, R.string.date_only, R.string.off).map { getString(it) },
                clock.indexOf(prefs.dateTimeVisibility)) {
                prefs.dateTimeVisibility = clock[it]
                viewModel.toggleDateTime()
                render()
            },
            segment("alignment", getString(R.string.home_layout_alignment), R.string.info_alignment,
                listOf(R.string.left, R.string.center, R.string.right).map { getString(it) }, alignments.indexOf(prefs.homeAlignment)) {
                viewModel.updateHomeAlignment(alignments[it])
                render()
            },
            switch("bottom", getString(R.string.apps_at_bottom), R.string.info_bottom, prefs.homeBottomAlignment) {
                prefs.homeBottomAlignment = it
                viewModel.updateHomeAlignment(prefs.homeAlignment)
                render()
            },
            // Back to home, to drag them there
            row("moveWidgets", getString(R.string.move_widgets), R.string.info_move_widgets,
                value = if (Arrangement.widgetsAt(context) != null) getString(R.string.widgets_moved) else null, control = Control.Opens) {
                viewModel.moveHomeWidgets = true
                runCatching { findNavController().popBackStack(R.id.mainFragment, false) }
            },
            if (sizes.size <= 5) cycle("textSize", getString(R.string.text_size), R.string.info_text_size, sizes.map { sizeName(it) }, size) { setTextSize(sizes[it]) }
            else row("textSize", getString(R.string.text_size), R.string.info_text_size, value = sizeName(prefs.textSizeScale)) {
                choose(getString(R.string.text_size), sizes.map { sizeName(it) }, size) { setTextSize(sizes[it]) }
            },
            segment("theme", getString(R.string.theme_mode), R.string.info_theme, listOf(R.string.dark, R.string.light, R.string.system_default).map { getString(it) },
                themes.indexOf(prefs.appTheme)) { setTheme(themes[it]) },
            switch("statusBar", getString(R.string.notification_bar), R.string.info_status_bar, prefs.showStatusBar) {
                prefs.showStatusBar = it
                showStatusBar(it)
                render()
            },
            // Off and not allowed yet: turning it on asks for usage access, which lockdown may block
            switch("screenTime", getString(R.string.screen_time), R.string.info_screen_time, screenTimeShown, locked = !screenTime && systemPagesLocked()) { on ->
                if (!screenTime) return@switch runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.let {}
                val change = {
                    val hidden = Arrangement.hidden(context, Arrangement.HOME)
                    Arrangement.save(context, Arrangement.HOME, Arrangement.order(context, Arrangement.HOME, Arrangement.HOME_ITEMS),
                        if (on) hidden - Arrangement.SCREEN_TIME else hidden + Arrangement.SCREEN_TIME)
                    render()
                }
                // Hiding it loosens lockdown
                if (on) change() else gate.loosen(change)
            },
            switch("weather", getString(R.string.weather), R.string.info_weather, weather) { toggleWeather() },
            segment("temperature", getString(R.string.temperature), R.string.info_temperature, listOf(getString(R.string.units_auto_short), "°C", "°F"),
                Weather.unit(context), level = 1, enabled = weather) {
                Weather.setUnit(context, it)
                viewModel.getWeather(force = true)
                render()
            },
            row("resetLayout", getString(R.string.settings_reset_layout), R.string.info_reset_layout, danger = true) {
                AppDialog(context)
                    .setTitle(R.string.reset_layout_title)
                    .setMessage(R.string.reset_layout_ask)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        Arrangement.reset(context)
                        viewModel.refreshHome(true)
                        context.showToast(getString(R.string.layout_reset_done))
                        render()
                    }
                    .show()
            },
        )
    }

    /** A text size by name, or its number when it's one of its own */
    private fun sizeName(scale: Float) = TEXT_SIZES.firstOrNull { Math.abs(it.first - scale) < 0.05f }?.let { getString(it.second) }
        ?: String.format(Locale.getDefault(), "%.1f", scale)

    private val TEXT_SIZES = listOf(0.8f to R.string.size_smallest, 0.9f to R.string.size_small, 1.0f to R.string.size_normal,
        1.15f to R.string.size_large, 1.3f to R.string.size_larger)

    /**
     * A new text size, without starting the app again: the activity's text scale changes in place and
     * this page is built again with it; pages opened after it (home too) are built with it as well.
     */
    private fun setTextSize(scale: Float) {
        if (Math.abs(scale - prefs.textSizeScale) < 0.05f) return
        prefs.textSizeScale = scale
        val resources = requireActivity().resources
        val config = Configuration(resources.configuration).apply { fontScale = scale }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        rebuildPage()
    }

    /**
     * A new theme, without starting the app again: JLauncher's activity takes the change of night mode
     * itself (it handles uiMode), so its views are only built again: this page now, the rest as they open.
     */
    private fun setTheme(mode: Int) {
        if (prefs.appTheme == mode) return
        prefs.appTheme = mode
        AppCompatDelegate.setDefaultNightMode(mode)
        rebuildPage()
    }

    private fun showStatusBar(show: Boolean) {
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (show) window.insetsController?.show(WindowInsets.Type.statusBars()) else window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = if (show) View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            else View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun toggleWeather() {
        val context = requireContext()
        when {
            Weather.isEnabled(context) -> {
                prefs.weatherOn = false
                viewModel.getWeather()
            }
            Weather.hasLocationPermission(context) -> {
                prefs.weatherOn = true
                viewModel.getWeather(force = true)
            }
            else -> return requestLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        render()
    }

    // --- Gestures ---

    private fun gesturesRows(): Rows {
        val context = requireContext()
        fun swipeName(left: Boolean): String {
            if (!(if (left) prefs.swipeLeftEnabled else prefs.swipeRightEnabled)) return getString(R.string.nothing)
            return when (SwipeTargets.get(context, left)) {
                SwipeTargets.Target.APP -> (if (left) prefs.appNameSwipeLeft else prefs.appNameSwipeRight).ifEmpty { getString(R.string.swipe_an_app) }
                SwipeTargets.Target.STATS -> getString(R.string.stats_title)
                SwipeTargets.Target.STATS_HOME -> getString(R.string.swipe_stats_home)
            }
        }
        val downs = listOf(Constants.SwipeDownAction.NOTIFICATIONS, Constants.SwipeDownAction.SEARCH)
        val service = isAccessServiceEnabled(context)
        val needsService = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        // Shown on only while the service that does it is on too, so a tap on one that looks off always turns it on
        val lockOn = prefs.lockModeOn && (!needsService || service)
        val recentsOn = prefs.homeButtonShowRecents && service
        return Rows(
            row("swipeLeft", getString(R.string.swipe_left), R.string.info_swipe_left, value = swipeName(true)) { pickSwipe(true) },
            row("swipeRight", getString(R.string.swipe_right), R.string.info_swipe_right, value = swipeName(false)) { pickSwipe(false) },
            segment("swipeDown", getString(R.string.swipe_down), R.string.info_swipe_down, listOf(getString(R.string.notifications), getString(R.string.search)),
                downs.indexOf(prefs.swipeDownAction)) {
                prefs.swipeDownAction = downs[it]
                render()
            },
            switch("doubleTap", getString(R.string.double_tap_to_lock_screen), R.string.info_double_tap, lockOn,
                locked = needsService && !service && systemPagesLocked()) { on -> setLockMode(on) },
            switch("homeRecents", getString(R.string.home_button_show_recents), R.string.info_home_recents, recentsOn,
                locked = !service && systemPagesLocked()) { on ->
                when {
                    !on -> prefs.homeButtonShowRecents = false
                    service -> prefs.homeButtonShowRecents = true
                    else -> return@switch askAccessibility(ASKED_FOR_RECENTS)
                }
                render()
            },
        )
    }

    private fun pickSwipe(left: Boolean) {
        val context = requireContext()
        val options = listOf(getString(R.string.stats_title), getString(R.string.swipe_stats_home), getString(R.string.swipe_an_app), getString(R.string.nothing))
        val enabled = if (left) prefs.swipeLeftEnabled else prefs.swipeRightEnabled
        val current = if (!enabled) 3 else when (SwipeTargets.get(context, left)) {
            SwipeTargets.Target.STATS -> 0
            SwipeTargets.Target.STATS_HOME -> 1
            SwipeTargets.Target.APP -> 2
        }
        // Picking "An app" again picks another app
        choose(getString(if (left) R.string.swipe_left_opens else R.string.swipe_right_opens), options, current, suggested = true) { which ->
            if (left) prefs.swipeLeftEnabled = which != 3 else prefs.swipeRightEnabled = which != 3
            when (which) {
                0 -> SwipeTargets.set(context, left, SwipeTargets.Target.STATS)
                1 -> SwipeTargets.set(context, left, SwipeTargets.Target.STATS_HOME)
                2 -> {
                    SwipeTargets.set(context, left, SwipeTargets.Target.APP)
                    viewModel.getAppList(true)
                    findNavController().navigate(R.id.appListFragment,
                        bundleOf(Constants.Key.FLAG to if (left) Constants.FLAG_SET_SWIPE_LEFT_APP else Constants.FLAG_SET_SWIPE_RIGHT_APP))
                }
            }
            render()
        }
    }

    /** Double tap to lock on or off: with the accessibility service from Android 9, a device admin before */
    private fun setLockMode(on: Boolean) {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (on && !isAccessServiceEnabled(context)) return askAccessibility(ASKED_FOR_LOCK)
            prefs.lockModeOn = on
        } else {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceAdmin::class.java)
            if (!on) {
                if (manager.isAdminActive(admin)) runCatching { manager.removeActiveAdmin(admin) }
                prefs.lockModeOn = false
            } else if (manager.isAdminActive(admin)) prefs.lockModeOn = true
            else {
                @Suppress("DEPRECATION")
                requireActivity().startActivityForResult(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_permission_message)), Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        render()
    }

    /**
     * Double tap and home for recents need the accessibility service: asks for it, remembering [forWhat]
     * ([ASKED_FOR_LOCK] or [ASKED_FOR_RECENTS]) so that one, and only that one, turns on once it's allowed
     */
    private fun askAccessibility(forWhat: String) {
        AppDialog(requireContext())
            .setTitle(R.string.accessibility)
            .setMessage(R.string.accessibility_disclosure)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.enable) { _, _ ->
                prefs.accessibilityAskedFor = forWhat
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .show()
    }

    // --- Apps ---

    /** The hidden apps, once looked up ([MainViewModel.getHiddenApps]) */
    private var hiddenAppModels: List<app.olauncher.data.AppModel> = emptyList()

    /** The hidden apps' list: tap or hold one for Open / Unhide, or select several to unhide */
    private val hiddenList = HoldList<app.olauncher.data.AppModel>(onRemove = { unhide(it) }, tapOpensMenu = true, removeLabel = R.string.unhide)

    private fun hiddenApps() = hiddenAppModels.filterIsInstance<app.olauncher.data.AppModel.App>().sortedBy { it.appLabel.lowercase(Locale.getDefault()) }

    private fun appsRows(): Rows {
        val context = requireContext()
        // Until they're looked up, as many as are kept
        val count = hiddenApps().size.takeIf { it > 0 } ?: prefs.hiddenApps.size
        return Rows(
            row("hiddenApps", getString(R.string.hidden_apps), R.string.info_hidden_apps, value = if (count == 0) getString(R.string.none) else count.toString(),
                control = Control.Opens, locked = Lockdown.isLocked(context, Lockdown.Lock.HIDDEN_APPS, prefs)) { openPage(HIDDEN_APPS) },
            switch("keyboard", getString(R.string.auto_show_keyboard), R.string.info_keyboard, prefs.autoShowKeyboard) {
                prefs.autoShowKeyboard = it
                render()
            },
        )
    }

    /** Apps' hidden apps, each opened or unhidden from its menu */
    private fun hiddenAppsRows(): Rows {
        val apps = hiddenApps()
        val rows = apps.map { it to row("app:" + it.appPackage + "|" + it.user, it.appLabel, R.string.info_hidden_app_item) }
        if (rows.isEmpty()) return Rows(noneRow("none"))
        return Rows(rows.map { it.second }) { views ->
            views.listHolding(rows.first().second.key)?.let { hiddenList.attach(it, scrollView()) }
            rows.forEach { (app, row) ->
                val view = views[row.key] ?: return@forEach
                hiddenList.item(view, app, label = app.appLabel, about = row.about, actions = listOf(
                    HoldList.Action(getString(R.string.open_app)) { viewModel.selectedApp(app, Constants.FLAG_HIDDEN_APPS) },
                    HoldList.Action(getString(R.string.unhide)) { unhide(setOf(app)) },
                ))
            }
        }
    }

    /** Puts [apps] back in the app list */
    private fun unhide(apps: Set<app.olauncher.data.AppModel>) {
        val context = requireContext()
        if (LockdownGate.stopIfLocked(context, Lockdown.Lock.HIDDEN_APPS)) return
        val keys = apps.flatMap { listOf(it.appPackage, it.appPackage + "|" + it.user.toString()) }.toSet()
        prefs.hiddenApps = prefs.hiddenApps.filter { it !in keys }.toMutableSet()
        hiddenAppModels = hiddenAppModels.filter { it !in apps }
        viewModel.getAppList()
        viewModel.getHiddenApps()
        render()
    }

    // --- Check-in ---

    private fun checkInRows(): Rows {
        val data = moodStore.load()
        val on = data.promptEnabled
        fun time(minutes: Int) = Formats.time(requireContext(), Formats.minutesOfDay(minutes))
        val asks = data.dailyStats.size + 1
        return Rows(
            switch("checkIn", getString(R.string.check_in_on), R.string.info_check_in, on) { enable ->
                moodStore.update {
                    it.promptEnabled = enable
                    it.promptSnoozedUntil = ""
                }
                render()
            },
            row("time", getString(R.string.prompt_time), R.string.info_check_in_time, value = time(data.promptMinutes), level = 1, enabled = on) {
                pickTime(data.promptMinutes) { moodStore.setPromptTimes(promptMinutes = it) }
            },
            row("asks", getString(R.string.check_in_asks), R.string.info_check_in_asks, value = resources.getQuantityString(R.plurals.stat_count, asks, asks),
                level = 1, enabled = on) { pickCheckInStats() },
            switch("missed", getString(R.string.ask_missed_day), R.string.info_missed_day, data.catchUpEnabled, level = 1, enabled = on) {
                moodStore.setPromptTimes(catchUpEnabled = it)
                render()
            },
            row("until", getString(R.string.ask_missed_day_until), R.string.info_missed_until, value = time(data.catchUpMinutes), level = 2,
                enabled = on && data.catchUpEnabled) {
                pickTime(data.catchUpMinutes) { moodStore.setPromptTimes(catchUpMinutes = it) }
            },
        )
    }

    /** The stats the check-in asks after the mood */
    private fun pickCheckInStats() {
        val stats = moodStore.load().stats.filter { it.id != MoodStore.USAGE }
        val asked = stats.map { it.daily }.toBooleanArray()
        AppDialog(requireContext())
            .setTitle(R.string.check_in_asks)
            .setMultiChoiceItems(stats.map { it.name }.toTypedArray(), asked) { _, i, on -> asked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                moodStore.setDaily(stats.filterIndexed { i, _ -> asked[i] }.map { it.id }.toSet())
                render()
            }
            .show()
    }

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(requireContext(), { _, hour, minute ->
            onPicked(hour * 60 + minute)
            render()
        }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(requireContext())).show()
    }

    // --- Money ---

    /** The weekly limit and what goes with it are set only here; the Money page's limit opens this page */
    private fun moneyRows(): Rows {
        val context = requireContext()
        val limit = Spending.weekLimitCents(context)
        val on = limit > 0
        return Rows(
            row("limit", getString(R.string.week_limit), R.string.info_week_limit, value = if (on) moneyShort(limit) else getString(R.string.off)) { askWeekLimit() },
            switch("carryOver", getString(R.string.settings_carry_over), R.string.info_carry_over, Spending.carryOver(context), level = 1, enabled = on) {
                // Not carrying over what's left lets a bad week be forgotten, so it loosens
                if (it) { Spending.setCarryOver(context, true); render() } else gate.loosen { Spending.setCarryOver(context, false); render() }
            },
            switch("onHome", getString(R.string.money_left_on_home), R.string.info_money_left, Spending.showOnHome(context), level = 1, enabled = on) {
                Spending.setShowOnHome(context, it)
                render()
            },
            row("essentials", getString(R.string.settings_essentials), R.string.info_essentials,
                value = Spending.groupRules(context).size.takeIf { it > 0 }?.toString() ?: getString(R.string.settings_automatic)) { askRules() },
        )
    }

    /** The most to spend in a week; Turn off takes the limit away, which loosens lockdown */
    private fun askWeekLimit() {
        val context = requireContext()
        val current = Spending.weekLimitCents(context)
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "$"
            if (current > 0) setText(if (current % 100 == 0L) (current / 100).toString() else String.format(Locale.US, "%.2f", current / 100.0))
            setSelection(text.length)
        }
        AppDialog(context)
            .setTitle(R.string.week_limit)
            .setView(FrameLayout(context).apply {
                setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
                addView(input)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val dollars = input.text.toString().replace("$", "").replace(",", "").trim().toDoubleOrNull()
                if (dollars != null && dollars > 0) Spending.setWeekLimitCents(context, Math.round(dollars * 100))
                if (isAdded) render()
            }
            .apply {
                if (current > 0) setNeutralButton(R.string.turn_off) { _, _ ->
                    gate.loosen { Spending.setWeekLimitCents(context, 0); if (isAdded) render() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    /** Each category set by hand: change it or put it back to automatic */
    private fun askRules() {
        val context = requireContext()
        val rules = Spending.groupRules(context).entries.toList()
        if (rules.isEmpty()) {
            AppDialog(context).setMessage(R.string.settings_essentials_how).setPositiveButton(R.string.okay, null).show()
            return
        }
        AppDialog(context)
            .setTitle(R.string.settings_essentials)
            .setItems(rules.map { (group, essential) -> group + " · " + getString(if (essential) R.string.essential else R.string.settings_not_essential) }.toTypedArray()) { _, i ->
                val (group, essential) = rules[i]
                AppDialog(context)
                    .setTitle(group)
                    .setItems(arrayOf(getString(if (essential) R.string.essential_not_group else R.string.essential_group, group),
                        getString(R.string.essential_reset_group, group))) { _, which ->
                        Spending.setGroupEssential(context, group, if (which == 0) !essential else null)
                        render()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Places ---

    private fun placesRows(): Rows {
        val context = requireContext()
        val (checks, scans) = Places.todayCounts(context)
        val on = Places.enabled(context)
        return Rows(
            switch("find", getString(R.string.places_find), R.string.info_places, on) {
                // Not finding places any more loosens lockdown, which can turn on at them
                if (it) { Places.setEnabled(context, true); render() } else gate.loosen { Places.setEnabled(context, false); render() }
            },
            row("today", getString(R.string.places_today), R.string.info_places_today, level = 1, enabled = on,
                value = resources.getQuantityString(R.plurals.places_checks, checks, checks) + " · " + resources.getQuantityString(R.plurals.places_scans, scans, scans)),
            row("delete", getString(R.string.places_delete), R.string.info_places_delete, level = 1, enabled = on, danger = true) {
                AppDialog(context)
                    .setTitle(R.string.places_delete_title)
                    .setMessage(R.string.places_delete_ask)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.action_delete) { _, _ -> Places.deleteHistory(context); render() }
                    .show()
            },
        )
    }

    // --- Tracking ---

    /** Each thing tracking needs, OK or to fix, then counting calls */
    private fun trackingRows(): Rows {
        val context = requireContext()
        val calls = People.hasCallLog(context)
        return Rows(Tracking.items(context).mapIndexed { i, item ->
            SettingRow("item.$i", item.title, item.detail, onTap = item.fix?.let { fix -> { fix(context) } },
                value = getString(if (item.ok) R.string.tracking_ok else if (item.fix != null) R.string.tracking_fix else R.string.tracking_check),
                valueColor = if (item.ok) null else Style.WARNING)
        } + listOf(
            // On: tapping goes to app info to take the permission back, which lockdown may block
            switch("calls", getString(R.string.people_count_calls), R.string.info_calls, calls, locked = calls && systemPagesLocked()) {
                if (calls) openAppInfo(context, Process.myUserHandle(), BuildConfig.APPLICATION_ID)
                else askCallLog.launch(android.Manifest.permission.READ_CALL_LOG)
            },
        ))
    }

    // --- Stats ---

    private fun statsRows(): Rows {
        val context = requireContext()
        val newOnHome = StatsHomeFragment.showsNewStats(context)
        val days = listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.SATURDAY)
        val homes = Social.homes(context)
        val work = Social.work(context)
        val goals = listOf(5_000, 6_000, 8_000, 10_000, 12_000, 15_000)
        return Rows(
            segment("weekStarts", getString(R.string.week_starts), R.string.info_week_starts,
                days.map { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }, days.indexOf(app.olauncher.helper.Week.first)) {
                app.olauncher.helper.Week.set(context, days[it])
                render()
            },
            if (app.olauncher.helper.Steps.hasSensor(context)) row("stepGoal", getString(R.string.step_goal), R.string.info_step_goal,
                value = String.format(Locale.getDefault(), "%,d", app.olauncher.helper.Steps.goal(context))) {
                choose(getString(R.string.step_goal), goals.map { String.format(Locale.getDefault(), "%,d", it) }, goals.indexOf(app.olauncher.helper.Steps.goal(context))) {
                    app.olauncher.helper.Steps.setGoal(context, goals[it])
                    render()
                }
            } else null,
            switch("newOnHome", getString(R.string.settings_new_on_home), R.string.info_new_on_home, newOnHome) {
                StatsHomeFragment.setShowsNewStats(context, it)
                render()
            },
            row("homes", getString(R.string.homes_and_work), R.string.info_homes, control = Control.Opens,
                value = listOfNotNull(homes.firstOrNull()?.name, homes.getOrNull(1)?.name, work?.name).joinToString(", ").ifEmpty { getString(R.string.none) }) {
                AutoStats.id(context, AutoStats.SOCIAL)?.let { id -> runCatching { findNavController().navigate(R.id.statFragment, bundleOf(StatFragment.STAT_ID to id)) } }
            },
            row("report", getString(R.string.report_make), R.string.info_report) { askReport() },
            if (app.olauncher.helper.DeviceTime.updated(context, app.olauncher.helper.DeviceTime.Device.MAC) > 0)
                switch("pornFromMac", getString(R.string.porn_from_mac), R.string.info_porn_from_mac, app.olauncher.helper.DeviceTime.pornOn(context)) {
                    app.olauncher.helper.DeviceTime.setPornOn(context, it)
                    render()
                } else null,
        )
    }

    private fun askReport() {
        val spans = listOf(30, 90, 180)
        AppDialog(requireContext())
            .setTitle(R.string.report_make)
            .setItems(spans.map { getString(R.string.last_days, it) }.toTypedArray()) { _, i ->
                reportDays = spans[i]
                // So the launcher stays here while the file is picked, instead of going home
                viewModel.isPickingFile = true
                createReport.launch(getString(R.string.report_file, LocalDate.now().toString()))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Notes ---

    private fun notesRows(): Rows {
        val tidy = app.olauncher.helper.AiTidy.isOn(requireContext())
        return Rows(
            row("claudeKey", getString(R.string.voice_tidy), R.string.info_voice_tidy, value = onOff(tidy)) { setTidyKey() },
            row("summarise", getString(R.string.journal_summarise_all), R.string.info_journal_summarise_all, value = summarising, level = 1, enabled = tidy) { summariseAll() },
        )
    }

    /** Shown beside "Tidy and summarise notes" while it's going */
    private var summarising: String? = null

    /** Summarises every long journal note again, after saying how many that is */
    private fun summariseAll() {
        if (summarising != null) return
        val context = requireContext().applicationContext
        val count = app.olauncher.helper.JournalSummaries.toRedo(context).size
        if (count == 0) return android.widget.Toast.makeText(context, R.string.journal_summarise_none, android.widget.Toast.LENGTH_SHORT).show()
        AppDialog(requireContext())
            .setMessage(resources.getQuantityString(R.plurals.journal_summarise_ask, count, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.journal_summarise) { _, _ ->
                summarising = getString(R.string.journal_summarising)
                render()
                app.olauncher.helper.JournalSummaries.redoAll(context) { made, failed, why ->
                    summarising = null
                    if (isAdded) render()
                    if (failed == 0) return@redoAll android.widget.Toast.makeText(context,
                        context.resources.getQuantityString(R.plurals.journal_summarised, made, made), android.widget.Toast.LENGTH_LONG).show()
                    // A dialog, so the reason can be read in full
                    val text = context.resources.getQuantityString(R.plurals.journal_summarise_failed, failed, failed)
                    if (isAdded) AppDialog(requireContext())
                        .setTitle(text)
                        .setMessage(why ?: "")
                        .setPositiveButton(R.string.okay, null)
                        .show()
                    else android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** The Anthropic API key dictated notes are tidied with; none turns it off */
    private fun setTidyKey() {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.voice_tidy_key)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(app.olauncher.helper.AiTidy.key(context).orEmpty())
        }
        val builder = AppDialog(context)
            .setTitle(R.string.voice_tidy)
            .setMessage(R.string.info_voice_tidy)
            .setView(FrameLayout(context).apply {
                setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
                addView(input)
            })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                app.olauncher.helper.AiTidy.setKey(context, input.text?.toString())
                if (isAdded) render()
            }
        if (app.olauncher.helper.AiTidy.isOn(context)) builder.setNeutralButton(R.string.voice_tidy_off) { _, _ ->
            app.olauncher.helper.AiTidy.setKey(context, null)
            if (isAdded) render()
        }
        builder.show()
    }

    // --- Newsletter ---

    /** Each newsletter's settings page, and how issues are read */
    private fun newsletterRows(): Rows {
        val context = requireContext()
        return Rows(app.olauncher.helper.NewsletterSync.PROFILES.map { profile ->
            row("profile.$profile", getString(if (profile == app.olauncher.helper.NewsletterSync.WEEKLY) R.string.newsletter_weekly else R.string.newsletter_daily),
                R.string.info_newsletter_profile, control = Control.Opens) {
                findNavController().navigate(R.id.newsletterSettingsFragment, bundleOf(NewsletterSettingsFragment.PROFILE to profile))
            }
        } + segment("reader", getString(R.string.newsletter_reader_mode), R.string.info_newsletter_reader,
            listOf(getString(R.string.newsletter_scrolling), getString(R.string.newsletter_pages)), if (NewsletterReaderFragment.paged(context)) 1 else 0) {
            NewsletterReaderFragment.setPaged(context, it == 1)
            render()
        })
    }

    // --- Backup ---

    private fun backupRows(): Rows {
        val context = requireContext()
        val folder = BackupManager.backupFolder(context)
        return Rows(
            row("folder", getString(R.string.backup_folder), R.string.info_backup_folder, value = when {
                folder.isEmpty() -> getString(R.string.off)
                BackupManager.isFolderFailing(context) -> getString(R.string.backup_not_saving)
                else -> folderName(folder)
            }) {
                if (folder.isEmpty()) return@row pickFolder()
                AppDialog(context)
                    .setTitle(R.string.backup_folder)
                    .setItems(arrayOf(getString(R.string.change_folder), getString(R.string.turn_off))) { _, which ->
                        if (which == 0) pickFolder() else {
                            BackupManager.setBackupFolder(context, null)
                            render()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            row("export", getString(R.string.export_backup), R.string.info_export) {
                viewModel.isPickingFile = true
                exportBackup.launch(BackupManager.BACKUP_FILE_NAME)
            },
            row("import", getString(R.string.import_backup), R.string.info_import, danger = true) {
                viewModel.isPickingFile = true
                importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            },
        )
    }

    private fun pickFolder() {
        viewModel.isPickingFile = true
        pickBackupFolder.launch(null)
    }

    /**
     * The backup folder's name as its app gives it, looked up once off the main thread and kept. Until
     * then, or if it can't be, a guess from the link: Google Drive's ids are codes, not names.
     */
    private fun folderName(treeUri: String): String {
        val context = requireContext()
        val names = context.getSharedPreferences("settings_ui", Context.MODE_PRIVATE)
        names.getString("folderName:" + treeUri, null)?.let { return it }
        if (lookingUpFolder != treeUri) {
            lookingUpFolder = treeUri
            val app = context.applicationContext
            Thread {
                val name = runCatching {
                    val uri = Uri.parse(treeUri)
                    val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()?.takeIf { it.isNotBlank() }
                if (name != null) {
                    names.edit().putString("folderName:" + treeUri, name).apply()
                    main.post { if (isAdded) render() }
                }
            }.start()
        }
        val uri = Uri.parse(treeUri)
        if (uri.authority.orEmpty().contains("google")) return getString(R.string.google_drive)
        return runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').substringAfterLast('/') }.getOrNull()
            ?.takeIf { it.isNotEmpty() && '=' !in it && ';' !in it } ?: getString(R.string.on)
    }

    private var lookingUpFolder: String? = null

    /** A backup's been picked to import: it replaces everything, so that's asked first */
    private fun confirmImport(backup: JSONObject) {
        AppDialog(requireContext())
            .setTitle(R.string.import_backup_ask)
            .setMessage(getString(R.string.import_backup_message) + savedAt(backup))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore) { _, _ -> restoreBackup(backup) }
            .show()
    }

    /**
     * Replaces everything with a backup. Loosens lockdown, since it could undo it. The app starts again
     * after, as every setting and list it holds has changed underneath it.
     */
    private fun restoreBackup(backup: JSONObject, afterRestore: () -> Unit = {}) {
        loosenThen {
            BackupManager.restore(requireContext(), backup)
            afterRestore()
            requireContext().showToast(getString(R.string.backup_restored))
            requireActivity().recreate()
        }
    }

    /** Anything that weakens a limit needs lockdown's unlock while lockdown is on */
    private fun loosenThen(action: () -> Unit) = gate.loosen(action)

    private fun showRestoreSummary() {
        val summary = BackupManager.takeRestoreSummary(requireContext()) ?: return
        AppDialog(requireContext())
            .setTitle(R.string.backup_restored)
            .setMessage(summary + "\n\n" + getString(R.string.restored_note))
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    // --- About ---

    private fun aboutRows(): Rows {
        val context = requireContext()
        return Rows(
            row("launcher", getString(if (isOlauncherDefault(context)) R.string.change_default_launcher else R.string.set_as_default_launcher), R.string.info_default,
                locked = Lockdown.isLocked(context, Lockdown.Lock.LAUNCHER, prefs)) { viewModel.resetLauncherLiveData.call() },
            row("tutorial", getString(R.string.tutorial), R.string.info_tutorial) { Tutorial.show(requireActivity()) },
            row("appInfo", getString(R.string.app_info), R.string.info_app_info, locked = systemPagesLocked()) {
                openAppInfo(context, Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            },
            row("version", getString(R.string.version), R.string.info_version, value = BuildConfig.VERSION_NAME),
            updateRow(),
            app.olauncher.helper.CrashLog.last(context)?.let { text ->
                row("lastError", getString(R.string.last_error), R.string.info_last_error, value = text.substringBefore(" · ").replace('T', ' ')) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.last_error)))
                }
            },
        )
    }

    // --- Updating from the build on Drive ---

    /** What the build on Drive is, once looked at; null while looking */
    private var update: AppUpdate.State? = null

    private var checking = false

    /** Looks at the build on Drive; [thenInstall] opens the installer straight away if it's newer */
    private fun checkUpdate(thenInstall: Boolean = false) {
        if (checking) return
        checking = true
        val context = requireContext().applicationContext
        Thread {
            val state = AppUpdate.check(context)
            main.post {
                checking = false
                update = state
                if (thenInstall) {
                    if (state is AppUpdate.State.Newer) runCatching { AppUpdate.install(context, state.uri) }
                    else context.showToast(updateValue(state))
                }
                if (isAdded) render()
            }
        }.start()
    }

    private fun updateValue(state: AppUpdate.State?): String = when (state) {
        null -> getString(R.string.app_update_checking)
        AppUpdate.State.NoFolder, AppUpdate.State.NotFound -> getString(R.string.app_update_not_found)
        is AppUpdate.State.Latest -> getString(R.string.app_update_latest, LockdownGate.time(requireContext(), state.modified))
        is AppUpdate.State.Newer -> getString(R.string.app_update_newer, LockdownGate.time(requireContext(), state.modified))
    }

    /** A newer build installs; otherwise a tap looks on Drive again */
    private fun updateRow(): SettingRow {
        val context = requireContext()
        val state = update.takeIf { !checking }
        return row("update", getString(R.string.app_update), R.string.info_app_update, value = updateValue(state)) {
            when (state) {
                null -> {}
                is AppUpdate.State.Newer -> runCatching { AppUpdate.install(context, state.uri) }
                else -> { checkUpdate(thenInstall = true); render() }
            }
        }
    }
}

/**
 * The setting that asked for the accessibility service turns on once it's allowed: double tap to lock
 * or home for recents, only the one that asked. Called when JLauncher or Settings comes back.
 */
internal fun finishAccessibilityAsk(context: Context, prefs: Prefs) {
    val asked = prefs.accessibilityAskedFor
    if (asked.isEmpty() || !isAccessServiceEnabled(context)) return
    prefs.accessibilityAskedFor = ""
    when (asked) {
        SettingsFragment.ASKED_FOR_LOCK -> prefs.lockModeOn = true
        SettingsFragment.ASKED_FOR_RECENTS -> prefs.homeButtonShowRecents = true
    }
}

/**
 * The header the stats pages share: ‹, an icon, and the title, large. Tapping ‹ goes back.
 */
fun pageHeader(context: android.content.Context, icon: String?, title: String, onBack: () -> Unit): View {
    val ink = context.getColorFromAttr(R.attr.primaryColor)
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12.dpToPx(), 0, 20.dpToPx(), 16.dpToPx())
        addView(TextView(context, null, 0, R.style.TextLarge).apply {
            text = "‹"
            textSize = Style.TEXT_LARGE
            setTextColor(ink)
            setPadding(8.dpToPx(), 0, 14.dpToPx(), 0)
            contentDescription = context.getString(R.string.close)
            setOnClickListener { onBack() }
        })
        if (icon != null) addView(View(context).apply { background = StatIcons.drawable(icon, ink, 24.dpToPx()) },
            LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply { marginEnd = 12.dpToPx() })
        addView(TextView(context, null, 0, R.style.TextLarge).apply {
            text = title
            textSize = Style.TEXT_LARGE
            setTextColor(ink)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
