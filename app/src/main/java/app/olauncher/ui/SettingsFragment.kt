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
import android.view.KeyEvent
import app.olauncher.helper.MyAccessibilityService
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.BackupManager
import app.olauncher.data.Constants
import app.olauncher.data.MoodStore
import app.olauncher.data.Prefs
import app.olauncher.helper.AppPause
import app.olauncher.helper.Arrangement
import app.olauncher.helper.Lockdown
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
 * Every setting on one page, in sections: the home screen, gestures, apps, the daily check-in, what's
 * tracked, the stats, lockdown, backup and about. A setting's own options only show (indented) while
 * it's on, and holding any setting explains it in a line or two.
 */
class SettingsFragment : CardPageFragment() {

    companion object {
        /** Argument: open at the Tracking section */
        const val TO_TRACKING = "toTracking"
    }

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList
    private val moodStore by lazy { MoodStore(requireContext()) }
    private var recoveryTaps = 0
    private var info: PopupWindow? = null

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
            requireContext().showToast(getString(R.string.backup_folder_set))
            return
        }
        // Don't silently overwrite either side: ask which to keep
        val savedAt = BackupManager.savedAt(existing)?.let {
            "\n\n" + getString(R.string.backup_saved_on, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(it))
        }.orEmpty()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_folder)
            .setMessage(getString(R.string.backup_found_message) + savedAt)
            .setCancelable(false)
            .setPositiveButton(R.string.restore) { _, _ -> restoreBackup(existing, link) }
            .setNegativeButton(R.string.keep_current) { _, _ ->
                link()
                requireContext().showToast(getString(R.string.backup_folder_set))
            }
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
                textSize = 13f
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                alpha = 0.45f
                setPadding(28.dpToPx(), 0, 28.dpToPx(), 4.dpToPx())
            })
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
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions and services may have changed in the system's settings
        viewModel.isOlauncherDefault()
        if (prefs.lockModePending && isAccessServiceEnabled(requireContext())) {
            prefs.lockModePending = false
            prefs.lockModeOn = true
        }
        render()
        // Lockdown's times and waits move on while the page is open
        main.removeCallbacks(refreshStatus)
        main.postDelayed(refreshStatus, 30_000)
    }

    override fun onPause() {
        info?.dismiss()
        main.removeCallbacks(refreshStatus)
        super.onPause()
    }

    /** Scrolls to the Tracking section once, when opened from a problem on the stat list */
    private var toTracking = false

    private fun render() {
        if (!isAdded) return
        sections.clear()
        toTracking = toTracking || arguments?.getBoolean(TO_TRACKING) == true
        arguments?.remove(TO_TRACKING)
        cardList.removeAllViews()
        homeSection()
        gesturesSection()
        appsSection()
        checkInSection()
        trackingSection()
        statsSection()
        lockdownSection()
        backupSection()
        aboutSection()
        applySearch()
    }

    // --- Search ---

    /** Each section's card, its title, and its rows with the words each is found by */
    private class Section(val root: View, val title: String, val rows: List<Pair<View, String>>)
    private val sections = mutableListOf<Section>()
    private var query = ""
    private val searchText = java.util.WeakHashMap<View, String>()
    private val noMatches by lazy {
        TextView(requireContext(), null, 0, R.style.TextSmall).apply {
            textSize = 14f
            setTextColor(ink)
            alpha = 0.55f
            gravity = Gravity.CENTER
            setPadding(28.dpToPx(), 32.dpToPx(), 28.dpToPx(), 0)
            visibility = View.GONE
        }
    }

    private fun searchBox(context: Context) = EditText(context, null, 0, R.style.TextSmall).apply {
        hint = getString(R.string.settings_search_hint)
        textSize = 15f
        setTextColor(ink)
        setHintTextColor(faint(110))
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
        background = GradientDrawable().apply {
            cornerRadius = 22.dpToPx().toFloat()
            setColor(faint(18))
        }
        setPadding(18.dpToPx(), 11.dpToPx(), 18.dpToPx(), 11.dpToPx())
        setText(query)
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
     */
    private fun applySearch() {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(text: String) = text.lowercase(Locale.getDefault()).let { t -> words.all { it in t } }
        var any = false
        sections.forEach { section ->
            val whole = words.isEmpty() || matches(section.title)
            var shown = 0
            section.rows.forEach { (view, text) ->
                val show = whole || matches(text)
                view.visibility = if (show) View.VISIBLE else View.GONE
                if (show) shown++
            }
            section.root.visibility = if (shown > 0) View.VISIBLE else View.GONE
            if (shown > 0) any = true
        }
        noMatches.visibility = if (any || words.isEmpty()) View.GONE else View.VISIBLE
        if (!any && words.isNotEmpty()) noMatches.text = getString(R.string.settings_search_none, query.trim())
    }

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    /**
     * A setting: its name, its value on the right, indented by [level] under the one it belongs to;
     * tapping does [onTap], holding explains it with [about].
     */
    private fun row(card: Card, title: String, value: String?, about: Int, level: Int = 0, onTap: (() -> Unit)?): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            setBackgroundResource(android.R.drawable.list_selector_background)
            if (onTap != null) setOnClickListener { onTap() }
            setOnLongClickListener {
                explain(this, title, getString(about))
                true
            }
            // A line down the left for each level it's under, joining it to the setting it belongs to
            for (l in 1..level) addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 1.dpToPx().toFloat()
                    setColor(faint(70))
                }
            }, LinearLayout.LayoutParams(2.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = if (l == 1) 6.dpToPx() else 12.dpToPx()
                if (l == level) marginEnd = 12.dpToPx()
            })
            addView(card.textView(if (level == 0) 15.5f else 14.5f, if (level == 0) 245 else 200).apply {
                text = title
                setPadding(0, 11.dpToPx(), 0, 11.dpToPx())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value != null) addView(card.textView(14f, 150).apply {
                text = value
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                // Never so long it squeezes the setting's name
                maxWidth = (resources.displayMetrics.widthPixels * 0.4f).toInt()
                updatePadding(left = 12.dpToPx())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            searchText[this] = title + "\n" + getString(about)
        }
    }

    /** What a setting does, in a small card under it */
    private fun explain(anchor: View, title: String, text: String) {
        info?.dismiss()
        val context = requireContext()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18.dpToPx().toFloat()
                // Solid, so the rows under it don't show through
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(context.getColorFromAttr(R.attr.primaryShadeDarkColor), 255))
                setStroke(1.dpToPx(), faint(60))
            }
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 14.dpToPx())
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                this.text = title
                textSize = 14f
                setTextColor(ink)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                this.text = text
                textSize = 14f
                setTextColor(ink)
                alpha = 0.8f
                setLineSpacing(0f, 1.15f)
                updatePadding(top = 4.dpToPx())
            })
        }
        val width = anchor.width.coerceAtLeast(200.dpToPx())
        info = PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 8.dpToPx().toFloat()
            showAsDropDown(anchor, 0, 4.dpToPx())
        }
    }

    private fun Card.body(vararg rows: View?) {
        scene.visibility = View.GONE
        // The section's name in small spaced capitals, so it reads as a heading over its settings rather than one of them
        titleView.textSize = 12.5f
        titleView.isAllCaps = true
        titleView.letterSpacing = 0.12f
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        titleView.setTextColor(faint(140))
        val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val shown = rows.filterNotNull()
        shown.forEach { list.addView(it) }
        root.addView(list, root.indexOfChild(scene))
        sections.add(Section(root, titleView.text.toString(), shown.map { it to (searchText[it] ?: rowText(it)) }))
    }

    /** The words in a row made some other way than [row], like Tracking's status lines */
    private fun rowText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { rowText(view.getChildAt(it)) }
        else -> ""
    }

    private fun choose(title: Int, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(requireContext())
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
        card.body(
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
            row(card, getString(R.string.settings_arrange), null, R.string.info_arrange) {
                AlertDialog.Builder(context).setTitle(R.string.settings_arrange).setMessage(R.string.info_arrange).setPositiveButton(R.string.okay, null).show()
            },
            row(card, getString(R.string.settings_reset_layout), null, R.string.info_reset_layout, level = 1) {
                AlertDialog.Builder(context)
                    .setMessage(R.string.reset_layout_ask)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.settings_reset_layout) { _, _ ->
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
        card.body(
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
            row(card, getString(R.string.voice_key), if (prefs.voiceKey != 0 && isAccessServiceEnabled(context)) keyName(prefs.voiceKey) else getString(R.string.off),
                R.string.info_voice_key) { pickVoiceKey() },
        )
    }

    private fun keyName(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
        .lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }

    /** Waits for the key to be pressed, through the accessibility service */
    private fun pickVoiceKey() {
        if (!isAccessServiceEnabled(requireContext())) return askAccessibility()
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_key)
            .setMessage(R.string.voice_key_press)
            .setNegativeButton(R.string.cancel, null)
        if (prefs.voiceKey != 0) builder.setNeutralButton(R.string.voice_key_off) { _, _ ->
            prefs.voiceKey = 0
            render()
        }
        val dialog = builder.create()
        dialog.setOnDismissListener { MyAccessibilityService.learn = null }
        MyAccessibilityService.learn = { code ->
            prefs.voiceKey = code
            runCatching { dialog.dismiss() }
            if (isAdded) render()
        }
        dialog.show()
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
        AlertDialog.Builder(requireContext())
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

    private fun appsSection() {
        val context = requireContext()
        val card = Card(getString(R.string.apps))
        val serviceOn = isAccessServiceEnabled(context)
        val slowing = prefs.pauseAppsOn || prefs.useForOn
        val hidden = prefs.hiddenApps.size
        card.body(
            row(card, getString(R.string.hidden_apps), hidden.toString(), R.string.info_hidden_apps) {
                if (LockdownGate.stopIfLocked(context, Lockdown.Lock.HIDDEN_APPS)) return@row
                if (prefs.hiddenApps.isEmpty()) return@row context.showToast(getString(R.string.no_hidden_apps))
                viewModel.getHiddenApps()
                findNavController().navigate(R.id.appListFragment, bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS))
            },
            row(card, getString(R.string.auto_show_keyboard), onOff(prefs.autoShowKeyboard), R.string.info_keyboard) {
                prefs.autoShowKeyboard = !prefs.autoShowKeyboard
                render()
            },
            row(card, getString(R.string.pause_before_opening), onOff(prefs.pauseAppsOn), R.string.info_pause) {
                if (prefs.pauseAppsOn) loosen { prefs.pauseAppsOn = false; render() }
                else {
                    prefs.pauseAppsOn = true
                    AppPause.addAddictiveApps(context, prefs)
                    render()
                }
            },
            if (prefs.pauseAppsOn) row(card, getString(R.string.pause_length), getString(R.string.seconds_value, prefs.pauseSeconds), R.string.info_pause_length, level = 1) {
                val options = listOf(5, 10, 15, 20, 30, 45, 60)
                choose(R.string.pause_length, options.map { getString(R.string.seconds_value, it) }, options.indexOf(prefs.pauseSeconds)) {
                    val seconds = options[it]
                    if (seconds < prefs.pauseSeconds) loosen { prefs.pauseSeconds = seconds; render() } else prefs.pauseSeconds = seconds
                }
            } else null,
            row(card, getString(R.string.use_for_a_set_time), onOff(prefs.useForOn), R.string.info_use_for) {
                if (prefs.useForOn) loosen { prefs.useForOn = false; render() }
                else {
                    prefs.useForOn = true
                    AppPause.addAddictiveApps(context, prefs)
                    render()
                    if (!serviceOn) askAccessibility()
                }
            },
            if (prefs.useForOn) row(card, getString(R.string.use_for_lengths), AppPause.useForLengths(context).joinToString(", "), R.string.info_use_for_lengths, level = 1) {
                val sets = AppPause.USE_FOR_SETS
                choose(R.string.use_for_lengths, sets.map { set -> set.joinToString(", ") { getString(R.string.minutes_short, it) } },
                    sets.indexOf(AppPause.useForLengths(context))) { AppPause.setUseForSet(context, it) }
            } else null,
            if (slowing) row(card, getString(R.string.paused_apps), prefs.pausedApps.count { context.isPackageInstalled(it) }.toString(), R.string.info_paused_apps, level = 1) {
                loosen { findNavController().navigate(R.id.pausedAppsFragment) }
            } else null,
            if (slowing) row(card, getString(R.string.apps_opened_elsewhere), onOff(serviceOn), R.string.info_elsewhere, level = 1) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else null,
        )
    }

    /** Anything that weakens a limit needs lockdown's unlock while lockdown is on */
    private fun loosen(action: () -> Unit) = gate.loosen(action)

    // --- Check-in ---

    private fun checkInSection() {
        val data = moodStore.load()
        val card = Card(getString(R.string.check_in))
        val pattern = if (DateFormat.is24HourFormat(requireContext())) "HH:mm" else "h:mm a"
        fun time(minutes: Int) = LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        card.body(
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.check_in_asks)
            .setMultiChoiceItems(stats.map { it.name }.toTypedArray(), asked) { _, i, on -> asked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                moodStore.setDaily(stats.filterIndexed { i, _ -> asked[i] }.map { it.id }.toSet())
                render()
            }
            .show()
    }

    /**
     * Which of [keys] show, and in what order: a tick for each, and arrows to move it. Saved for
     * [name] in [Arrangement].
     */
    private fun arrange(title: Int, name: String, keys: List<String>, label: (String) -> String) {
        val context = requireContext()
        val order = Arrangement.order(context, name, keys).toMutableList()
        val hidden = Arrangement.hidden(context, name).toMutableSet()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 8.dpToPx(), 12.dpToPx(), 0)
        }
        fun draw() {
            list.removeAllViews()
            order.forEachIndexed { i, key ->
                list.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(android.widget.CheckBox(context).apply {
                        text = label(key)
                        isChecked = key !in hidden
                        setOnCheckedChangeListener { _, on -> if (on) hidden -= key else hidden += key }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    fun arrow(symbol: String, enabled: Boolean, by: Int) = TextView(context).apply {
                        text = symbol
                        textSize = 20f
                        alpha = if (enabled) 0.85f else 0.2f
                        setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
                        if (enabled) setOnClickListener {
                            order.add(i + by, order.removeAt(i))
                            draw()
                        }
                    }
                    addView(arrow("↑", i > 0, -1))
                    addView(arrow("↓", i < order.lastIndex, 1))
                })
            }
        }
        draw()
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                Arrangement.save(context, name, order, hidden)
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
        if (toTracking) {
            toTracking = false
            card.root.post { (cardList.parent?.parent as? ScrollView)?.smoothScrollTo(0, card.root.top) }
        }
        val problems = items.count { !it.ok }
        card.subtitle(if (problems == 0) getString(R.string.tracking_all_ok) else resources.getQuantityString(R.plurals.tracking_problems, problems, problems))
        val status = items.map { item ->
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4.dpToPx(), 9.dpToPx(), 4.dpToPx(), 9.dpToPx())
                setBackgroundResource(android.R.drawable.list_selector_background)
                item.fix?.let { fix -> setOnClickListener { fix(context) } }
                setOnLongClickListener {
                    explain(this, item.title, item.detail)
                    true
                }
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (item.ok) 0xFF66BB6A.toInt() else 0xFFFFB300.toInt())
                    }
                }, LinearLayout.LayoutParams(8.dpToPx(), 8.dpToPx()).apply { marginEnd = 12.dpToPx() })
                addView(card.textView(15f, 240).apply { text = item.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (!item.ok) addView(card.textView(14f, 150).apply {
                    text = getString(if (item.fix != null) R.string.tracking_fix else R.string.tracking_check)
                })
            }
        }
        card.body(
            *status.toTypedArray(),
            row(card, getString(R.string.people_count_calls), onOff(People.hasCallLog(context)), R.string.info_calls) {
                if (People.hasCallLog(context)) openAppInfo(context, Process.myUserHandle(), BuildConfig.APPLICATION_ID)
                else askCallLog.launch(android.Manifest.permission.READ_CALL_LOG)
            },
        )
        placesSection()
        spendingSection()
    }

    private fun placesSection() {
        val context = requireContext()
        val card = Card(getString(R.string.places_title_short))
        val social = AutoStats.id(context, AutoStats.SOCIAL)
        val homes = Social.homes(context)
        val work = Social.work(context)
        val (checks, scans) = Places.todayCounts(context)
        val placesOn = Places.enabled(context)
        card.body(
            row(card, getString(R.string.homes_and_work), listOfNotNull(homes.firstOrNull()?.name, homes.getOrNull(1)?.name, work?.name)
                .joinToString(", ").ifEmpty { getString(R.string.social_not_set) }, R.string.info_homes) {
                social?.let { id -> runCatching { findNavController().navigate(R.id.statFragment, bundleOf(StatFragment.STAT_ID to id)) } }
            },
            row(card, getString(R.string.places_find), onOff(placesOn), R.string.info_places) {
                Places.setEnabled(context, !placesOn)
                render()
            },
            if (placesOn) row(card, getString(R.string.places_today), resources.getQuantityString(R.plurals.places_checks, checks, checks) + " · " +
                resources.getQuantityString(R.plurals.places_scans, scans, scans), R.string.info_places_today, level = 1, onTap = null) else null,
            if (placesOn) row(card, getString(R.string.places_delete), null, R.string.info_places_delete, level = 1) {
                AlertDialog.Builder(context)
                    .setMessage(R.string.places_delete_ask)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.places_delete) { _, _ -> Places.deleteHistory(context); render() }
                    .show()
            } else null,
        )
    }

    private fun spendingSection() {
        val context = requireContext()
        val card = Card(getString(R.string.settings_spending))
        val limit = Spending.weekLimitCents(context)
        card.body(
            row(card, getString(R.string.week_limit), if (limit <= 0) getString(R.string.off) else moneyShort(limit), R.string.info_week_limit) {
                askWeekLimit { if (isAdded) render() }
            },
            if (limit > 0) row(card, getString(R.string.money_left_on_home), onOff(Spending.showOnHome(context)), R.string.info_money_left, level = 1) {
                Spending.setShowOnHome(context, !Spending.showOnHome(context))
                render()
            } else null,
            if (limit > 0) row(card, getString(R.string.settings_carry_over), onOff(Spending.carryOver(context)), R.string.info_carry_over, level = 1) {
                Spending.setCarryOver(context, !Spending.carryOver(context))
                render()
            } else null,
            row(card, getString(R.string.settings_essentials), Spending.groupRules(context).size.takeIf { it > 0 }?.toString() ?: getString(R.string.settings_automatic),
                R.string.info_essentials) { askRules() },
        )
    }

    /** Each category set by hand: change it or put it back to automatic */
    private fun askRules() {
        val context = requireContext()
        val rules = Spending.groupRules(context).entries.toList()
        if (rules.isEmpty()) {
            AlertDialog.Builder(context).setMessage(R.string.settings_essentials_how).setPositiveButton(R.string.okay, null).show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_essentials)
            .setItems(rules.map { (group, essential) -> group + " · " + getString(if (essential) R.string.essential else R.string.settings_not_essential) }.toTypedArray()) { _, i ->
                val (group, essential) = rules[i]
                AlertDialog.Builder(context)
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
        card.body(
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
            row(card, getString(R.string.stats_home_cards), Arrangement.shown(context, Arrangement.STATS_HOME, Arrangement.STATS_HOME_CARDS).size.toString(),
                R.string.info_stats_home_cards) {
                arrange(R.string.stats_home_cards, Arrangement.STATS_HOME, Arrangement.STATS_HOME_CARDS) { key ->
                    getString(when (key) {
                        Arrangement.DAILY -> R.string.stat_daily
                        Arrangement.TRENDS -> R.string.graph_trends
                        Arrangement.REMEMBER -> R.string.remember
                        Arrangement.INSIGHTS -> R.string.insights
                        Arrangement.THIS_WEEK -> R.string.this_week
                        else -> R.string.compare_two
                    })
                }
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
        AlertDialog.Builder(requireContext())
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
    private var lockdownCard: Card? = null
    private var lockdownPhase: List<Any>? = null

    /** What changes lockdown's rows: whether it's on, timed, and how far a wait has got */
    private fun lockdownPhase(context: Context) = listOf(Lockdown.isActive(context, prefs), Lockdown.timedActive(context, prefs),
        Lockdown.waitState(context, prefs)::class)

    /** Keeps lockdown's status current while the page is open, and its rows when they change */
    private val refreshStatus = object : Runnable {
        override fun run() {
            if (!isAdded) return
            val context = requireContext()
            if (lockdownPhase(context) != lockdownPhase) render() else lockdownCard?.subtitle(LockdownGate.status(context))
            main.postDelayed(this, 30_000)
        }
    }

    private fun lockdownSection() {
        val context = requireContext()
        val card = Card(getString(R.string.lockdown))
        lockdownCard = card
        lockdownPhase = lockdownPhase(context)
        card.subtitle(LockdownGate.status(context))
        // Tapping the title 500 times shows the password, when that way of recovering it is on
        card.titleView.setOnClickListener {
            if (!prefs.passwordRecoveryEnabled || !prefs.recoveryTap500 || !prefs.isLockdownPasswordSet) return@setOnClickListener
            recoveryTaps++
            if (recoveryTaps >= 500) {
                recoveryTaps = 0
                gate.showPassword()
            } else if (recoveryTaps % 50 == 0) context.showToast(getString(R.string.recovery_tap_progress, recoveryTaps))
        }
        val manual = prefs.lockdownModeOn
        val timedEnd = Lockdown.timedEnd(context, prefs)
        val schedules = Lockdown.schedules(prefs)
        val locks = Lockdown.Lock.values()
        val blocked = prefs.lockdownBlockedApps.count { context.isPackageInstalled(it) }
        val unlock = Lockdown.unlock(prefs)
        val needsService = blocked > 0 || prefs.lockdownProtect
        val password = unlock == Lockdown.Unlock.PASSWORD
        val recovery = password && prefs.passwordRecoveryEnabled
        fun method(title: Int, about: Int, get: () -> Boolean, toggle: () -> Unit) = if (recovery) row(card, getString(title), onOff(get()), about, level = 2) {
            loosen(toggle)
        } else null
        card.body(
            // Switched on, or on by itself until a time
            row(card, getString(R.string.lockdown), when {
                manual -> getString(R.string.on)
                Lockdown.isActive(context, prefs) -> Lockdown.activeUntil(context, prefs)?.let { getString(R.string.lockdown_until, LockdownGate.time(context, it)) }
                    ?: getString(R.string.on)
                else -> getString(R.string.off)
            }, R.string.info_lockdown) {
                if (manual) loosen { prefs.lockdownModeOn = false; render() }
                else {
                    prefs.lockdownModeOn = true
                    render()
                }
            },
            row(card, getString(R.string.lockdown_lock_for), timedEnd?.let { getString(R.string.lockdown_until, LockdownGate.time(context, it)) } ?: getString(R.string.off),
                R.string.info_lockdown_lock_for) { askLockFor() },
            row(card, getString(R.string.lockdown_schedules), if (schedules.isEmpty()) getString(R.string.off) else schedules.size.toString(),
                R.string.info_lockdown_schedules) { if (schedules.isEmpty()) addSchedule() else showSchedules() },
            row(card, getString(R.string.lockdown_whats_locked), getString(R.string.lockdown_count_of, locks.count { Lockdown.enabled(prefs, it) }, locks.size),
                R.string.info_lockdown_whats_locked) { pickLocks() },
            row(card, getString(R.string.blocked_apps), blocked.toString(), R.string.info_blocked_apps) {
                findNavController().navigate(R.id.pausedAppsFragment, bundleOf(PausedAppsFragment.MODE to PausedAppsFragment.BLOCKED))
            },
            if (needsService) row(card, getString(R.string.accessibility), onOff(isAccessServiceEnabled(context)), R.string.info_lockdown_accessibility, level = 1) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else null,
            row(card, getString(R.string.lockdown_unlock_with), unlockName(unlock), R.string.info_lockdown_unlock_with) { pickUnlock() },
            if (password) row(card, getString(R.string.change_lockdown_password), null, R.string.info_change_password, level = 1) {
                gate.requirePassword { gate.setNewPassword(warn = false) { render() } }
            } else null,
            if (password) row(card, getString(R.string.lockdown_password_recovery), onOff(prefs.passwordRecoveryEnabled), R.string.info_recovery, level = 1) {
                loosen { prefs.passwordRecoveryEnabled = !prefs.passwordRecoveryEnabled; render() }
            } else null,
            method(R.string.recovery_tap_500, R.string.recovery_tap_500_info, { prefs.recoveryTap500 }) { prefs.recoveryTap500 = !prefs.recoveryTap500; render() },
            method(R.string.recovery_clue, R.string.recovery_clue_info, { prefs.recoveryClue }) { toggleClue() },
            method(R.string.recovery_essay, R.string.recovery_essay_info, { prefs.recoveryEssay }) { prefs.recoveryEssay = !prefs.recoveryEssay; render() },
        )
    }

    private fun unlockName(unlock: Lockdown.Unlock) = when (unlock) {
        Lockdown.Unlock.NOTHING -> getString(R.string.lockdown_unlock_nothing)
        Lockdown.Unlock.PASSWORD -> getString(R.string.lockdown_password)
        Lockdown.Unlock.WAIT -> getString(R.string.lockdown_unlock_wait, LockdownGate.duration(requireContext(), prefs.lockdownWaitMinutes * 60_000L))
    }

    /** Nothing, the password, or one of the waits. Anything but a stricter choice loosens lockdown. */
    private fun pickUnlock() {
        val context = requireContext()
        val current = Lockdown.unlock(prefs)
        val waits = Lockdown.WAIT_CHOICES
        val names = listOf(getString(R.string.lockdown_unlock_nothing), getString(R.string.lockdown_password)) +
            waits.map { getString(R.string.lockdown_unlock_wait, LockdownGate.duration(context, it * 60_000L)) }
        val selected = when (current) {
            Lockdown.Unlock.NOTHING -> 0
            Lockdown.Unlock.PASSWORD -> 1
            Lockdown.Unlock.WAIT -> 2 + waits.indexOf(prefs.lockdownWaitMinutes)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.lockdown_unlock_with)
            .setSingleChoiceItems(names.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                if (which == selected) return@setSingleChoiceItems
                val stricter = current == Lockdown.Unlock.NOTHING ||
                    (current == Lockdown.Unlock.WAIT && which >= 2 && waits[which - 2] > prefs.lockdownWaitMinutes)
                val change = {
                    when (which) {
                        0 -> {
                            gate.clearPassword()
                            prefs.lockdownUnlock = Lockdown.Unlock.NOTHING.ordinal
                            render()
                        }
                        1 -> gate.setNewPassword(warn = true) {
                            prefs.lockdownUnlock = Lockdown.Unlock.PASSWORD.ordinal
                            render()
                        }
                        else -> {
                            gate.clearPassword()
                            prefs.lockdownWaitMinutes = waits[which - 2]
                            prefs.lockdownUnlock = Lockdown.Unlock.WAIT.ordinal
                            Lockdown.cancelWait(prefs)
                            render()
                        }
                    }
                }
                if (stricter) change() else loosen(change)
            }
            .show()
    }

    private fun lockName(lock: Lockdown.Lock) = getString(when (lock) {
        Lockdown.Lock.APP_LIST -> R.string.lockdown_lock_app_list
        Lockdown.Lock.APP_CHANGES -> R.string.lockdown_lock_app_changes
        Lockdown.Lock.HIDDEN_APPS -> R.string.lockdown_lock_hidden_apps
        Lockdown.Lock.LAUNCHER -> R.string.lockdown_lock_launcher
        Lockdown.Lock.PROTECT -> R.string.lockdown_lock_protect
    })

    /** A tick for each thing lockdown locks. Unticking one loosens lockdown. */
    private fun pickLocks() {
        val context = requireContext()
        val locks = Lockdown.Lock.values()
        val ticked = locks.map { Lockdown.enabled(prefs, it) }.toBooleanArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.lockdown_whats_locked)
            .setMultiChoiceItems(locks.map { lockName(it) }.toTypedArray(), ticked) { _, i, on -> ticked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val loosens = locks.indices.any { Lockdown.enabled(prefs, locks[it]) && !ticked[it] }
                val protectAdded = !prefs.lockdownProtect && ticked[locks.indexOf(Lockdown.Lock.PROTECT)]
                val apply = {
                    locks.forEachIndexed { i, lock -> Lockdown.setEnabled(prefs, lock, ticked[i]) }
                    render()
                    if (protectAdded && !isAccessServiceEnabled(context)) askAccessibility()
                }
                if (loosens) loosen(apply) else apply()
            }
            .show()
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
                val now = LocalTime.now()
                TimePickerDialog(context, { _, hour, minute ->
                    val at = LocalDate.now().atTime(hour, minute).let { if (it.isAfter(java.time.LocalDateTime.now())) it else it.plusDays(1) }
                    val total = java.time.Duration.between(java.time.LocalDateTime.now(), at).toMillis()
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

    private fun scheduleName(schedule: Lockdown.Schedule): String {
        val context = requireContext()
        val days = when (schedule.days) {
            Lockdown.EVERY_DAY -> getString(R.string.lockdown_every_day)
            Lockdown.WEEKDAYS -> getString(R.string.lockdown_weekdays)
            Lockdown.WEEKENDS -> getString(R.string.lockdown_weekends)
            else -> java.time.DayOfWeek.values().filter { schedule.on(it) }
                .joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }
        }
        if (schedule.start == schedule.end) return days + " · " + getString(R.string.lockdown_all_day)
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        fun time(minutes: Int) = LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        return days + " · " + time(schedule.start) + "–" + time(schedule.end)
    }

    /** The schedules: tapping one removes it, which loosens lockdown */
    private fun showSchedules() {
        val schedules = Lockdown.schedules(prefs)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.lockdown_schedules)
            .setItems(schedules.map { scheduleName(it) }.toTypedArray()) { _, i ->
                val schedule = schedules[i]
                AlertDialog.Builder(requireContext())
                    .setTitle(scheduleName(schedule))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.lockdown_remove) { _, _ ->
                        loosen {
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

    /** The days, then when it starts and ends */
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
                        prefs.lockdownSchedules = Lockdown.formatSchedules(Lockdown.schedules(prefs) + schedule)
                        render()
                    }, 7, 0, is24).apply { setTitle(R.string.lockdown_schedule_ends) }.show()
                }, 22, 0, is24).apply { setTitle(R.string.lockdown_schedule_starts) }.show()
            }
            .show()
    }

    private fun toggleClue() {
        if (prefs.recoveryClue) {
            prefs.recoveryClue = false
            prefs.recoveryClueText = ""
            return render()
        }
        val input = EditText(requireContext()).apply { hint = getString(R.string.enter_clue) }
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

    // --- Backup ---

    private fun backupSection() {
        val context = requireContext()
        val card = Card(getString(R.string.backup))
        val folder = BackupManager.backupFolder(context)
        card.body(
            row(card, getString(R.string.backup_folder), when {
                folder.isEmpty() -> getString(R.string.off)
                BackupManager.isFolderFailing(context) -> getString(R.string.backup_not_saving)
                else -> folderName(folder)
            }, R.string.info_backup_folder) {
                if (folder.isEmpty()) return@row pickFolder()
                AlertDialog.Builder(context)
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
        AlertDialog.Builder(requireContext())
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
        card.body(
            if (!Lockdown.isLocked(context, Lockdown.Lock.LAUNCHER, prefs)) row(card, getString(if (isDefault) R.string.change_default_launcher else R.string.set_as_default_launcher),
                null, R.string.info_default) { viewModel.resetLauncherLiveData.call() } else null,
            row(card, getString(R.string.tutorial), null, R.string.info_tutorial) { Tutorial.show(requireActivity()) },
            row(card, getString(R.string.app_info), null, R.string.info_app_info) {
                openAppInfo(context, Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            },
            row(card, getString(R.string.version), BuildConfig.VERSION_NAME, R.string.info_version, onTap = null),
        )
        showRestoreSummary()
    }

    override fun onDestroyView() {
        info?.dismiss()
        info = null
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
            textSize = 28f
            setTextColor(ink)
            setPadding(8.dpToPx(), 0, 14.dpToPx(), 0)
            contentDescription = context.getString(R.string.close)
            setOnClickListener { onBack() }
        })
        if (icon != null) addView(View(context).apply { background = StatIcons.drawable(icon, ink, 24.dpToPx()) },
            LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply { marginEnd = 12.dpToPx() })
        addView(TextView(context, null, 0, R.style.TextLarge).apply {
            text = title
            textSize = 28f
            setTextColor(ink)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
