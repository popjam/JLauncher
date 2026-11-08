package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.MoodStore
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.Arrangement
import app.olauncher.helper.MoodPrompt
import app.olauncher.helper.Spending
import app.olauncher.helper.SwipeTargets
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.Weather
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.isPackageStillInstalled
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.openSearch
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        underRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        binding.mainLayout.addView(underRow, binding.mainLayout.indexOfChild(binding.moneyWidget),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        appsPadding = binding.homeAppsLayout.paddingTop to binding.homeAppsLayout.paddingBottom
        clockMarginTop = (binding.dateTimeLayout.layoutParams as FrameLayout.LayoutParams).topMargin
        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    /** Keeps the battery in the date line current while home shows */
    private val batteryChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (_binding != null) populateDateTime()
            runCatching { app.olauncher.helper.Battery.record(context) }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(requireContext(), batteryChanged,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        populateHomeScreen(false)
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
        showMoodPromptIfDue()
        scheduleMidnightRefresh()
    }

    override fun onPause() {
        runCatching { requireContext().unregisterReceiver(batteryChanged) }
        handler.removeCallbacks(midnightRefresh)
        handler.removeCallbacks(moodPromptCheck)
        super.onPause()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The date and today's screen time only refresh on resume, so roll them over if home is open at midnight. */
    private val midnightRefresh = Runnable {
        if (_binding == null) return@Runnable
        populateDateTime()
        // Hides a prompt left open from yesterday
        showMoodPromptIfDue()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && requireContext().appUsagePermissionGranted())
            viewModel.getTodaysScreenTime(force = true)
        viewModel.getWeather(force = true)
        scheduleMidnightRefresh()
    }

    private fun scheduleMidnightRefresh() {
        handler.removeCallbacks(midnightRefresh)
        val nextMidnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // A second late, so "today" has definitely changed
        handler.postDelayed(midnightRefresh, nextMidnight - System.currentTimeMillis() + 1000)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> {
                if (prefs.lockdownModeOn && prefs.lockdownChangeLauncher) return
                viewModel.resetLauncherLiveData.call()
            }
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
            R.id.moodAvatar -> openMoodTracker()
            R.id.weatherIcon, R.id.tvWeather -> openWeather()
            R.id.moneyWidget -> openMoney()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.moodAvatar -> runCatching {
                findNavController().navigate(R.id.action_mainFragment_to_statFragment, bundleOf(StatFragment.STAT_ID to MoodStore.MOOD))
            }
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            // Picking one replaces the old; backing out keeps it
            R.id.clock -> showAppList(Constants.FLAG_SET_CLOCK_APP)

            R.id.date -> showAppList(Constants.FLAG_SET_CALENDAR_APP)

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not()
                    && prefs.hideSetDefaultLauncher.not()
                    && !(prefs.lockdownModeOn && prefs.lockdownChangeLauncher)
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        viewModel.screenTimeMillis.observe(viewLifecycleOwner) {
            it ?: return@observe
            // Every device's screen time together, so the face goes by Screen time's hours
            binding.moodAvatar.mood = MoodStore.moodForTotalUsage(it)
            binding.moodAvatar.visibility = View.VISIBLE
        }
        viewModel.weather.observe(viewLifecycleOwner) {
            val today = it?.today
            binding.weatherColumn.isVisible = today != null
            today ?: return@observe
            // Today's conditions, coloured by the temperature now
            val now = it.upcomingHours(1).firstOrNull()
            binding.weatherIcon.code = today.code
            binding.weatherIcon.tint = Weather.tempColor(now?.temp ?: today.max, it.fahrenheit)
            binding.weatherIcon.contentDescription = Weather.conditionName(requireContext(), today.code)
            binding.tvWeather.text = Weather.homeText(today, now?.uv ?: today.uv)
        }
        binding.mainLayout.viewTreeObserver.addOnGlobalLayoutListener { alignScreenTimeWithDateTime() }
        viewModel.showRecentApps.observe(viewLifecycleOwner) {
            binding.recents.performClick()
        }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        // Holding an app and letting go picks another; holding and dragging moves it
        appViews.forEach { app ->
            app.setOnTouchListener(HoldToDrag(getViewSwipeTouchListener(context, app), onHold = { onLongClick(it) }) { view, x, y -> startAppDrag(view, x, y) })
        }
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)
        binding.moodAvatar.setOnClickListener(this)
        binding.moodAvatar.setOnLongClickListener(this)
        binding.moneyWidget.setOnClickListener(this)
        binding.weatherIcon.setOnClickListener(this)
        binding.tvWeather.setOnClickListener(this)
        // Holding and dragging moves them; holding and letting go does what holding always did
        listOf(binding.clock, binding.date).forEach { it.setOnTouchListener(HoldToDrag { _, x, y -> startClockDrag(x, y) }) }
        listOf(binding.weatherIcon to Arrangement.WEATHER, binding.tvWeather to Arrangement.WEATHER,
            binding.moodAvatar to Arrangement.SCREEN_TIME, binding.tvScreenTime to Arrangement.SCREEN_TIME,
            binding.moneyWidget to Arrangement.MONEY).forEach { (view, key) ->
            view.setOnTouchListener(HoldToDrag { _, x, y -> startWidgetDrag(key, x, y) })
        }
    }

    // --- Moving things on the home screen ---

    private val appViews
        get() = listOf(binding.homeApp1, binding.homeApp2, binding.homeApp3, binding.homeApp4,
            binding.homeApp5, binding.homeApp6, binding.homeApp7, binding.homeApp8)

    /** The row under the one beside the clock (above it when the clock is at the bottom) */
    private lateinit var underRow: LinearLayout

    /** The apps' padding at the top and bottom, and the clock's margin, as laid out with the clock at the top */
    private var appsPadding = 0 to 0
    private var clockMarginTop = 0

    private fun pieceOf(key: String): View = when (key) {
        Arrangement.WEATHER -> binding.weatherColumn
        Arrangement.SCREEN_TIME -> binding.screenTimeColumn
        else -> binding.moneyWidget
    }

    private fun keyOf(piece: View) = when (piece) {
        binding.weatherColumn -> Arrangement.WEATHER
        binding.screenTimeColumn -> Arrangement.SCREEN_TIME
        else -> Arrangement.MONEY
    }

    /** Puts the weather, screen time and money in their rows, in their order */
    private fun arrangeWidgets() {
        val context = requireContext()
        val order = Arrangement.order(context, Arrangement.HOME, Arrangement.HOME_ITEMS)
        val under = Arrangement.underClock(context)
        listOf(binding.screenTimeLayout to order.filter { it !in under }, underRow to order.filter { it in under }).forEach { (row, keys) ->
            keys.forEachIndexed { i, key ->
                val piece = pieceOf(key)
                if (piece.parent == row && row.indexOfChild(piece) == i) return@forEachIndexed
                (piece.parent as? ViewGroup)?.removeView(piece)
                row.addView(piece, minOf(i, row.childCount), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    /** The clock at the top or the bottom, the apps keeping clear of it */
    private fun placeClock() {
        val bottom = Arrangement.clockAtBottom(requireContext())
        val params = binding.dateTimeLayout.layoutParams as FrameLayout.LayoutParams
        val gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP
        if (params.gravity != gravity) {
            params.gravity = gravity
            params.topMargin = if (bottom) 0 else clockMarginTop
            params.bottomMargin = if (bottom) 64.dpToPx() else 0
            binding.dateTimeLayout.layoutParams = params
        }
        val (top, end) = appsPadding
        val apps = binding.homeAppsLayout
        if (bottom) apps.setPadding(apps.paddingLeft, end, apps.paddingRight, top + 64.dpToPx())
        else apps.setPadding(apps.paddingLeft, top, apps.paddingRight, end)
    }

    private fun hideZone(label: Int = R.string.hide) = DragSession.Zone(HIDE, getString(label), Gravity.TOP or Gravity.CENTER_HORIZONTAL)

    private fun hiddenToast() = requireContext().showToast(getString(R.string.hidden_turn_on))

    /** An app, moved among the others, to another page, or off the home screen */
    private fun startAppDrag(view: View, x: Float, y: Float): DragSession? {
        val layout = binding.homeAppsLayout
        val list = ListDrag(layout, null) { it in appViews }
        list.start(view)
        val page = prefs.homePage
        val pages = prefs.homePages
        val zones = mutableListOf(hideZone(R.string.remove_from_home))
        if (pages > 1) {
            val next = (page + 1) % pages
            val previous = (page - 1 + pages) % pages
            zones += DragSession.Zone(next, getString(R.string.to_page, next + 1) + "  ›", Gravity.END or Gravity.CENTER_VERTICAL)
            if (previous != next) zones += DragSession.Zone(previous, "‹  " + getString(R.string.to_page, previous + 1), Gravity.START or Gravity.CENTER_VERTICAL)
        }
        val slot = view.tag.toString().toIntOrNull() ?: return null
        return DragSession(view, x, y, zones = zones,
            onMove = { _, y -> list.move(y) }, onEnd = { list.stop() }) { key ->
            val context = requireContext()
            when (key) {
                HIDE -> prefs.removeHomeSlot(page, slot)
                is Int -> if (prefs.moveHomeSlotToPage(page, slot, key)) context.showToast(getString(R.string.moved_to_page, key + 1))
                    else context.showToast(getString(R.string.page_full, key + 1))
                else -> {
                    val order = (0 until layout.childCount).map { layout.getChildAt(it) }.filter { it in appViews && it.isVisible }
                        .mapNotNull { it.tag.toString().toIntOrNull() }
                    if (order != order.sorted()) prefs.reorderHomeSlots(page, order)
                }
            }
            // The views back in their places, showing the slots' new apps
            layout.post {
                if (_binding == null) return@post
                appViews.forEachIndexed { i, app ->
                    if (layout.getChildAt(i) != app) {
                        layout.removeView(app)
                        layout.addView(app, i)
                    }
                }
                hideHomeApps()
                populateHomeApps()
            }
        }
    }

    /** The clock, to the top or the bottom, or hidden */
    private fun startClockDrag(x: Float, y: Float): DragSession {
        val clock = binding.dateTimeLayout
        val main = IntArray(2).also { binding.mainLayout.getLocationOnScreen(it) }
        val here = IntArray(2).also { clock.getLocationOnScreen(it) }
        val width = clock.width.toFloat()
        val height = clock.height.toFloat()
        val left = here[0].toFloat()
        val top = main[1] + clockMarginTop.toFloat()
        val bottom = main[1] + binding.mainLayout.height - 64.dpToPx() - height
        return DragSession(clock, x, y, zones = listOf(hideZone()),
            slots = listOf(DragSession.Slot(TOP, android.graphics.RectF(left, top, left + width, top + height)),
                DragSession.Slot(BOTTOM, android.graphics.RectF(left, bottom, left + width, bottom + height)))) { key ->
            val context = requireContext()
            when (key) {
                HIDE -> {
                    prefs.dateTimeVisibility = Constants.DateTime.OFF
                    viewModel.toggleDateTime()
                    hiddenToast()
                }
                TOP, BOTTOM -> Arrangement.setClockAtBottom(context, key == BOTTOM)
            }
            placeClock()
            populateScreenTime()
        }
    }

    /** The weather, screen time or money, along its row, into the other row, or hidden */
    private fun startWidgetDrag(key: String, x: Float, y: Float): DragSession? {
        val piece = pieceOf(key)
        if (!piece.isVisible) return null
        val rows = listOf(binding.screenTimeLayout, underRow)
        val slots = mutableListOf<DragSession.Slot>()
        val pieceAt = IntArray(2).also { piece.getLocationOnScreen(it) }
        val w = piece.width.toFloat()
        val h = piece.height.toFloat()
        val clockBottom = Arrangement.clockAtBottom(requireContext())
        rows.forEachIndexed { r, row ->
            val others = (0 until row.childCount).map { row.getChildAt(it) }.filter { it != piece && it.isVisible && it.width > 0 }
            if (others.isEmpty()) {
                // A box where it would sit alone in the row
                val first = rows[0]
                val firstAt = IntArray(2).also { first.getLocationOnScreen(it) }
                val y = when {
                    r == 0 -> firstAt[1].toFloat()
                    clockBottom -> firstAt[1] - h
                    else -> firstAt[1] + maxOf(first.height.toFloat(), h)
                }
                val x = if (first.width > 0) firstAt[0] + first.width - w else pieceAt[0].toFloat()
                slots += DragSession.Slot(r to 0, android.graphics.RectF(x, y, x + w, y + h))
                return@forEachIndexed
            }
            val rowAt = IntArray(2).also { row.getLocationOnScreen(it) }
            val top = rowAt[1].toFloat()
            val bottom = top + row.height
            val edges = others.map { rowAt[0] + it.left.toFloat() to rowAt[0] + it.right.toFloat() }
            (0..others.size).forEach { k ->
                val x = when (k) {
                    0 -> edges.first().first
                    others.size -> edges.last().second
                    else -> (edges[k - 1].second + edges[k].first) / 2
                }
                slots += DragSession.Slot(r to k, android.graphics.RectF(x - 5.dpToPx(), top, x + 5.dpToPx(), bottom))
            }
        }
        return DragSession(piece, x, y, zones = listOf(hideZone()), slots = slots) { drop ->
            val context = requireContext()
            when (drop) {
                HIDE -> {
                    when (key) {
                        Arrangement.WEATHER -> {
                            prefs.weatherOn = false
                            viewModel.getWeather()
                        }
                        Arrangement.SCREEN_TIME -> Arrangement.save(context, Arrangement.HOME, Arrangement.order(context, Arrangement.HOME, Arrangement.HOME_ITEMS),
                            Arrangement.hidden(context, Arrangement.HOME) + Arrangement.SCREEN_TIME)
                        else -> Spending.setShowOnHome(context, false)
                    }
                    hiddenToast()
                }
                is Pair<*, *> -> {
                    val r = drop.first as Int
                    val k = drop.second as Int
                    // Every piece in each row, shown or not, in its order, and this one put at the k-th shown place
                    val keys = rows.map { row -> (0 until row.childCount).map { row.getChildAt(it) }.filter { it != piece } }
                    val target = keys[r]
                    val shown = target.filter { it.isVisible && it.width > 0 }
                    val before = shown.getOrNull(k)
                    val inRow = target.toMutableList().apply { add(if (before == null) size else indexOf(before), piece) }
                    val all = rows.indices.map { if (it == r) inRow else keys[it] }
                    Arrangement.save(context, Arrangement.HOME, all.flatten().map(::keyOf), Arrangement.hidden(context, Arrangement.HOME))
                    Arrangement.setUnderClock(context, all[1].map(::keyOf).toSet())
                }
            }
            populateScreenTime()
            populateMoney()
        }
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    /** Beside the clock: today's weather, then the mood avatar and screen time. */
    private fun populateScreenTime() {
        val context = requireContext()
        val showScreenTime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.appUsagePermissionGranted() &&
            app.olauncher.helper.Arrangement.SCREEN_TIME !in app.olauncher.helper.Arrangement.hidden(context, app.olauncher.helper.Arrangement.HOME)
        val showWeather = Weather.isEnabled(context)
        arrangeWidgets()
        placeClock()
        val moneyBeside = Arrangement.MONEY !in Arrangement.underClock(context) && Spending.showOnHome(context)
        binding.screenTimeColumn.isVisible = showScreenTime
        // Holds the avatar's place beside the weather icon until there's a screen time, so the texts line up
        binding.moodAvatar.visibility = when {
            showScreenTime && viewModel.screenTimeMillis.value != null -> View.VISIBLE
            showScreenTime && showWeather -> View.INVISIBLE
            else -> View.GONE
        }
        viewModel.getWeather()
        if (!showScreenTime && !showWeather && !moneyBeside) {
            binding.screenTimeLayout.visibility = View.GONE
            return
        }

        if (showScreenTime) viewModel.getTodaysScreenTime()
        binding.screenTimeLayout.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        // Once laid out, alignScreenTimeWithDateTime owns the top margin; resetting it here makes the
        // column jump down and back on every refresh
        val alignedTop = (binding.screenTimeLayout.layoutParams as FrameLayout.LayoutParams).topMargin
            .takeIf { binding.dateTimeLayout.isVisible && binding.dateTimeLayout.height > 0 }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = alignedTop ?: marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.screenTimeLayout.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 10.dpToPx())
    }

    /** What's left to spend this week, in pixel dollars, and the days until the week starts again */
    private fun populateMoney() {
        val context = requireContext()
        val week = Spending.thisWeek(context)?.takeIf { Spending.showOnHome(context) }
        binding.moneyWidget.isVisible = week != null
        week ?: return
        val (allowance, left) = week
        binding.moneyLeft.text = PixelMoneyView.figure(left)
        binding.moneyLeft.color = PixelMoneyView.colorFor(context, left, allowance)
        val today = LocalDate.now()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, today.with(java.time.temporal.TemporalAdjusters.next(app.olauncher.helper.Week.first))).toInt()
        binding.tvMoneyDays.text = resources.getQuantityString(R.plurals.days_left, days, days)
        binding.tvMoneyDays.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.date_size) * 0.88f)
        binding.moneyWidget.contentDescription = getString(R.string.money_left_description, PixelMoneyView.figure(left), binding.tvMoneyDays.text)
    }

    /**
     * Puts the row under the one beside the clock (over it when the clock is at the bottom), its outer
     * piece centred on the outer piece above; it may sit beside the apps.
     */
    private fun placeMoneyWidget() {
        val binding = _binding ?: return
        val row = underRow
        if (row.width == 0) return
        val layout = binding.screenTimeLayout
        val endSide = prefs.homeAlignment != Gravity.END
        fun outer(r: LinearLayout) = (0 until r.childCount).map { r.getChildAt(it) }.filter { it.isVisible }.let { if (endSide) it.lastOrNull() else it.firstOrNull() }
        val above = outer(layout).takeIf { layout.isVisible && layout.height > 0 }
        val mine = outer(row) ?: return
        val clockBottom = Arrangement.clockAtBottom(requireContext())
        val top: Int
        val centre: Int
        if (above != null) {
            top = if (clockBottom) layout.top - row.height else layout.top + layout.height
            centre = layout.left + above.left + above.width / 2
        } else {
            val margin = 10.dpToPx() + mine.width / 2
            top = if (clockBottom) binding.mainLayout.height - 64.dpToPx() - row.height else 72.dpToPx()
            centre = if (endSide) binding.mainLayout.width - margin else margin
        }
        val params = row.layoutParams as FrameLayout.LayoutParams
        val left = (centre - mine.left - mine.width / 2).coerceIn(0, maxOf(0, binding.mainLayout.width - row.width))
        if (params.topMargin != top.coerceAtLeast(0) || params.leftMargin != left) {
            params.topMargin = top.coerceAtLeast(0)
            params.leftMargin = left
            row.layoutParams = params
        }
    }

    private fun openMoney() {
        val id = Spending.moneyStat(MoodStore(requireContext()).load())?.id
        try {
            if (id == null) findNavController().navigate(R.id.action_mainFragment_to_statListFragment)
            else findNavController().navigate(R.id.action_mainFragment_to_statFragment, bundleOf(StatFragment.STAT_ID to id))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Keeps the avatar + screen time column vertically centred on the time and date. */
    private fun alignScreenTimeWithDateTime() {
        placeMoneyWidget()
        val binding = _binding ?: return
        val dateTime = binding.dateTimeLayout
        val screenTime = binding.screenTimeLayout
        if (!screenTime.isVisible || !dateTime.isVisible || dateTime.height == 0) return
        val top = (dateTime.top + (dateTime.height - screenTime.height) / 2).coerceAtLeast(0)
        val params = screenTime.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin != top) {
            params.topMargin = top
            screenTime.layoutParams = params
        }
    }

    private fun openWeather() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_weatherFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openMoodTracker() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_statListFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val moodPromptCheck = Runnable {
        if (_binding != null) showMoodPromptIfDue()
    }

    /** The day the shown mood prompt asks about */
    private var moodPromptDate: LocalDate? = null

    /**
     * The daily mood prompt only appears on the home screen. Dismissing it snoozes it for half an
     * hour, until the mood and daily stats are in or the day ends; a timer brings it back while home
     * stays open. A missed day can be asked about the next morning (see [MoodStore.nextPrompt]).
     */
    private fun showMoodPromptIfDue() {
        handler.removeCallbacks(moodPromptCheck)
        val moodStore = MoodStore(requireContext())
        val now = LocalDateTime.now()
        val next = moodStore.nextPrompt(now)
        if (next == null || next.at > now) {
            // E.g. today's mood was set from the mood tracker instead
            binding.moodPrompt.root.visibility = View.GONE
            moodPromptDate = null
            // Midnight is covered by midnightRefresh
            if (next != null) handler.postDelayed(moodPromptCheck, Duration.between(now, next.at).toMillis() + 1000)
            return
        }
        // Already showing: rebinding would wipe anything typed before leaving and coming back
        if (binding.moodPrompt.root.visibility == View.VISIBLE && moodPromptDate == next.date) return
        val data = moodStore.load()
        val date = next.date
        moodPromptDate = date
        MoodPrompt.bind(binding.moodPrompt.root, data, data.days[date.toString()], yesterday = date < now.toLocalDate(),
            onVoice = { addText -> recordVoiceNote(date, onText = { text -> if (_binding != null) addText(text) }) }) { faces, note ->
            moodStore.answerPrompt(date, faces, note)
            binding.moodPrompt.root.visibility = View.GONE
            moodPromptDate = null
            showMoodPromptIfDue()
        }
        binding.moodPrompt.root.visibility = View.VISIBLE
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()
        populatePageIndicator()

        populateScreenTime()
        populateMoney()

        populateHomeApps()
    }

    private fun populateHomeApps() {
        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                // Can't check right now (e.g. not the default launcher, or the profile is locked); keep it
                e.printStackTrace()
                textView.text = appName
                return true
            }
        }

        // Regular app check. Apps mid-update or in a paused profile have no launchable activity for a
        // moment but are still installed, so only clear the slot once the app is really gone.
        if (isPackageInstalled(requireContext(), packageName, userString)
            || isPackageStillInstalled(requireContext(), packageName, userString)
        ) {
            textView.text = appName
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    /**
     * Opens Stats or the stats' Home for a swipe set to it, sliding in from the side swiped from; dragging
     * it back the other way returns here. False for a swipe that opens an app.
     */
    private fun openSwipeStats(left: Boolean): Boolean {
        val destination = when (SwipeTargets.get(requireContext(), left)) {
            SwipeTargets.Target.APP -> return false
            SwipeTargets.Target.STATS -> R.id.statListFragment
            SwipeTargets.Target.STATS_HOME -> R.id.statsHomeFragment
        }
        val options = androidx.navigation.NavOptions.Builder()
            .setEnterAnim(if (left) R.anim.slide_in_right else R.anim.slide_in_left)
            .setExitAnim(R.anim.quick_fade_exit)
            .setPopEnterAnim(R.anim.quick_fade_enter)
            .setPopExitAnim(0)
            .build()
        try {
            findNavController().navigate(destination, bundleOf(SwipeTargets.SWIPE_BACK to if (left) 1 else -1), options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        if (openSwipeStats(left = false)) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        if (openSwipeStats(left = true)) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            runCatching {
                findNavController().navigate(
                    R.id.appListFragment,
                    bundleOf(
                        Constants.Key.FLAG to flag,
                        Constants.Key.RENAME to rename
                    )
                )
            }
            e.printStackTrace()
        }
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                if (!(prefs.lockdownModeOn && prefs.lockdownAppDrawer)) showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                // The first tap already turned the page; put it back before locking
                if (lastPageStep != 0) changePage(-lastPageStep)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (isAccessServiceEnabled(requireContext())) binding.lock.performClick()
                    else requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock))
                } else lockPhone()
            }

            // Every tap turns the page straight away, so rapid taps turn as many pages
            override fun onClick(e: MotionEvent) = changePageFromTap(e.x)

            // With double tap to lock on, the second tap locks instead
            override fun onSecondTap(e: MotionEvent) {
                if (!prefs.lockModeOn) changePageFromTap(e.x)
            }
        }
    }

    /** Last page change, so a double tap to lock can undo the page turn of its first tap. */
    private var lastPageStep = 0

    /** Tapping the left half shows the previous page and the right half the next, wrapping at the ends. */
    private fun changePageFromTap(x: Float) {
        changePage(if (x < binding.mainLayout.width / 2f) -1 else 1)
    }

    private fun changePage(step: Int) {
        val pages = prefs.homePages
        lastPageStep = 0
        if (pages < 2) return
        lastPageStep = step
        prefs.homePage = (prefs.homePage + step + pages) % pages
        // Only the home apps differ between pages, so leave the clock and screen time alone
        hideHomeApps()
        populatePageIndicator()
        populateHomeApps()
    }

    private fun populatePageIndicator() {
        val indicator = binding.pageIndicator
        val pages = prefs.homePages
        indicator.isVisible = pages > 1
        if (pages < 2) return
        if (indicator.childCount != pages) {
            indicator.removeAllViews()
            repeat(pages) {
                indicator.addView(View(requireContext()).apply {
                    setBackgroundResource(R.drawable.page_dot)
                    layoutParams = LinearLayout.LayoutParams(5.dpToPx(), 5.dpToPx()).apply {
                        marginStart = 3.dpToPx()
                        marginEnd = 3.dpToPx()
                    }
                })
            }
        }
        val current = prefs.homePage
        for (i in 0 until pages) indicator.getChildAt(i).alpha = if (i == current) 1f else 0.3f
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                if (!(prefs.lockdownModeOn && prefs.lockdownAppDrawer)) showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Drop places: off the home screen, and the clock's top and bottom */
        const val HIDE = "hide"
        const val TOP = "top"
        const val BOTTOM = "bottom"
    }
}