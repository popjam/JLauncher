package app.olauncher.ui

import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.helper.Formats
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.BackupManager
import app.olauncher.data.Constants
import app.olauncher.data.MoodStore
import app.olauncher.data.Prefs
import app.olauncher.helper.AppUpdate
import app.olauncher.helper.AppPause
import app.olauncher.helper.Arrangement
import app.olauncher.helper.Lockdown
import androidx.core.view.isVisible
import app.olauncher.helper.Limits
import app.olauncher.helper.AutoStats
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
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every setting on one page, in sections that open and close, one open at a time: lockdown, the home
 * screen, gestures, apps, the check-in, money, places, tracking, stats, notes, the newsletter, backup
 * and about. Each section's header shows a short summary of it. A setting's own options only show
 * (indented) while it's on, and holding any setting explains it in a line or two. Searching shows the
 * matching settings and opens the first section with one.
 */
class SettingsFragment : CardPageFragment() {

    companion object {
        /** Argument: the section to open at, one of the keys below ([MONEY], [TRACKING]) */
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
    }

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList
    private val moodStore by lazy { MoodStore(requireContext()) }
    private var recoveryTaps = 0

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
                if (backup == null) context.showToast(getString(R.string.import_failed)) else restoreBackup(backup)
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
        val savedAt = BackupManager.savedAt(existing)?.let {
            "\n\n" + getString(R.string.backup_saved_on, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(it))
        }.orEmpty()
        AppDialog(requireContext())
            .setTitle(R.string.backup_folder)
            .setMessage(getString(R.string.backup_found_message) + savedAt)
            .setCancelable(false)
            .setPositiveButton(R.string.restore) { _, _ -> restoreBackup(existing, link) }
            .setNegativeButton(R.string.keep_current) { _, _ -> link() }
            .show()
    }

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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // --- The page ---

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        prefs = Prefs(context)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx())
            addView(pageHeader(context, StatIcons.GEAR, getString(R.string.settings)) { findNavController().popBackStack() })
            addView(searchBox(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(12.dpToPx(), 0, 12.dpToPx(), 10.dpToPx())
            })
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                setText(R.string.settings_hold_hint)
                textSize = Style.TEXT_SMALL
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                alpha = 0.45f
                setPadding(28.dpToPx(), 0, 28.dpToPx(), 4.dpToPx())
            })
            // Kept from the page before, when coming back from a page opened here, so it's taken off that first
            (noMatches.parent as? ViewGroup)?.removeView(noMatches)
            addView(noMatches)
            addView(cardList)
        }
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(content)
                scroll = this
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    override fun onResume() {
        super.onResume()
        checkUpdate()
        viewModel.getHiddenApps()
        // Permissions and services may have changed in the system's settings
        viewModel.isOlauncherDefault()
        if (prefs.lockModePending && isAccessServiceEnabled(requireContext())) {
            prefs.lockModePending = false
            prefs.lockModeOn = true
        }
        render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.hiddenApps.observe(viewLifecycleOwner) {
            hiddenAppModels = it.orEmpty()
            render()
        }
    }

    override fun onPause() {
        hiddenList.end()
        super.onPause()
    }

    /** Which section is open: one at a time, kept while the page rebuilds and after coming back to it */
    private val sections = OneOpen()

    /** Rows that open inside a section (the hidden apps), apart from [sections] so opening one keeps its section open */
    private val groups = OneOpen()

    private var scroll: ScrollView? = null

    /** Where the page was scrolled to, kept while a page opened from it is on top */
    private var savedScroll = -1

    private fun render() {
        if (!isAdded) return
        restoreScrollSoon()
        searchable.clear()
        cardList.removeAllViews()
        lockdownSection()
        homeSection()
        gesturesSection()
        appsSection()
        checkInSection()
        moneySection()
        placesSection()
        trackingSection()
        statsSection()
        notesSection()
        newsletterSection()
        backupSection()
        aboutSection()
        applySearch()
        // Opened at a section (the Money page's limit, a problem on the stat list), once
        arguments?.getString(OPEN)?.let { key ->
            arguments?.remove(OPEN)
            sections.show(key)
        }
    }

    // --- Search ---

    /** Each section's key, card, title, and its rows with the words each is found by */
    private class Section(val key: String, val root: View, val title: String, val rows: List<Pair<View, String>>)
    private val searchable = mutableListOf<Section>()
    private var query = ""
    private val searchText = java.util.WeakHashMap<View, String>()
    private val noMatches by lazy {
        TextView(requireContext(), null, 0, R.style.TextSmall).apply {
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            alpha = 0.55f
            gravity = Gravity.CENTER
            setPadding(28.dpToPx(), 32.dpToPx(), 28.dpToPx(), 0)
            visibility = View.GONE
        }
    }

    private fun searchBox(context: Context) = EditText(context, null, 0, R.style.TextSmall).apply {
        hint = getString(R.string.settings_search_hint)
        textSize = Style.TEXT_BODY
        setTextColor(ink)
        setHintTextColor(faint(110))
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
        background = GradientDrawable().apply {
            cornerRadius = Style.radiusLarge
            // Solid like the sections, so it reads over a light wallpaper
            setColor(panelColor)
        }
        setPadding(18.dpToPx(), 11.dpToPx(), 18.dpToPx(), 11.dpToPx())
        setText(query)
        // Built without the usual text box style, so it has to be told it can take focus from a tap
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener {
            requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this, 0)
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                applySearch()
            }
        })
        setOnEditorActionListener { view, _, _ ->
            view.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
            true
        }
    }

    /**
     * Shows only the settings matching the search: by their name, what holding them says, or their
     * section's name (which shows the whole section). A match under another setting keeps its line.
     * Sections without a match go, and unless the open section has one, the first that does opens.
     */
    private fun applySearch() {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(text: String) = text.lowercase(Locale.getDefault()).let { t -> words.all { it in t } }
        var any = false
        val matching = mutableListOf<String>()
        searchable.forEach { section ->
            val whole = words.isEmpty() || matches(section.title)
            var shown = 0
            section.rows.forEach { (view, text) ->
                val show = whole || matches(text)
                view.visibility = if (show) View.VISIBLE else View.GONE
                if (show) shown++
            }
            section.root.visibility = if (shown > 0) View.VISIBLE else View.GONE
            if (shown > 0) {
                any = true
                matching += section.key
            }
        }
        noMatches.visibility = if (any || words.isEmpty()) View.GONE else View.VISIBLE
        if (!any && words.isNotEmpty()) noMatches.text = getString(R.string.settings_search_none, query.trim())
        if (words.isNotEmpty() && matching.isNotEmpty() && sections.open !in matching) sections.show(matching.first())
    }

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    /** A [settingRow] that holding explains with [about], found by search by its name and that */
    private fun row(card: Card, title: String, value: String?, about: Int, level: Int = 0, onTap: (() -> Unit)?): View =
        settingRow(title, value, getString(about), level, onTap = onTap).also { searchText[it] = title + "\n" + getString(about) }

    /** Makes [this] the section kept as [key], opening and closing, its header showing [value]; returns its rows' list */
    private fun Card.body(key: String, value: String?, vararg rows: View?): LinearLayout {
        val shown = rows.filterNotNull()
        val list = collapsibleSection(sections, key, value, null, shown)
        searchable.add(Section(key, root, titleView.text.toString(), shown.map { it to (searchText[it] ?: rowText(it)) }))
        return list
    }

    /** The words in a row made some other way than [row], like Tracking's status lines */
    private fun rowText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { rowText(view.getChildAt(it)) }
        else -> ""
    }

    private fun choose(title: Int, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AppDialog(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                onPick(which)
                render()
            }
            .show()
    }

    // --- Home screen ---

    private fun homeSection() {
        val context = requireContext()
        val card = Card(getString(R.string.home_screen))
        val alignments = listOf(Gravity.START, Gravity.CENTER, Gravity.END)
        val alignNames = listOf(R.string.left, R.string.center, R.string.right).map { getString(it) }
        val clock = listOf(Constants.DateTime.ON, Constants.DateTime.DATE_ONLY, Constants.DateTime.OFF)
        val clockNames = listOf(R.string.on, R.string.date_only, R.string.off).map { getString(it) }
        val themes = listOf(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val themeNames = listOf(R.string.dark, R.string.light, R.string.system_default).map { getString(it) }
        val screenTime = context.appUsagePermissionGranted()
        val units = listOf(getString(R.string.units_auto), "°C", "°F")
        val screenTimeShown = screenTime && Arrangement.SCREEN_TIME !in Arrangement.hidden(context, Arrangement.HOME)
        card.body(HOME, null,
            row(card, getString(R.string.apps_on_home_screen), prefs.homeAppsNum.toString(), R.string.info_home_apps) {
                choose(R.string.apps_on_home_screen, (0..8).map { it.toString() }, prefs.homeAppsNum) {
                    prefs.homeAppsNum = it
                    viewModel.refreshHome(true)
                }
            },
            row(card, getString(R.string.home_pages), prefs.homePages.toString(), R.string.info_home_pages) {
                choose(R.string.home_pages, (1..5).map { it.toString() }, prefs.homePages - 1) {
                    prefs.homePages = it + 1
                    if (prefs.homePage >= prefs.homePages) prefs.homePage = prefs.homePages - 1
                    viewModel.refreshHome(true)
                }
            },
            row(card, getString(R.string.clock), clockNames[clock.indexOf(prefs.dateTimeVisibility).coerceAtLeast(0)], R.string.info_clock) {
                choose(R.string.clock, clockNames, clock.indexOf(prefs.dateTimeVisibility)) {
                    prefs.dateTimeVisibility = clock[it]
                    viewModel.toggleDateTime()
                }
            },
            row(card, getString(R.string.home_layout_alignment), alignNames[alignments.indexOf(prefs.homeAlignment).coerceAtLeast(0)], R.string.info_alignment) {
                choose(R.string.home_layout_alignment, alignNames, alignments.indexOf(prefs.homeAlignment)) {
                    viewModel.updateHomeAlignment(alignments[it])
                }
            },
            row(card, getString(R.string.apps_at_bottom), onOff(prefs.homeBottomAlignment), R.string.info_bottom, level = 1) {
                prefs.homeBottomAlignment = !prefs.homeBottomAlignment
                viewModel.updateHomeAlignment(prefs.homeAlignment)
                render()
            },
            row(card, getString(R.string.text_size), sizeName(prefs.textSizeScale), R.string.info_text_size) {
                val sizes = TEXT_SIZES.map { it.first } + if (isTablet(context)) listOf(1.6f, 2.0f) else emptyList()
                choose(R.string.text_size, sizes.map { sizeName(it) }, sizes.indexOfFirst { Math.abs(it - prefs.textSizeScale) < 0.05f }) {
                    if (Math.abs(sizes[it] - prefs.textSizeScale) < 0.05f) return@choose
                    prefs.textSizeScale = sizes[it]
                    requireActivity().recreate()
                }
            },
            row(card, getString(R.string.theme_mode), themeNames[themes.indexOf(prefs.appTheme).coerceAtLeast(0)], R.string.info_theme) {
                choose(R.string.theme_mode, themeNames, themes.indexOf(prefs.appTheme)) {
                    if (prefs.appTheme == themes[it]) return@choose
                    prefs.appTheme = themes[it]
                    requireActivity().recreate()
                }
            },
            row(card, getString(R.string.notification_bar), onOff(prefs.showStatusBar), R.string.info_status_bar) {
                prefs.showStatusBar = !prefs.showStatusBar
                showStatusBar(prefs.showStatusBar)
                render()
            },
            row(card, getString(R.string.screen_time), onOff(screenTimeShown), R.string.info_screen_time) {
                if (!screenTime) return@row startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                val hidden = Arrangement.hidden(context, Arrangement.HOME)
                Arrangement.save(context, Arrangement.HOME, Arrangement.order(context, Arrangement.HOME, Arrangement.HOME_ITEMS),
                    if (screenTimeShown) hidden + Arrangement.SCREEN_TIME else hidden - Arrangement.SCREEN_TIME)
                render()
            },
            row(card, getString(R.string.weather), onOff(Weather.isEnabled(context)), R.string.info_weather) { toggleWeather() },
            if (Weather.isEnabled(context)) row(card, getString(R.string.temperature), units[Weather.unit(context)], R.string.info_temperature, level = 1) {
                choose(R.string.temperature, units, Weather.unit(context)) {
                    Weather.setUnit(context, it)
                    viewModel.getWeather(force = true)
                }
            } else null,
            row(card, getString(R.string.settings_reset_layout), null, R.string.info_reset_layout) {
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

    private fun gesturesSection() {
        val context = requireContext()
        val card = Card(getString(R.string.gestures))
        fun swipeName(left: Boolean): String {
            if (!(if (left) prefs.swipeLeftEnabled else prefs.swipeRightEnabled)) return getString(R.string.nothing)
            return when (SwipeTargets.get(context, left)) {
                SwipeTargets.Target.APP -> (if (left) prefs.appNameSwipeLeft else prefs.appNameSwipeRight).ifEmpty { getString(R.string.swipe_an_app) }
                SwipeTargets.Target.STATS -> getString(R.string.stats_title)
                SwipeTargets.Target.STATS_HOME -> getString(R.string.swipe_stats_home)
            }
        }
        fun pickSwipe(left: Boolean) {
            val options = listOf(getString(R.string.stats_title), getString(R.string.swipe_stats_home), getString(R.string.swipe_an_app), getString(R.string.nothing))
            val enabled = if (left) prefs.swipeLeftEnabled else prefs.swipeRightEnabled
            val current = if (!enabled) 3 else when (SwipeTargets.get(context, left)) {
                SwipeTargets.Target.STATS -> 0
                SwipeTargets.Target.STATS_HOME -> 1
                SwipeTargets.Target.APP -> 2
            }
            choose(if (left) R.string.swipe_left_opens else R.string.swipe_right_opens, options, current) { which ->
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
            }
        }
        val downNames = listOf(getString(R.string.notifications), getString(R.string.search))
        val downs = listOf(Constants.SwipeDownAction.NOTIFICATIONS, Constants.SwipeDownAction.SEARCH)
        val lockOn = prefs.lockModeOn && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || isAccessServiceEnabled(context))
        card.body(GESTURES, null,
            row(card, getString(R.string.swipe_left), swipeName(true), R.string.info_swipe_left) { pickSwipe(true) },
            row(card, getString(R.string.swipe_right), swipeName(false), R.string.info_swipe_right) { pickSwipe(false) },
            row(card, getString(R.string.swipe_down), downNames[downs.indexOf(prefs.swipeDownAction).coerceAtLeast(0)], R.string.info_swipe_down) {
                choose(R.string.swipe_down, downNames, downs.indexOf(prefs.swipeDownAction)) { prefs.swipeDownAction = downs[it] }
            },
            row(card, getString(R.string.double_tap_to_lock_screen), onOff(lockOn), R.string.info_double_tap) { toggleLock() },
            row(card, getString(R.string.home_button_show_recents), onOff(prefs.homeButtonShowRecents && isAccessServiceEnabled(context)), R.string.info_home_recents) {
                if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(context)) return@row askAccessibility()
                prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
                render()
            },
        )
    }

    // --- Notes ---

    private fun notesSection() {
        val context = requireContext()
        val card = Card(getString(R.string.settings_notes))
        val tidy = app.olauncher.helper.AiTidy.isOn(context)
        card.body(NOTES, null,
            row(card, getString(R.string.voice_tidy), onOff(tidy), R.string.info_voice_tidy) { setTidyKey() },
            if (tidy) row(card, getString(R.string.journal_summarise_all), summarising, R.string.info_journal_summarise_all, level = 1) { summariseAll() }
            else null,
        )
    }

    /** Shown beside "Summarise all notes" while it's going */
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
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(app.olauncher.helper.AiTidy.key(context).orEmpty())
        }
        val builder = AppDialog(context)
            .setTitle(R.string.voice_tidy)
            .setMessage(R.string.info_voice_tidy)
            .setView(android.widget.FrameLayout(context).apply {
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

    private fun toggleLock() {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(context)) return askAccessibility()
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceAdmin::class.java)
            if (manager.isAdminActive(admin)) {
                runCatching { manager.removeActiveAdmin(admin) }
                prefs.lockModeOn = false
            } else {
                @Suppress("DEPRECATION")
                requireActivity().startActivityForResult(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_permission_message)), Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        render()
    }

    /** Double tap, home for recents and pausing apps opened elsewhere all need the accessibility service */
    private fun askAccessibility() {
        AppDialog(requireContext())
            .setTitle(R.string.accessibility)
            .setMessage(R.string.accessibility_disclosure)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.enable) { _, _ ->
                prefs.lockModePending = true
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .show()
    }

    // --- Apps ---

    /** The hidden apps, once looked up ([MainViewModel.getHiddenApps]) */
    private var hiddenAppModels: List<app.olauncher.data.AppModel> = emptyList()

    /** The hidden apps' list: tap or hold one for Open / Unhide, or select several to unhide */
    private val hiddenList = HoldList<app.olauncher.data.AppModel>(onRemove = { unhide(it) }, tapOpensMenu = true, removeLabel = R.string.unhide)

    private fun appsSection() {
        val context = requireContext()
        val card = Card(getString(R.string.apps))
        val apps = hiddenAppModels.filterIsInstance<app.olauncher.data.AppModel.App>().sortedBy { it.appLabel.lowercase(Locale.getDefault()) }
        // Until they're looked up, as many as are kept
        val count = if (apps.isNotEmpty()) apps.size else prefs.hiddenApps.size
        val countText = if (count == 0) getString(R.string.none) else count.toString()
        val locked = Lockdown.isLocked(context, Lockdown.Lock.HIDDEN_APPS, prefs)
        val hiddenRows = apps.map { settingRow(it.appLabel, null, level = 1, onTap = null) }
        // While lockdown keeps hidden apps hidden, the row only says so; otherwise it opens to the list
        val hidden: View = if (locked || apps.isEmpty()) row(card, getString(R.string.hidden_apps), countText, R.string.info_hidden_apps) {
            LockdownGate.stopIfLocked(context, Lockdown.Lock.HIDDEN_APPS)
        } else collapsibleGroup(groups, "hidden", getString(R.string.hidden_apps), countText, getString(R.string.info_hidden_apps), hiddenRows).also { group ->
            hiddenList.attach(group.rows, scroll)
            hiddenRows.forEachIndexed { i, row ->
                val app = apps[i]
                hiddenList.item(row, app, label = app.appLabel, actions = listOf(
                    HoldList.Action(getString(R.string.open_app)) { viewModel.selectedApp(app, Constants.FLAG_HIDDEN_APPS) },
                    HoldList.Action(getString(R.string.unhide)) { unhide(setOf(app)) },
                ))
            }
        }.view
        card.body(APPS, if (count == 0) null else resources.getQuantityString(R.plurals.hidden_count, count, count),
            hidden,
            row(card, getString(R.string.auto_show_keyboard), onOff(prefs.autoShowKeyboard), R.string.info_keyboard) {
                prefs.autoShowKeyboard = !prefs.autoShowKeyboard
                render()
            },
        )
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

    /** Anything that weakens a limit needs lockdown's unlock while lockdown is on */
    private fun loosen(action: () -> Unit) = gate.loosen(action)

    // --- Check-in ---

    private fun checkInSection() {
        val data = moodStore.load()
        val card = Card(getString(R.string.check_in))
        fun time(minutes: Int) = Formats.time(requireContext(), Formats.minutesOfDay(minutes))
        card.body(CHECK_IN, if (data.promptEnabled) time(data.promptMinutes) else getString(R.string.off),
            row(card, getString(R.string.daily_mood_prompt), onOff(data.promptEnabled), R.string.info_check_in) {
                val enable = !data.promptEnabled
                moodStore.update {
                    it.promptEnabled = enable
                    it.promptSnoozedUntil = ""
                }
                render()
            },
            if (data.promptEnabled) row(card, getString(R.string.prompt_time), time(data.promptMinutes), R.string.info_check_in_time, level = 1) {
                pickTime(data.promptMinutes) { moodStore.setPromptTimes(promptMinutes = it) }
            } else null,
            if (data.promptEnabled) row(card, getString(R.string.check_in_asks), resources.getQuantityString(R.plurals.stat_count, data.dailyStats.size + 1, data.dailyStats.size + 1),
                R.string.info_check_in_asks, level = 1) { pickCheckInStats() } else null,
            if (data.promptEnabled) row(card, getString(R.string.ask_missed_day), onOff(data.catchUpEnabled), R.string.info_missed_day, level = 1) {
                moodStore.setPromptTimes(catchUpEnabled = !data.catchUpEnabled)
                render()
            } else null,
            if (data.promptEnabled && data.catchUpEnabled) row(card, getString(R.string.ask_missed_day_until), time(data.catchUpMinutes), R.string.info_missed_until, level = 2) {
                pickTime(data.catchUpMinutes) { moodStore.setPromptTimes(catchUpMinutes = it) }
            } else null,
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

    // --- Tracking ---

    private fun trackingSection() {
        val context = requireContext()
        val items = Tracking.items(context)
        val card = Card(getString(R.string.tracking))
        val problems = items.count { !it.ok }
        val status = items.map { item ->
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4.dpToPx(), 9.dpToPx(), 4.dpToPx(), 9.dpToPx())
                setBackgroundResource(android.R.drawable.list_selector_background)
                item.fix?.let { fix -> setOnClickListener { fix(context) } }
                setOnLongClickListener {
                    explainSetting(this, item.title, item.detail)
                    true
                }
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (item.ok) Style.GOOD else Style.WARNING)
                    }
                }, LinearLayout.LayoutParams(8.dpToPx(), 8.dpToPx()).apply { marginEnd = 12.dpToPx() })
                addView(card.textView(Style.TEXT_BODY, 240).apply { text = item.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (!item.ok) addView(card.textView(Style.TEXT_BODY, 150).apply {
                    text = getString(if (item.fix != null) R.string.tracking_fix else R.string.tracking_check)
                })
            }
        }
        card.body(TRACKING, if (problems == 0) getString(R.string.tracking_all_ok) else resources.getQuantityString(R.plurals.tracking_problems, problems, problems),
            *status.toTypedArray(),
            row(card, getString(R.string.people_count_calls), onOff(People.hasCallLog(context)), R.string.info_calls) {
                if (People.hasCallLog(context)) openAppInfo(context, Process.myUserHandle(), BuildConfig.APPLICATION_ID)
                else askCallLog.launch(android.Manifest.permission.READ_CALL_LOG)
            },
        )
    }

    private fun placesSection() {
        val context = requireContext()
        val card = Card(getString(R.string.places_title_short))
        val social = AutoStats.id(context, AutoStats.SOCIAL)
        val homes = Social.homes(context)
        val work = Social.work(context)
        val (checks, scans) = Places.todayCounts(context)
        val placesOn = Places.enabled(context)
        card.body(PLACES, onOff(placesOn),
            row(card, getString(R.string.homes_and_work), listOfNotNull(homes.firstOrNull()?.name, homes.getOrNull(1)?.name, work?.name)
                .joinToString(", ").ifEmpty { getString(R.string.none) }, R.string.info_homes) {
                social?.let { id -> runCatching { findNavController().navigate(R.id.statFragment, bundleOf(StatFragment.STAT_ID to id)) } }
            },
            row(card, getString(R.string.places_find), onOff(placesOn), R.string.info_places) {
                Places.setEnabled(context, !placesOn)
                render()
            },
            if (placesOn) row(card, getString(R.string.places_today), resources.getQuantityString(R.plurals.places_checks, checks, checks) + " · " +
                resources.getQuantityString(R.plurals.places_scans, scans, scans), R.string.info_places_today, level = 1, onTap = null) else null,
            if (placesOn) row(card, getString(R.string.places_delete), null, R.string.info_places_delete, level = 1) {
                AppDialog(context)
                    .setTitle(R.string.places_delete_title)
                    .setMessage(R.string.places_delete_ask)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.action_delete) { _, _ -> Places.deleteHistory(context); render() }
                    .show()
            } else null,
        )
    }

    // --- Money ---

    /** The weekly limit and what goes with it are set only here; the Money page's limit opens this section */
    private fun moneySection() {
        val context = requireContext()
        val card = Card(getString(R.string.settings_spending))
        val limit = Spending.weekLimitCents(context)
        val limitText = if (limit <= 0) getString(R.string.off) else moneyShort(limit)
        card.body(MONEY, limitText,
            row(card, getString(R.string.week_limit), limitText, R.string.info_week_limit) { askWeekLimit() },
            if (limit > 0) row(card, getString(R.string.settings_carry_over), onOff(Spending.carryOver(context)), R.string.info_carry_over, level = 1) {
                Spending.setCarryOver(context, !Spending.carryOver(context))
                render()
            } else null,
            if (limit > 0) row(card, getString(R.string.money_left_on_home), onOff(Spending.showOnHome(context)), R.string.info_money_left, level = 1) {
                Spending.setShowOnHome(context, !Spending.showOnHome(context))
                render()
            } else null,
            row(card, getString(R.string.settings_essentials), Spending.groupRules(context).size.takeIf { it > 0 }?.toString() ?: getString(R.string.settings_automatic),
                R.string.info_essentials) { askRules() },
        )
    }

    /** The most to spend in a week; Turn off takes the limit away */
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
            .apply { if (current > 0) setNeutralButton(R.string.turn_off) { _, _ -> Spending.setWeekLimitCents(context, 0); if (isAdded) render() } }
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
                    .show()
            }
            .show()
    }

    // --- Stats ---

    private fun statsSection() {
        val context = requireContext()
        val card = Card(getString(R.string.stats_title))
        val newOnHome = StatsHomeFragment.showsNewStats(context)
        card.body(STATS, null,
            row(card, getString(R.string.week_starts), app.olauncher.helper.Week.first.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()),
                R.string.info_week_starts) {
                val days = listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.SATURDAY)
                choose(R.string.week_starts, days.map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) },
                    days.indexOf(app.olauncher.helper.Week.first)) { app.olauncher.helper.Week.set(context, days[it]) }
            },
            if (app.olauncher.helper.Steps.hasSensor(context)) row(card, getString(R.string.step_goal),
                String.format(Locale.getDefault(), "%,d", app.olauncher.helper.Steps.goal(context)), R.string.info_step_goal) {
                val goals = listOf(5_000, 6_000, 8_000, 10_000, 12_000, 15_000)
                choose(R.string.step_goal, goals.map { String.format(Locale.getDefault(), "%,d", it) }, goals.indexOf(app.olauncher.helper.Steps.goal(context))) {
                    app.olauncher.helper.Steps.setGoal(context, goals[it])
                }
            } else null,
            row(card, getString(R.string.settings_new_on_home), onOff(newOnHome), R.string.info_new_on_home) {
                StatsHomeFragment.setShowsNewStats(context, !newOnHome)
                render()
            },
            row(card, getString(R.string.report_make), null, R.string.info_report) { askReport() },
            if (app.olauncher.helper.DeviceTime.updated(context, app.olauncher.helper.DeviceTime.Device.MAC) > 0)
                row(card, getString(R.string.porn_from_mac), onOff(app.olauncher.helper.DeviceTime.pornOn(context)), R.string.info_porn_from_mac) {
                    app.olauncher.helper.DeviceTime.setPornOn(context, !app.olauncher.helper.DeviceTime.pornOn(context))
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
            .show()
    }

    // --- Lockdown ---

    private val gate by lazy { LockdownGate(this) { render() } }

    /**
     * Lockdown has its own page (Limits is in it, under Screen time); here it's the first section,
     * its header saying whether it's on, with a red dot while it's in force, and a row opening the page.
     */
    private fun lockdownSection() {
        val context = requireContext()
        val card = Card(getString(R.string.lockdown))
        val on = Lockdown.isActive(context, prefs)
        card.body(LOCKDOWN, LockdownGate.status(context),
            row(card, getString(R.string.settings_lockdown_open), null, R.string.info_lockdown) {
                findNavController().navigate(R.id.lockdownFragment)
            },
        )
        if (on) card.headerEnd.valueDot(Style.DANGER, first = true)
    }

    // --- Newsletter ---

    /** Each newsletter's settings page, and how issues are read */
    private fun newsletterSection() {
        val context = requireContext()
        val card = Card(getString(R.string.newsletter))
        card.body(NEWSLETTER, null,
            *app.olauncher.helper.NewsletterSync.PROFILES.map { profile ->
                row(card, getString(if (profile == app.olauncher.helper.NewsletterSync.WEEKLY) R.string.newsletter_weekly else R.string.newsletter_daily),
                    null, R.string.info_newsletter_profile) {
                    findNavController().navigate(R.id.newsletterSettingsFragment, bundleOf(NewsletterSettingsFragment.PROFILE to profile))
                }
            }.toTypedArray(),
            row(card, getString(R.string.newsletter_reader_mode), getString(
                if (NewsletterReaderFragment.paged(context)) R.string.newsletter_pages else R.string.newsletter_scrolling), R.string.info_newsletter_reader) {
                NewsletterReaderFragment.setPaged(context, !NewsletterReaderFragment.paged(context))
                render()
            },
        )
    }

    /** A small dot before a row's value (its last text), or before the first text with [first] */
    private fun View.valueDot(color: Int, first: Boolean = false) {
        val group = this as? ViewGroup ?: return
        val value = group.getChildAt(if (first) 0 else group.childCount - 1) as? android.widget.TextView ?: return
        val size = 7.dpToPx()
        value.setCompoundDrawablesRelative(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(size, size)
            setBounds(0, 0, size, size)
        }, null, null, null)
        value.compoundDrawablePadding = 7.dpToPx()
    }

    // --- Backup ---

    private fun backupSection() {
        val context = requireContext()
        val card = Card(getString(R.string.backup))
        val folder = BackupManager.backupFolder(context)
        val saved = BackupManager.lastSaved(context)
        card.body(BACKUP, when {
                folder.isEmpty() -> getString(R.string.off)
                BackupManager.isFolderFailing(context) -> getString(R.string.backup_not_saving)
                saved > 0 -> getString(R.string.backup_saved_at, LockdownGate.time(context, saved))
                else -> getString(R.string.on)
            },
            row(card, getString(R.string.backup_folder), when {
                folder.isEmpty() -> getString(R.string.off)
                BackupManager.isFolderFailing(context) -> getString(R.string.backup_not_saving)
                else -> folderName(folder)
            }, R.string.info_backup_folder) {
                if (folder.isEmpty()) return@row pickFolder()
                AppDialog(context)
                    .setTitle(R.string.backup_folder)
                    .setItems(arrayOf(getString(R.string.change_folder), getString(R.string.turn_off))) { _, which ->
                        if (which == 0) pickFolder() else {
                            BackupManager.setBackupFolder(context, null)
                            render()
                        }
                    }
                    .show()
            },
            row(card, getString(R.string.export_backup), null, R.string.info_export) {
                viewModel.isPickingFile = true
                exportBackup.launch(BackupManager.BACKUP_FILE_NAME)
            },
            row(card, getString(R.string.import_backup), null, R.string.info_import) {
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

    /** Replaces everything with a backup. Loosens lockdown, since it could undo it. */
    private fun restoreBackup(backup: JSONObject, afterRestore: () -> Unit = {}) {
        loosen {
            BackupManager.restore(requireContext(), backup)
            afterRestore()
            requireContext().showToast(getString(R.string.backup_restored))
            requireActivity().recreate()
        }
    }

    private fun showRestoreSummary() {
        val summary = BackupManager.takeRestoreSummary(requireContext()) ?: return
        AppDialog(requireContext())
            .setTitle(R.string.backup_restored)
            .setMessage(summary + "\n\n" + getString(R.string.restored_note))
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    // --- About ---

    private fun aboutSection() {
        val context = requireContext()
        val card = Card(getString(R.string.about))
        val isDefault = isOlauncherDefault(context)
        card.body(ABOUT, BuildConfig.VERSION_NAME,
            if (!Lockdown.isLocked(context, Lockdown.Lock.LAUNCHER, prefs)) row(card, getString(if (isDefault) R.string.change_default_launcher else R.string.set_as_default_launcher),
                null, R.string.info_default) { viewModel.resetLauncherLiveData.call() } else null,
            row(card, getString(R.string.tutorial), null, R.string.info_tutorial) { Tutorial.show(requireActivity()) },
            row(card, getString(R.string.app_info), null, R.string.info_app_info) {
                openAppInfo(context, Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            },
            row(card, getString(R.string.version), BuildConfig.VERSION_NAME, R.string.info_version, onTap = null),
            updateRow(card),
        )
        showRestoreSummary()
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
            view?.post {
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
    private fun updateRow(card: Card): View? {
        val context = requireContext()
        val state = update.takeIf { !checking }
        return row(card, getString(R.string.app_update), updateValue(state), R.string.info_app_update) {
            when (state) {
                null -> {}
                is AppUpdate.State.Newer -> runCatching { AppUpdate.install(context, state.uri) }
                else -> { checkUpdate(thenInstall = true); render() }
            }
        }
    }

    /** Back from a page opened here, the page goes back to where it was, once it's laid out again */
    private fun restoreScrollSoon() {
        if (savedScroll < 0) return
        val y = savedScroll
        savedScroll = -1
        scroll?.let { view -> view.post { view.scrollTo(0, y) } }
    }

    override fun onDestroyView() {
        scroll?.let { savedScroll = it.scrollY }
        scroll = null
        super.onDestroyView()
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
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
