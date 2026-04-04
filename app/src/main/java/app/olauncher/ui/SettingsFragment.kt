package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.animateAlpha
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.PasswordHelper
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openUrl
import app.olauncher.helper.rateApp
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.shareApp
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin

class SettingsFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val showPentastic = System.currentTimeMillis() % 2 == 0L
    private var recoveryTapCount = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isOlauncherDefault()

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)
        checkAdminPermission()

        binding.homeAppsNum.text = prefs.homeAppsNum.toString()
        populateProMessage()
        populateKeyboardText()
        populateScreenTimeOnOff()
        populateLockSettings()
        populateHomeButtonRecents()
        populateWallpaperText()
        populateAppThemeText()
        populateTextSize()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeApps()
        populateSwipeDownAction()
        populateLockdownSettings()
        populateActionHints()
        initClickListeners()
        initObservers()

        if (showPentastic)
            binding.footer.text = getText(R.string.new_app_minimal_todo_lists)
    }

    override fun onClick(view: View) {
        binding.appsNumSelectLayout.visibility = View.GONE
        binding.dateTimeSelectLayout.visibility = View.GONE
        binding.appThemeSelectLayout.visibility = View.GONE
        binding.swipeDownSelectLayout.visibility = View.GONE
        if (view.id != R.id.textSizeMinus && view.id != R.id.textSizePlus) {
            if (binding.textSizesLayout.isVisible) {
                binding.textSizesLayout.visibility = View.GONE
                applyTextSizeScale()
            }
        }
        if (view.id != R.id.alignmentBottom)
            binding.alignmentSelectLayout.visibility = View.GONE

        when (view.id) {
            R.id.olauncherHiddenApps -> {
                if (prefs.lockdownModeOn && prefs.lockdownHiddenApps) return
                showHiddenApps()
            }
            R.id.moreFeatures -> viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> {
                if (prefs.lockdownModeOn && prefs.lockdownChangeLauncher) return
                viewModel.resetLauncherLiveData.call()
            }
            R.id.lockdownModeStatus -> toggleLockdownMode()
            R.id.lockdownPasswordStatus -> handleLockdownPasswordClick()
            R.id.lockdownAppDrawerStatus -> toggleLockdownSetting({ prefs.lockdownAppDrawer }, { prefs.lockdownAppDrawer = it })
            R.id.lockdownHoldAppStatus -> toggleLockdownSetting({ prefs.lockdownHoldApp }, { prefs.lockdownHoldApp = it })
            R.id.lockdownHiddenAppsStatus -> toggleLockdownSetting({ prefs.lockdownHiddenApps }, { prefs.lockdownHiddenApps = it })
            R.id.lockdownChangeLauncherStatus -> toggleLockdownSetting({ prefs.lockdownChangeLauncher }, { prefs.lockdownChangeLauncher = it })
            R.id.lockdownPasswordRecoveryStatus -> toggleRecoverySetting()
            R.id.passwordRecoveryTitle -> onRecoveryTitleTapped()
            R.id.recoveryTap500Info -> showInfoDialog(getString(R.string.recovery_tap_500), getString(R.string.recovery_tap_500_info))
            R.id.recoveryTap500Status -> toggleRecoveryMethod({ prefs.recoveryTap500 }, { prefs.recoveryTap500 = it })
            R.id.recoveryClueInfo -> showInfoDialog(getString(R.string.recovery_clue), getString(R.string.recovery_clue_info))
            R.id.recoveryClueStatus -> onRecoveryClueToggle()
            R.id.recoveryEssayInfo -> showInfoDialog(getString(R.string.recovery_essay), getString(R.string.recovery_essay_info))
            R.id.recoveryEssayStatus -> toggleRecoveryMethod({ prefs.recoveryEssay }, { prefs.recoveryEssay = it })
            R.id.recoveryPayInfo -> showInfoDialog(getString(R.string.recovery_pay), getString(R.string.recovery_pay_info))
            R.id.recoveryPayStatus -> onRecoveryPayToggle()
            R.id.toggleLock -> toggleLockMode()
            R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.homeAppsNum -> binding.appsNumSelectLayout.visibility = View.VISIBLE
            R.id.dailyWallpaperUrl -> requireContext().openUrl(prefs.dailyWallpaperUrl)
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.alignment -> binding.alignmentSelectLayout.visibility = View.VISIBLE
            R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
            R.id.alignmentCenter -> viewModel.updateHomeAlignment(Gravity.CENTER)
            R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
            R.id.alignmentBottom -> updateHomeBottomAlignment()
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> binding.dateTimeSelectLayout.visibility = View.VISIBLE
            R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
            R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
            R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)
            R.id.appThemeText -> binding.appThemeSelectLayout.visibility = View.VISIBLE
            R.id.themeLight -> updateTheme(AppCompatDelegate.MODE_NIGHT_NO)
            R.id.themeDark -> updateTheme(AppCompatDelegate.MODE_NIGHT_YES)
            R.id.themeSystem -> updateTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            R.id.textSizeValue -> binding.textSizesLayout.visibility = View.VISIBLE
            R.id.actionAccessibility -> openAccessibilityService()
            R.id.closeAccessibility -> toggleAccessibilityVisibility(false)
            R.id.notWorking -> requireContext().openUrl(Constants.URL_DOUBLE_TAP)

            R.id.tvGestures -> binding.flSwipeDown.visibility = View.VISIBLE

            R.id.maxApps0 -> updateHomeAppsNum(0)
            R.id.maxApps1 -> updateHomeAppsNum(1)
            R.id.maxApps2 -> updateHomeAppsNum(2)
            R.id.maxApps3 -> updateHomeAppsNum(3)
            R.id.maxApps4 -> updateHomeAppsNum(4)
            R.id.maxApps5 -> updateHomeAppsNum(5)
            R.id.maxApps6 -> updateHomeAppsNum(6)
            R.id.maxApps7 -> updateHomeAppsNum(7)
            R.id.maxApps8 -> updateHomeAppsNum(8)

            R.id.textSizeMinus -> adjustTextSizePreview(-0.1f)
            R.id.textSizePlus -> adjustTextSizePreview(0.1f)

            R.id.swipeLeftApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_LEFT_APP)
            R.id.swipeRightApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_RIGHT_APP)
            R.id.swipeDownAction -> binding.swipeDownSelectLayout.visibility = View.VISIBLE
            R.id.notifications -> updateSwipeDownAction(Constants.SwipeDownAction.NOTIFICATIONS)
            R.id.search -> updateSwipeDownAction(Constants.SwipeDownAction.SEARCH)

            R.id.aboutOlauncher -> {
                prefs.aboutClicked = true
                requireContext().openUrl(Constants.URL_ABOUT_OLAUNCHER)
            }

            R.id.share -> requireActivity().shareApp()
            R.id.rate -> {
                prefs.rateClicked = true
                requireActivity().rateApp()
            }

            R.id.twitter -> requireContext().openUrl(Constants.URL_TWITTER_TANUJ)
            R.id.github -> requireContext().openUrl(Constants.URL_OLAUNCHER_GITHUB)
            R.id.privacy -> requireContext().openUrl(Constants.URL_OLAUNCHER_PRIVACY)
            R.id.footer -> {
                requireContext().openUrl(
                    if (showPentastic) Constants.URL_PENTASTIC else Constants.URL_NTS
                )
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
                requireContext().showToast(getString(R.string.alignment_changed))
            }

            R.id.dailyWallpaper -> removeWallpaper()
            R.id.appThemeText -> {
                binding.appThemeSelectLayout.visibility = View.VISIBLE
                binding.themeSystem.visibility = View.VISIBLE
            }

            R.id.swipeLeftApp -> toggleSwipeLeft()
            R.id.swipeRightApp -> toggleSwipeRight()
            R.id.toggleLock -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return true
    }

    private fun initClickListeners() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.lockdownModeStatus.setOnClickListener(this)
        binding.lockdownPasswordStatus.setOnClickListener(this)
        binding.lockdownAppDrawerStatus.setOnClickListener(this)
        binding.lockdownHoldAppStatus.setOnClickListener(this)
        binding.lockdownHiddenAppsStatus.setOnClickListener(this)
        binding.lockdownChangeLauncherStatus.setOnClickListener(this)
        binding.lockdownPasswordRecoveryStatus.setOnClickListener(this)
        binding.passwordRecoveryTitle.setOnClickListener(this)
        binding.recoveryTap500Info.setOnClickListener(this)
        binding.recoveryTap500Status.setOnClickListener(this)
        binding.recoveryClueInfo.setOnClickListener(this)
        binding.recoveryClueStatus.setOnClickListener(this)
        binding.recoveryEssayInfo.setOnClickListener(this)
        binding.recoveryEssayStatus.setOnClickListener(this)
        binding.recoveryPayInfo.setOnClickListener(this)
        binding.recoveryPayStatus.setOnClickListener(this)
        binding.scrollLayout.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.aboutOlauncher.setOnClickListener(this)
        binding.moreFeatures.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.toggleLock.setOnClickListener(this)
        binding.homeButtonRecents.setOnClickListener(this)
        binding.homeAppsNum.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.dailyWallpaperUrl.setOnClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.alignmentLeft.setOnClickListener(this)
        binding.alignmentCenter.setOnClickListener(this)
        binding.alignmentRight.setOnClickListener(this)
        binding.alignmentBottom.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.dateTimeOn.setOnClickListener(this)
        binding.dateTimeOff.setOnClickListener(this)
        binding.dateOnly.setOnClickListener(this)
        binding.swipeLeftApp.setOnClickListener(this)
        binding.swipeRightApp.setOnClickListener(this)
        binding.swipeDownAction.setOnClickListener(this)
        binding.search.setOnClickListener(this)
        binding.notifications.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.themeLight.setOnClickListener(this)
        binding.themeDark.setOnClickListener(this)
        binding.themeSystem.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.actionAccessibility.setOnClickListener(this)
        binding.closeAccessibility.setOnClickListener(this)
        binding.notWorking.setOnClickListener(this)

        binding.share.setOnClickListener(this)
        binding.rate.setOnClickListener(this)
        binding.twitter.setOnClickListener(this)
        binding.github.setOnClickListener(this)
        binding.privacy.setOnClickListener(this)
        binding.footer.setOnClickListener(this)

        binding.maxApps0.setOnClickListener(this)
        binding.maxApps1.setOnClickListener(this)
        binding.maxApps2.setOnClickListener(this)
        binding.maxApps3.setOnClickListener(this)
        binding.maxApps4.setOnClickListener(this)
        binding.maxApps5.setOnClickListener(this)
        binding.maxApps6.setOnClickListener(this)
        binding.maxApps7.setOnClickListener(this)
        binding.maxApps8.setOnClickListener(this)

        binding.textSizeMinus.setOnClickListener(this)
        binding.textSizePlus.setOnClickListener(this)

        binding.dailyWallpaper.setOnLongClickListener(this)
        binding.alignment.setOnLongClickListener(this)
        binding.appThemeText.setOnLongClickListener(this)
        binding.swipeLeftApp.setOnLongClickListener(this)
        binding.swipeRightApp.setOnLongClickListener(this)
        binding.toggleLock.setOnLongClickListener(this)
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            viewModel.showDialog.postValue(Constants.Dialog.ABOUT)
            prefs.firstSettingsOpen = false
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    private fun toggleSwipeLeft() {
        prefs.swipeLeftEnabled = !prefs.swipeLeftEnabled
        if (prefs.swipeLeftEnabled) {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_left_app_enabled))
        } else {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_left_app_disabled))
        }
    }

    private fun toggleSwipeRight() {
        prefs.swipeRightEnabled = !prefs.swipeRightEnabled
        if (prefs.swipeRightEnabled) {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_right_app_enabled))
        } else {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_right_app_disabled))
        }
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
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

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun checkAdminPermission() {
        val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            prefs.lockModeOn = isAdmin
    }

    private fun toggleAccessibilityVisibility(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            binding.notWorking.visibility = View.VISIBLE
        if (isAccessServiceEnabled(requireContext()))
            binding.actionAccessibility.text = getString(R.string.disable)
        binding.accessibilityLayout.isVisible = show
        binding.scrollView.animateAlpha(if (show) 0.5f else 1f)
    }

    private fun openAccessibilityService() {
        toggleAccessibilityVisibility(false)
        // prefs.lockModeOn = true
        populateLockSettings()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(requireContext())) {
                toggleAccessibilityVisibility(true)
                return
            }
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                removeActiveAdmin("Admin permission removed.")
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_permission_message)
                )
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun removeActiveAdmin(toastMessage: String? = null) {
        try {
            deviceManager.removeActiveAdmin(componentName) // for backward compatibility
            requireContext().showToast(toastMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeWallpaper() {
        if (requireContext().isEinkDisplay()) {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
            setPlainWallpaper(requireContext(), android.R.color.white)
        } else {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_YES
            setPlainWallpaper(requireContext(), android.R.color.black)
        }
        if (!prefs.dailyWallpaper) return
        prefs.dailyWallpaper = false
        populateWallpaperText()
        viewModel.cancelWallpaperWorker()
    }

    private fun toggleDailyWallpaperUpdate() {
        if (prefs.dailyWallpaper.not() && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && viewModel.isOlauncherDefault.value == false) {
            requireContext().showToast(R.string.set_as_default_launcher_first)
            return
        }
        prefs.dailyWallpaper = !prefs.dailyWallpaper
        populateWallpaperText()
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
            showWallpaperToasts()
        } else viewModel.cancelWallpaperWorker()
    }

    private fun showWallpaperToasts() {
        if (isOlauncherDefault(requireContext()))
            requireContext().showToast(getString(R.string.your_wallpaper_will_update_shortly))
        else
            requireContext().showToast(getString(R.string.olauncher_is_not_default_launcher), Toast.LENGTH_LONG)
    }

    private fun updateHomeAppsNum(num: Int) {
        binding.homeAppsNum.text = num.toString()
        binding.appsNumSelectLayout.visibility = View.GONE
        prefs.homeAppsNum = num
        viewModel.refreshHome(true)
    }

    private var pendingTextSizeScale: Float = -1f

    private fun adjustTextSizePreview(delta: Float) {
        val maxScale = if (isTablet(requireContext())) 2.0f else 1.5f
        val current = if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale
        val newScale = Math.round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(0.5f, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = String.format("%.1f", clamped)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun applyTextSizeScale() {
        if (pendingTextSizeScale < 0 || prefs.textSizeScale == pendingTextSizeScale) {
            pendingTextSizeScale = -1f
            return
        }
        prefs.textSizeScale = pendingTextSizeScale
        pendingTextSizeScale = -1f
        requireActivity().recreate()
    }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun updateTheme(appTheme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == appTheme) return
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        setAppTheme(appTheme)
    }

    private fun setAppTheme(theme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == theme) return
        if (prefs.dailyWallpaper) {
            setPlainWallpaper(theme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun setPlainWallpaper(appTheme: Int) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(requireContext(), android.R.color.black)
            AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(requireContext(), android.R.color.white)
            else -> {
                if (requireContext().isDarkThemeOn())
                    setPlainWallpaper(requireContext(), android.R.color.black)
                else setPlainWallpaper(requireContext(), android.R.color.white)
            }
        }
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> binding.appThemeText.text = getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> binding.appThemeText.text = getString(R.string.light)
            else -> binding.appThemeText.text = getString(R.string.system_default)
        }
    }

    private fun populateTextSize() {
        val formatted = String.format("%.1f", prefs.textSizeScale)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requireContext().appUsagePermissionGranted()) binding.screenTimeOnOff.text = getString(R.string.on)
            else binding.screenTimeOnOff.text = getString(R.string.off)
        } else binding.screenTimeLayout.visibility = View.GONE
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
    }

    private fun populateWallpaperText() {
        if (prefs.dailyWallpaper) binding.dailyWallpaper.text = getString(R.string.on)
        else binding.dailyWallpaper.text = getString(R.string.off)
    }

    private fun updateHomeBottomAlignment() {
        if (viewModel.isOlauncherDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_olauncher_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
        binding.alignmentBottom.text = if (prefs.homeBottomAlignment)
            getString(R.string.bottom_on)
        else getString(R.string.bottom_off)
    }

    private fun toggleHomeButtonRecents() {
        if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
            toggleAccessibilityVisibility(true)
            return
        }
        prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
        populateHomeButtonRecents()
    }

    private fun populateHomeButtonRecents() {
        binding.homeButtonRecents.text = getString(
            if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on
            else R.string.off
        )
    }

    private fun populateLockSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn && isAccessServiceEnabled(requireContext())) R.string.on
                else R.string.off
            )
        } else {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn) R.string.on
                else R.string.off
            )
        }
    }

    private fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            else -> getString(R.string.search)
        }
    }

    private fun updateSwipeDownAction(swipeDownFor: Int) {
        if (prefs.swipeDownAction == swipeDownFor) return
        prefs.swipeDownAction = swipeDownFor
        populateSwipeDownAction()
    }

    private fun populateSwipeApps() {
        binding.swipeLeftApp.text = prefs.appNameSwipeLeft
        binding.swipeRightApp.text = prefs.appNameSwipeRight
        if (!prefs.swipeLeftEnabled)
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
        if (!prefs.swipeRightEnabled)
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
    }

//    private fun populateDigitalWellbeing() {
//        binding.digitalWellbeing.isVisible = requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_PACKAGE_NAME).not()
//                && requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME).not()
//                && prefs.hideDigitalWellbeing.not()
//    }

    private fun showAppListIfEnabled(flag: Int) {
        if ((flag == Constants.FLAG_SET_SWIPE_LEFT_APP) and !prefs.swipeLeftEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        if ((flag == Constants.FLAG_SET_SWIPE_RIGHT_APP) and !prefs.swipeRightEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        viewModel.getAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    private fun populateActionHints() {
        if (prefs.aboutClicked.not())
            binding.aboutOlauncher.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
        if (viewModel.isOlauncherDefault.value != true) return
        if (prefs.rateClicked.not() && prefs.toShowHintCounter > Constants.HINT_RATE_US && prefs.toShowHintCounter < Constants.HINT_RATE_US + 100)
            binding.rate.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.arrow_down_float, 0, 0)
    }

    private fun populateProMessage() {
        if (prefs.proMessageShown.not() && prefs.userState == Constants.UserState.SHARE) {
            prefs.proMessageShown = true
            viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
        }
    }

    private fun populateLockdownSettings() {
        binding.lockdownModeStatus.text = getString(
            if (prefs.lockdownModeOn) R.string.on else R.string.off
        )
        binding.lockdownSettingsContainer.visibility =
            if (prefs.lockdownModeOn) View.VISIBLE else View.GONE
        if (prefs.lockdownModeOn) {
            binding.lockdownPasswordStatus.text = getString(
                if (prefs.isLockdownPasswordSet) R.string.on else R.string.off
            )
            binding.lockdownAppDrawerStatus.text = getString(
                if (prefs.lockdownAppDrawer) R.string.on else R.string.off
            )
            binding.lockdownHoldAppStatus.text = getString(
                if (prefs.lockdownHoldApp) R.string.on else R.string.off
            )
            binding.lockdownHiddenAppsStatus.text = getString(
                if (prefs.lockdownHiddenApps) R.string.on else R.string.off
            )
            binding.lockdownChangeLauncherStatus.text = getString(
                if (prefs.lockdownChangeLauncher) R.string.on else R.string.off
            )
            binding.lockdownPasswordRecoveryStatus.text = getString(
                if (prefs.passwordRecoveryEnabled) R.string.on else R.string.off
            )
        }
        // Hide set launcher button when restricted
        if (prefs.lockdownModeOn && prefs.lockdownChangeLauncher)
            binding.setLauncher.visibility = View.GONE
        else
            binding.setLauncher.visibility = View.VISIBLE
        // Show/hide recovery card
        binding.passwordRecoveryCard.visibility =
            if (prefs.lockdownModeOn && prefs.passwordRecoveryEnabled) View.VISIBLE else View.GONE
        if (prefs.passwordRecoveryEnabled) populateRecoveryStatuses()
    }

    private fun populateRecoveryStatuses() {
        binding.recoveryTap500Status.text = getString(if (prefs.recoveryTap500) R.string.on else R.string.off)
        binding.recoveryClueStatus.text = getString(if (prefs.recoveryClue) R.string.on else R.string.off)
        binding.recoveryEssayStatus.text = getString(if (prefs.recoveryEssay) R.string.on else R.string.off)
        binding.recoveryPayStatus.text = getString(if (prefs.recoveryPay) R.string.on else R.string.off)
    }

    private fun toggleLockdownMode() {
        if (prefs.lockdownModeOn) {
            // Turning OFF lockdown
            if (prefs.isLockdownPasswordSet) {
                requirePasswordThen {
                    prefs.resetLockdownSettings()
                    populateLockdownSettings()
                }
            } else {
                prefs.resetLockdownSettings()
                populateLockdownSettings()
            }
        } else {
            // Turning ON lockdown — free
            prefs.lockdownModeOn = true
            populateLockdownSettings()
        }
    }

    private fun toggleLockdownSetting(getter: () -> Boolean, setter: (Boolean) -> Unit) {
        val currentlyOn = getter()
        if (currentlyOn) {
            // Loosening restriction — requires password if set
            if (prefs.isLockdownPasswordSet) {
                requirePasswordThen {
                    setter(false)
                    populateLockdownSettings()
                }
            } else {
                setter(false)
                populateLockdownSettings()
            }
        } else {
            // Tightening restriction — free
            setter(true)
            populateLockdownSettings()
        }
    }

    private fun handleLockdownPasswordClick() {
        if (prefs.isLockdownPasswordSet) {
            requirePasswordThen { showPasswordSetOrRemoveDialog() }
        } else {
            showPasswordWarningThenSet()
        }
    }

    private fun showPasswordWarningThenSet() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.catastrophic_warning))
            .setMessage(getString(R.string.lockdown_password_warning))
            .setPositiveButton(getString(R.string.proceed)) { _, _ ->
                showSetNewPasswordDialog()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPasswordSetOrRemoveDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.lockdown_mode))
            .setItems(arrayOf(
                getString(R.string.change_lockdown_password),
                getString(R.string.remove_lockdown_password)
            )) { _, which ->
                when (which) {
                    0 -> showSetNewPasswordDialog()
                    1 -> {
                        prefs.lockdownPasswordHash = ""
                        populateLockdownSettings()
                        requireContext().showToast(getString(R.string.password_removed))
                    }
                }
            }
            .show()
    }

    private fun showSetNewPasswordDialog() {
        showPasswordInputDialog(getString(R.string.set_lockdown_password), showGenerate = true) { newPassword ->
            if (newPassword.length < 4) {
                requireContext().showToast(getString(R.string.password_too_short))
                return@showPasswordInputDialog
            }
            showPasswordInputDialog(getString(R.string.confirm_password), showGenerate = false) { confirmPassword ->
                if (newPassword == confirmPassword) {
                    prefs.lockdownPasswordHash = PasswordHelper.hashPassword(newPassword)
                    prefs.lockdownPasswordPlaintext = newPassword
                    populateLockdownSettings()
                    requireContext().showToast(getString(R.string.password_set_success))
                } else {
                    requireContext().showToast(getString(R.string.passwords_do_not_match))
                }
            }
        }
    }

    private fun requirePasswordThen(action: () -> Unit) {
        if (!prefs.isLockdownPasswordSet) {
            action()
            return
        }
        showPasswordInputDialog(getString(R.string.enter_password)) { password ->
            if (PasswordHelper.verifyPassword(password, prefs.lockdownPasswordHash)) {
                action()
            } else {
                requireContext().showToast(getString(R.string.password_incorrect))
            }
        }
    }

    private fun showPasswordInputDialog(
        title: String,
        showGenerate: Boolean = false,
        onPasswordEntered: (String) -> Unit,
    ) {
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.enter_password)
        }
        container.addView(editText)

        val toggleRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val eyeCheckbox = android.widget.CheckBox(requireContext()).apply {
            text = "Show"
            textSize = 14f
            setOnCheckedChangeListener { _, isChecked ->
                editText.inputType = if (isChecked)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                editText.setSelection(editText.text.length)
            }
        }
        toggleRow.addView(eyeCheckbox)
        container.addView(toggleRow)

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton(getString(R.string.okay)) { _, _ ->
                onPasswordEntered(editText.text.toString())
            }
            .setNegativeButton(getString(R.string.close), null)
        if (showGenerate) {
            builder.setNeutralButton(getString(R.string.generate_password)) { _, _ ->
                generateAndSetPassword(onPasswordEntered)
            }
        } else if (prefs.passwordRecoveryEnabled) {
            builder.setNeutralButton(getString(R.string.recover_password)) { _, _ ->
                showRecoveryOptionsDialog()
            }
        }
        builder.show()
    }

    private fun generateAndSetPassword(onPasswordEntered: (String) -> Unit) {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
        val password = (1..32).map { chars.random() }.joinToString("")
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.catastrophic_warning))
            .setMessage(getString(R.string.generated_password_warning, password))
            .setPositiveButton(getString(R.string.proceed)) { _, _ ->
                prefs.lockdownPasswordHash = PasswordHelper.hashPassword(password)
                prefs.lockdownPasswordPlaintext = password
                populateLockdownSettings()
                requireContext().showToast(getString(R.string.password_set_success))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRecoveryOptionsDialog() {
        val methods = mutableListOf<Pair<String, () -> Unit>>()
        if (prefs.recoveryTap500) methods.add(getString(R.string.recovery_tap_500) to {
            requireContext().showToast(getString(R.string.recovery_tap_500_info))
        })
        if (prefs.recoveryClue) methods.add(getString(R.string.recovery_clue) to { useRecoveryClue() })
        if (prefs.recoveryEssay) methods.add(getString(R.string.recovery_essay) to { useRecoveryEssay() })
        if (prefs.recoveryPay) methods.add(getString(R.string.recovery_pay) + " (${payAmounts[prefs.recoveryPayAmount]})" to { useRecoveryPay() })

        if (methods.isEmpty()) {
            requireContext().showToast(getString(R.string.no_recovery_methods))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.recover_password))
            .setItems(methods.map { it.first }.toTypedArray()) { _, which ->
                methods[which].second()
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    // --- Password Recovery ---

    private fun toggleRecoverySetting() {
        if (prefs.passwordRecoveryEnabled) {
            // Turning off recovery — requires password if set
            if (prefs.isLockdownPasswordSet) {
                requirePasswordThen {
                    prefs.passwordRecoveryEnabled = false
                    populateLockdownSettings()
                }
            } else {
                prefs.passwordRecoveryEnabled = false
                populateLockdownSettings()
            }
        } else {
            // Turning on recovery — requires password if set
            if (prefs.isLockdownPasswordSet) {
                requirePasswordThen {
                    prefs.passwordRecoveryEnabled = true
                    populateLockdownSettings()
                }
            } else {
                prefs.passwordRecoveryEnabled = true
                populateLockdownSettings()
            }
        }
    }

    private fun toggleRecoveryMethod(getter: () -> Boolean, setter: (Boolean) -> Unit) {
        if (prefs.isLockdownPasswordSet) {
            requirePasswordThen {
                setter(!getter())
                populateRecoveryStatuses()
            }
        } else {
            setter(!getter())
            populateRecoveryStatuses()
        }
    }

    private fun onRecoveryClueToggle() {
        if (prefs.recoveryClue) {
            // Turning off or viewing clue
            if (prefs.isLockdownPasswordSet) {
                requirePasswordThen {
                    prefs.recoveryClue = false
                    prefs.recoveryClueText = ""
                    populateRecoveryStatuses()
                }
            } else {
                prefs.recoveryClue = false
                prefs.recoveryClueText = ""
                populateRecoveryStatuses()
            }
        } else {
            // Turning on — prompt for clue text
            val action: () -> Unit = {
                val editText = EditText(requireContext()).apply {
                    hint = getString(R.string.enter_clue)
                    setPadding(48, 32, 48, 32)
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.enter_clue))
                    .setView(editText)
                    .setPositiveButton(getString(R.string.okay)) { _, _ ->
                        val clue = editText.text.toString().trim()
                        if (clue.isNotEmpty()) {
                            prefs.recoveryClue = true
                            prefs.recoveryClueText = clue
                            populateRecoveryStatuses()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            if (prefs.isLockdownPasswordSet) requirePasswordThen(action) else action()
        }
    }

    private val payAmounts = arrayOf("$3", "$9", "$20", "$50", "$99", "$299")

    private fun onRecoveryPayToggle() {
        if (prefs.recoveryPay) {
            if (prefs.isLockdownPasswordSet) {
                requirePasswordThen {
                    prefs.recoveryPay = false
                    prefs.recoveryPayAmount = 0
                    populateRecoveryStatuses()
                }
            } else {
                prefs.recoveryPay = false
                prefs.recoveryPayAmount = 0
                populateRecoveryStatuses()
            }
        } else {
            val action: () -> Unit = {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.select_amount))
                    .setItems(payAmounts) { _, which ->
                        prefs.recoveryPay = true
                        prefs.recoveryPayAmount = which
                        populateRecoveryStatuses()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            if (prefs.isLockdownPasswordSet) requirePasswordThen(action) else action()
        }
    }

    private fun onRecoveryTitleTapped() {
        if (!prefs.isLockdownPasswordSet || !prefs.recoveryTap500) return
        recoveryTapCount++
        if (recoveryTapCount >= 500) {
            recoveryTapCount = 0
            showPasswordRevealDialog()
        } else if (recoveryTapCount % 50 == 0) {
            requireContext().showToast(getString(R.string.recovery_tap_progress, recoveryTapCount))
        }
    }

    private fun showPasswordRevealDialog() {
        val password = prefs.lockdownPasswordPlaintext
        if (password.isEmpty()) return
        val textView = TextView(requireContext()).apply {
            text = password
            textSize = 20f
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.your_password_is))
            .setView(textView)
            .setPositiveButton(getString(R.string.okay), null)
            .show()
    }

    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.okay), null)
            .show()
    }

    // Recovery usage methods — called from password dialog "Recover password" button

    fun useRecoveryClue() {
        if (!prefs.recoveryClue || prefs.recoveryClueText.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.your_clue))
            .setMessage(prefs.recoveryClueText)
            .setPositiveButton(getString(R.string.okay), null)
            .show()
    }

    fun useRecoveryEssay() {
        if (!prefs.recoveryEssay) return
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.essay_prompt)
            setPadding(48, 32, 48, 32)
            maxLines = 10
            // Disable paste
            setCustomSelectionActionModeCallback(object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                    menu?.removeItem(android.R.id.paste)
                    return true
                }
                override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                    menu?.removeItem(android.R.id.paste)
                    return true
                }
                override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
                override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
            })
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.recovery_essay))
            .setMessage(getString(R.string.essay_prompt))
            .setView(editText)
            .setPositiveButton(getString(R.string.okay)) { _, _ ->
                val text = editText.text.toString()
                if (text.length >= 500) {
                    showPasswordRevealDialog()
                } else {
                    requireContext().showToast(getString(R.string.essay_too_short, text.length))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    fun useRecoveryPay() {
        requireContext().showToast(getString(R.string.recovery_pay_not_available))
    }

    override fun onDestroyView() {
        recoveryTapCount = 0
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }
}