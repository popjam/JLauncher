package app.olauncher.data

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

class Prefs(context: Context) {
    companion object {
        const val FILE_NAME = "app.olauncher"
        const val MAX_HOME_PAGES = 5
    }

    private val PREFS_FILENAME = FILE_NAME

    private val FIRST_OPEN = "FIRST_OPEN"
    private val FIRST_OPEN_TIME = "FIRST_OPEN_TIME"
    private val FIRST_SETTINGS_OPEN = "FIRST_SETTINGS_OPEN"
    private val FIRST_HIDE = "FIRST_HIDE"
    private val USER_STATE = "USER_STATE"
    private val LOCK_MODE = "LOCK_MODE"
    private val LOCK_MODE_PENDING = "LOCK_MODE_PENDING"
    private val HOME_APPS_NUM = "HOME_APPS_NUM"
    private val HOME_PAGES = "HOME_PAGES"
    private val HOME_PAGE = "HOME_PAGE"
    private val PAUSE_APPS_ON = "PAUSE_APPS_ON"
    private val PAUSED_APPS = "PAUSED_APPS"
    private val PAUSE_APPS_REVIEWED = "PAUSE_APPS_REVIEWED"
    private val PAUSE_SECONDS = "PAUSE_SECONDS"
    private val USE_FOR_ON = "USE_FOR_ON"
    private val AUTO_SHOW_KEYBOARD = "AUTO_SHOW_KEYBOARD"
    private val KEYBOARD_MESSAGE = "KEYBOARD_MESSAGE"
    private val DAILY_WALLPAPER = "DAILY_WALLPAPER"
    private val DAILY_WALLPAPER_URL = "DAILY_WALLPAPER_URL"
    private val HOME_ALIGNMENT = "HOME_ALIGNMENT"
    private val HOME_BOTTOM_ALIGNMENT = "HOME_BOTTOM_ALIGNMENT"
    private val APP_LABEL_ALIGNMENT = "APP_LABEL_ALIGNMENT"
    private val STATUS_BAR = "STATUS_BAR"
    private val DATE_TIME_VISIBILITY = "DATE_TIME_VISIBILITY"
    private val SWIPE_LEFT_ENABLED = "SWIPE_LEFT_ENABLED"
    private val SWIPE_RIGHT_ENABLED = "SWIPE_RIGHT_ENABLED"
    private val HIDDEN_APPS = "HIDDEN_APPS"
    private val HIDDEN_APPS_UPDATED = "HIDDEN_APPS_UPDATED"
    private val SHOW_HINT_COUNTER = "SHOW_HINT_COUNTER"
    private val APP_THEME = "APP_THEME"
    private val ABOUT_CLICKED = "ABOUT_CLICKED"
    private val RATE_CLICKED = "RATE_CLICKED"
    private val WALLPAPER_MSG_SHOWN = "WALLPAPER_MSG_SHOWN"
    private val SHARE_SHOWN_TIME = "SHARE_SHOWN_TIME"
    private val SWIPE_DOWN_ACTION = "SWIPE_DOWN_ACTION"
    private val TEXT_SIZE_SCALE = "TEXT_SIZE_SCALE"
    private val PRO_MESSAGE_SHOWN = "PRO_MESSAGE_SHOWN"
    private val HIDE_SET_DEFAULT_LAUNCHER = "HIDE_SET_DEFAULT_LAUNCHER"
    private val SCREEN_TIME_LAST_UPDATED = "SCREEN_TIME_LAST_UPDATED"
    private val WEATHER_ON = "WEATHER_ON"
    private val WEATHER_DATA = "WEATHER_DATA"
    private val WEATHER_LAST_UPDATED = "WEATHER_LAST_UPDATED"
    private val WEATHER_LATITUDE = "WEATHER_LATITUDE"
    private val WEATHER_PLACE = "WEATHER_PLACE"
    private val WEATHER_LONGITUDE = "WEATHER_LONGITUDE"
    private val USAGE_HISTORY_LAST_UPDATED = "USAGE_HISTORY_LAST_UPDATED"
    private val LAUNCHER_RESTART_TIMESTAMP = "LAUNCHER_RECREATE_TIMESTAMP"
    private val SHOWN_ON_DAY_OF_YEAR = "SHOWN_ON_DAY_OF_YEAR"
    private val HOME_BUTTON_SHOW_RECENTS = "HOME_BUTTON_SHOW_RECENTS"
    private val LOCKDOWN_MODE_ON = "LOCKDOWN_MODE_ON"
    private val LOCKDOWN_PASSWORD_HASH = "LOCKDOWN_PASSWORD_HASH"
    private val LOCKDOWN_APP_DRAWER = "LOCKDOWN_APP_DRAWER"
    private val LOCKDOWN_HOLD_APP = "LOCKDOWN_HOLD_APP"
    private val LOCKDOWN_HIDDEN_APPS = "LOCKDOWN_HIDDEN_APPS"
    private val LOCKDOWN_CHANGE_LAUNCHER = "LOCKDOWN_CHANGE_LAUNCHER"
    private val LOCKDOWN_PASSWORD_RECOVERY = "LOCKDOWN_PASSWORD_RECOVERY"
    private val LOCKDOWN_PASSWORD_PLAINTEXT = "LOCKDOWN_PASSWORD_PLAINTEXT"
    private val RECOVERY_TAP_500 = "RECOVERY_TAP_500"
    private val RECOVERY_CLUE = "RECOVERY_CLUE"
    private val RECOVERY_CLUE_TEXT = "RECOVERY_CLUE_TEXT"
    private val RECOVERY_ESSAY = "RECOVERY_ESSAY"
    private val RECOVERY_PAY = "RECOVERY_PAY"
    private val RECOVERY_PAY_AMOUNT = "RECOVERY_PAY_AMOUNT"

    private val APP_NAME_1 = "APP_NAME_1"
    private val APP_NAME_2 = "APP_NAME_2"
    private val APP_NAME_3 = "APP_NAME_3"
    private val APP_NAME_4 = "APP_NAME_4"
    private val APP_NAME_5 = "APP_NAME_5"
    private val APP_NAME_6 = "APP_NAME_6"
    private val APP_NAME_7 = "APP_NAME_7"
    private val APP_NAME_8 = "APP_NAME_8"
    private val APP_PACKAGE_1 = "APP_PACKAGE_1"
    private val APP_PACKAGE_2 = "APP_PACKAGE_2"
    private val APP_PACKAGE_3 = "APP_PACKAGE_3"
    private val APP_PACKAGE_4 = "APP_PACKAGE_4"
    private val APP_PACKAGE_5 = "APP_PACKAGE_5"
    private val APP_PACKAGE_6 = "APP_PACKAGE_6"
    private val APP_PACKAGE_7 = "APP_PACKAGE_7"
    private val APP_PACKAGE_8 = "APP_PACKAGE_8"
    private val APP_ACTIVITY_CLASS_NAME_1 = "APP_ACTIVITY_CLASS_NAME_1"
    private val APP_ACTIVITY_CLASS_NAME_2 = "APP_ACTIVITY_CLASS_NAME_2"
    private val APP_ACTIVITY_CLASS_NAME_3 = "APP_ACTIVITY_CLASS_NAME_3"
    private val APP_ACTIVITY_CLASS_NAME_4 = "APP_ACTIVITY_CLASS_NAME_4"
    private val APP_ACTIVITY_CLASS_NAME_5 = "APP_ACTIVITY_CLASS_NAME_5"
    private val APP_ACTIVITY_CLASS_NAME_6 = "APP_ACTIVITY_CLASS_NAME_6"
    private val APP_ACTIVITY_CLASS_NAME_7 = "APP_ACTIVITY_CLASS_NAME_7"
    private val APP_ACTIVITY_CLASS_NAME_8 = "APP_ACTIVITY_CLASS_NAME_8"
    private val APP_USER_1 = "APP_USER_1"
    private val APP_USER_2 = "APP_USER_2"
    private val APP_USER_3 = "APP_USER_3"
    private val APP_USER_4 = "APP_USER_4"
    private val APP_USER_5 = "APP_USER_5"
    private val APP_USER_6 = "APP_USER_6"
    private val APP_USER_7 = "APP_USER_7"
    private val APP_USER_8 = "APP_USER_8"

    private val APP_NAME_SWIPE_LEFT = "APP_NAME_SWIPE_LEFT"
    private val APP_NAME_SWIPE_RIGHT = "APP_NAME_SWIPE_RIGHT"
    private val APP_PACKAGE_SWIPE_LEFT = "APP_PACKAGE_SWIPE_LEFT"
    private val APP_PACKAGE_SWIPE_RIGHT = "APP_PACKAGE_SWIPE_RIGHT"
    private val APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT = "APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT"
    private val APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT = "APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT"
    private val APP_USER_SWIPE_LEFT = "APP_USER_SWIPE_LEFT"
    private val APP_USER_SWIPE_RIGHT = "APP_USER_SWIPE_RIGHT"
    private val CLOCK_APP_PACKAGE = "CLOCK_APP_PACKAGE"
    private val CLOCK_APP_USER = "CLOCK_APP_USER"
    private val CLOCK_APP_CLASS_NAME = "CLOCK_APP_CLASS_NAME"
    private val CALENDAR_APP_PACKAGE = "CALENDAR_APP_PACKAGE"
    private val CALENDAR_APP_USER = "CALENDAR_APP_USER"
    private val CALENDAR_APP_CLASS_NAME = "CALENDAR_APP_CLASS_NAME"
    private val SCREEN_TIME_APP_PACKAGE = "SCREEN_TIME_APP_PACKAGE"
    private val SCREEN_TIME_APP_USER = "SCREEN_TIME_APP_USER"
    private val SCREEN_TIME_APP_CLASS_NAME = "SCREEN_TIME_APP_CLASS_NAME"

    private val IS_SHORTCUT_1 = "IS_SHORTCUT_1"
    private val SHORTCUT_ID_1 = "SHORTCUT_ID_1"
    private val IS_SHORTCUT_2 = "IS_SHORTCUT_2"
    private val SHORTCUT_ID_2 = "SHORTCUT_ID_2"
    private val IS_SHORTCUT_3 = "IS_SHORTCUT_3"
    private val SHORTCUT_ID_3 = "SHORTCUT_ID_3"
    private val IS_SHORTCUT_4 = "IS_SHORTCUT_4"
    private val SHORTCUT_ID_4 = "SHORTCUT_ID_4"
    private val IS_SHORTCUT_5 = "IS_SHORTCUT_5"
    private val SHORTCUT_ID_5 = "SHORTCUT_ID_5"
    private val IS_SHORTCUT_6 = "IS_SHORTCUT_6"
    private val SHORTCUT_ID_6 = "SHORTCUT_ID_6"
    private val IS_SHORTCUT_7 = "IS_SHORTCUT_7"
    private val SHORTCUT_ID_7 = "SHORTCUT_ID_7"
    private val IS_SHORTCUT_8 = "IS_SHORTCUT_8"
    private val SHORTCUT_ID_8 = "SHORTCUT_ID_8"

    private val SHORTCUT_ID_SWIPE_LEFT = "SHORTCUT_ID_SWIPE_LEFT"
    private val IS_SHORTCUT_SWIPE_LEFT = "IS_SHORTCUT_SWIPE_LEFT"
    private val SHORTCUT_ID_SWIPE_RIGHT = "SHORTCUT_ID_SWIPE_RIGHT"
    private val IS_SHORTCUT_SWIPE_RIGHT = "IS_SHORTCUT_SWIPE_RIGHT"

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILENAME, 0)

    var firstOpen: Boolean
        get() = prefs.getBoolean(FIRST_OPEN, true)
        set(value) = prefs.edit { putBoolean(FIRST_OPEN, value).apply() }

    var firstOpenTime: Long
        get() = prefs.getLong(FIRST_OPEN_TIME, 0L)
        set(value) = prefs.edit { putLong(FIRST_OPEN_TIME, value).apply() }

    var firstSettingsOpen: Boolean
        get() = prefs.getBoolean(FIRST_SETTINGS_OPEN, true)
        set(value) = prefs.edit { putBoolean(FIRST_SETTINGS_OPEN, value).apply() }

    var firstHide: Boolean
        get() = prefs.getBoolean(FIRST_HIDE, true)
        set(value) = prefs.edit { putBoolean(FIRST_HIDE, value).apply() }

    var userState: String
        get() = prefs.getString(USER_STATE, Constants.UserState.START).toString()
        set(value) = prefs.edit { putString(USER_STATE, value).apply() }

    var lockModeOn: Boolean
        get() = prefs.getBoolean(LOCK_MODE, false)
        set(value) = prefs.edit { putBoolean(LOCK_MODE, value).apply() }

    /** Double tap to lock was turned on while the accessibility service was off; finish once it's enabled. */
    var lockModePending: Boolean
        get() = prefs.getBoolean(LOCK_MODE_PENDING, false)
        set(value) = prefs.edit { putBoolean(LOCK_MODE_PENDING, value).apply() }

    var autoShowKeyboard: Boolean
        get() = prefs.getBoolean(AUTO_SHOW_KEYBOARD, true)
        set(value) = prefs.edit { putBoolean(AUTO_SHOW_KEYBOARD, value).apply() }

    var keyboardMessageShown: Boolean
        get() = prefs.getBoolean(KEYBOARD_MESSAGE, false)
        set(value) = prefs.edit { putBoolean(KEYBOARD_MESSAGE, value).apply() }

    var dailyWallpaper: Boolean
        get() = prefs.getBoolean(DAILY_WALLPAPER, false)
        set(value) = prefs.edit { putBoolean(DAILY_WALLPAPER, value).apply() }

    var dailyWallpaperUrl: String
        get() = prefs.getString(DAILY_WALLPAPER_URL, "").toString()
        set(value) = prefs.edit { putString(DAILY_WALLPAPER_URL, value).apply() }

    var homeAppsNum: Int
        get() = prefs.getInt(HOME_APPS_NUM, 4)
        set(value) = prefs.edit { putInt(HOME_APPS_NUM, value).apply() }

    var homePages: Int
        get() = prefs.getInt(HOME_PAGES, 1).coerceIn(1, MAX_HOME_PAGES)
        set(value) = prefs.edit { putInt(HOME_PAGES, value).apply() }

    /** Page currently shown on the home screen; home app slots below are read and written for this page. */
    var homePage: Int
        get() = prefs.getInt(HOME_PAGE, 0).coerceIn(0, homePages - 1)
        set(value) = prefs.edit { putInt(HOME_PAGE, value).apply() }

    /** The first page keeps the original keys so existing home apps stay on it. */
    private fun slotKey(key: String, page: Int) = if (page == 0) key else "${key}_P$page"

    private fun page(key: String) = slotKey(key, homePage)

    var homeAlignment: Int
        get() = prefs.getInt(HOME_ALIGNMENT, Gravity.START)
        set(value) = prefs.edit { putInt(HOME_ALIGNMENT, value).apply() }

    var homeBottomAlignment: Boolean
        get() = prefs.getBoolean(HOME_BOTTOM_ALIGNMENT, false)
        set(value) = prefs.edit { putBoolean(HOME_BOTTOM_ALIGNMENT, value).apply() }

    var appLabelAlignment: Int
        get() = prefs.getInt(APP_LABEL_ALIGNMENT, Gravity.START)
        set(value) = prefs.edit { putInt(APP_LABEL_ALIGNMENT, value).apply() }

    var showStatusBar: Boolean
        get() = prefs.getBoolean(STATUS_BAR, false)
        set(value) = prefs.edit { putBoolean(STATUS_BAR, value).apply() }

    var dateTimeVisibility: Int
        get() = prefs.getInt(DATE_TIME_VISIBILITY, Constants.DateTime.ON)
        set(value) = prefs.edit { putInt(DATE_TIME_VISIBILITY, value).apply() }

    var swipeLeftEnabled: Boolean
        get() = prefs.getBoolean(SWIPE_LEFT_ENABLED, true)
        set(value) = prefs.edit { putBoolean(SWIPE_LEFT_ENABLED, value).apply() }

    var swipeRightEnabled: Boolean
        get() = prefs.getBoolean(SWIPE_RIGHT_ENABLED, true)
        set(value) = prefs.edit { putBoolean(SWIPE_RIGHT_ENABLED, value).apply() }

    var appTheme: Int
        get() = prefs.getInt(APP_THEME, AppCompatDelegate.MODE_NIGHT_YES)
        set(value) = prefs.edit { putInt(APP_THEME, value).apply() }

    var textSizeScale: Float
        get() = prefs.getFloat(TEXT_SIZE_SCALE, 1.0f)
        set(value) = prefs.edit { putFloat(TEXT_SIZE_SCALE, value).apply() }

    var proMessageShown: Boolean
        get() = prefs.getBoolean(PRO_MESSAGE_SHOWN, false)
        set(value) = prefs.edit { putBoolean(PRO_MESSAGE_SHOWN, value).apply() }

    var hideSetDefaultLauncher: Boolean
        get() = prefs.getBoolean(HIDE_SET_DEFAULT_LAUNCHER, false)
        set(value) = prefs.edit { putBoolean(HIDE_SET_DEFAULT_LAUNCHER, value).apply() }

    var screenTimeLastUpdated: Long
        get() = prefs.getLong(SCREEN_TIME_LAST_UPDATED, 0L)
        set(value) = prefs.edit { putLong(SCREEN_TIME_LAST_UPDATED, value).apply() }

    var weatherOn: Boolean
        get() = prefs.getBoolean(WEATHER_ON, false)
        set(value) = prefs.edit { putBoolean(WEATHER_ON, value).apply() }

    /** Open-Meteo response for the last forecast fetched, see Weather.cached. */
    var weatherData: String
        get() = prefs.getString(WEATHER_DATA, "").toString()
        set(value) = prefs.edit { putString(WEATHER_DATA, value).apply() }

    var weatherLastUpdated: Long
        get() = prefs.getLong(WEATHER_LAST_UPDATED, 0L)
        set(value) = prefs.edit { putLong(WEATHER_LAST_UPDATED, value).apply() }

    /** Suburb or town of the last known position. */
    var weatherPlace: String
        get() = prefs.getString(WEATHER_PLACE, "").toString()
        set(value) = prefs.edit { putString(WEATHER_PLACE, value).apply() }

    /** Last known position, NaN until the first fix. */
    var weatherLatitude: Float
        get() = prefs.getFloat(WEATHER_LATITUDE, Float.NaN)
        set(value) = prefs.edit { putFloat(WEATHER_LATITUDE, value).apply() }

    var weatherLongitude: Float
        get() = prefs.getFloat(WEATHER_LONGITUDE, Float.NaN)
        set(value) = prefs.edit { putFloat(WEATHER_LONGITUDE, value).apply() }

    var usageHistoryLastUpdated: Long
        get() = prefs.getLong(USAGE_HISTORY_LAST_UPDATED, 0L)
        set(value) = prefs.edit { putLong(USAGE_HISTORY_LAST_UPDATED, value).apply() }

    var launcherRestartTimestamp: Long
        get() = prefs.getLong(LAUNCHER_RESTART_TIMESTAMP, 0L)
        set(value) = prefs.edit { putLong(LAUNCHER_RESTART_TIMESTAMP, value).apply() }

    var shownOnDayOfYear: Int
        get() = prefs.getInt(SHOWN_ON_DAY_OF_YEAR, 0)
        set(value) = prefs.edit { putInt(SHOWN_ON_DAY_OF_YEAR, value).apply() }

    var homeButtonShowRecents: Boolean
        get() = prefs.getBoolean(HOME_BUTTON_SHOW_RECENTS, false)
        set(value) = prefs.edit { putBoolean(HOME_BUTTON_SHOW_RECENTS, value).apply() }

    var lockdownModeOn: Boolean
        get() = prefs.getBoolean(LOCKDOWN_MODE_ON, false)
        set(value) = prefs.edit { putBoolean(LOCKDOWN_MODE_ON, value).apply() }

    var lockdownPasswordHash: String
        get() = prefs.getString(LOCKDOWN_PASSWORD_HASH, "").toString()
        set(value) = prefs.edit { putString(LOCKDOWN_PASSWORD_HASH, value).apply() }

    val isLockdownPasswordSet: Boolean
        get() = lockdownPasswordHash.isNotEmpty()

    var lockdownAppDrawer: Boolean
        get() = prefs.getBoolean(LOCKDOWN_APP_DRAWER, true)
        set(value) = prefs.edit { putBoolean(LOCKDOWN_APP_DRAWER, value).apply() }

    var lockdownHoldApp: Boolean
        get() = prefs.getBoolean(LOCKDOWN_HOLD_APP, true)
        set(value) = prefs.edit { putBoolean(LOCKDOWN_HOLD_APP, value).apply() }

    var lockdownHiddenApps: Boolean
        get() = prefs.getBoolean(LOCKDOWN_HIDDEN_APPS, true)
        set(value) = prefs.edit { putBoolean(LOCKDOWN_HIDDEN_APPS, value).apply() }

    var lockdownChangeLauncher: Boolean
        get() = prefs.getBoolean(LOCKDOWN_CHANGE_LAUNCHER, true)
        set(value) = prefs.edit { putBoolean(LOCKDOWN_CHANGE_LAUNCHER, value).apply() }

    var passwordRecoveryEnabled: Boolean
        get() = prefs.getBoolean(LOCKDOWN_PASSWORD_RECOVERY, false)
        set(value) = prefs.edit { putBoolean(LOCKDOWN_PASSWORD_RECOVERY, value).apply() }

    var lockdownPasswordPlaintext: String
        get() = prefs.getString(LOCKDOWN_PASSWORD_PLAINTEXT, "").toString()
        set(value) = prefs.edit { putString(LOCKDOWN_PASSWORD_PLAINTEXT, value).apply() }

    var recoveryTap500: Boolean
        get() = prefs.getBoolean(RECOVERY_TAP_500, false)
        set(value) = prefs.edit { putBoolean(RECOVERY_TAP_500, value).apply() }

    var recoveryClue: Boolean
        get() = prefs.getBoolean(RECOVERY_CLUE, false)
        set(value) = prefs.edit { putBoolean(RECOVERY_CLUE, value).apply() }

    var recoveryClueText: String
        get() = prefs.getString(RECOVERY_CLUE_TEXT, "").toString()
        set(value) = prefs.edit { putString(RECOVERY_CLUE_TEXT, value).apply() }

    var recoveryEssay: Boolean
        get() = prefs.getBoolean(RECOVERY_ESSAY, false)
        set(value) = prefs.edit { putBoolean(RECOVERY_ESSAY, value).apply() }


    var recoveryPay: Boolean
        get() = prefs.getBoolean(RECOVERY_PAY, false)
        set(value) = prefs.edit { putBoolean(RECOVERY_PAY, value).apply() }

    var recoveryPayAmount: Int
        get() = prefs.getInt(RECOVERY_PAY_AMOUNT, 0)
        set(value) = prefs.edit { putInt(RECOVERY_PAY_AMOUNT, value).apply() }

    fun resetLockdownSettings() {
        lockdownModeOn = false
        lockdownPasswordHash = ""
        lockdownPasswordPlaintext = ""
        lockdownAppDrawer = true
        lockdownHoldApp = true
        lockdownHiddenApps = true
        lockdownChangeLauncher = true
        passwordRecoveryEnabled = false
        recoveryTap500 = false
        recoveryClue = false
        recoveryClueText = ""
        recoveryEssay = false
        recoveryPay = false
        recoveryPayAmount = 0
    }

    var hiddenApps: MutableSet<String>
        get() = prefs.getStringSet(HIDDEN_APPS, mutableSetOf()) as MutableSet<String>
        set(value) = prefs.edit { putStringSet(HIDDEN_APPS, value).apply() }

    /** Wellness: make chosen apps wait [pauseSeconds] before opening. */
    var pauseAppsOn: Boolean
        get() = prefs.getBoolean(PAUSE_APPS_ON, false)
        set(value) = prefs.edit { putBoolean(PAUSE_APPS_ON, value) }

    /** Package names that get the pause. Copies, since the set SharedPreferences returns mustn't be changed. */
    var pausedApps: Set<String>
        get() = prefs.getStringSet(PAUSED_APPS, emptySet())!!.toSet()
        set(value) = prefs.edit { putStringSet(PAUSED_APPS, value.toSet()) }

    /** Known addictive apps already added (or deliberately removed), so they aren't added again. */
    var pauseAppsReviewed: Set<String>
        get() = prefs.getStringSet(PAUSE_APPS_REVIEWED, emptySet())!!.toSet()
        set(value) = prefs.edit { putStringSet(PAUSE_APPS_REVIEWED, value.toSet()) }

    var pauseSeconds: Int
        get() = prefs.getInt(PAUSE_SECONDS, 10)
        set(value) = prefs.edit { putInt(PAUSE_SECONDS, value) }

    /** Wellness: ask how long to use a chosen app for, and close it when the time is up. */
    var useForOn: Boolean
        get() = prefs.getBoolean(USE_FOR_ON, false)
        set(value) = prefs.edit { putBoolean(USE_FOR_ON, value) }

    var hiddenAppsUpdated: Boolean
        get() = prefs.getBoolean(HIDDEN_APPS_UPDATED, false)
        set(value) = prefs.edit { putBoolean(HIDDEN_APPS_UPDATED, value).apply() }

    var toShowHintCounter: Int
        get() = prefs.getInt(SHOW_HINT_COUNTER, 1)
        set(value) = prefs.edit { putInt(SHOW_HINT_COUNTER, value).apply() }

    var aboutClicked: Boolean
        get() = prefs.getBoolean(ABOUT_CLICKED, false)
        set(value) = prefs.edit { putBoolean(ABOUT_CLICKED, value).apply() }

    var rateClicked: Boolean
        get() = prefs.getBoolean(RATE_CLICKED, false)
        set(value) = prefs.edit { putBoolean(RATE_CLICKED, value).apply() }

    var wallpaperMsgShown: Boolean
        get() = prefs.getBoolean(WALLPAPER_MSG_SHOWN, false)
        set(value) = prefs.edit { putBoolean(WALLPAPER_MSG_SHOWN, value).apply() }

    var shareShownTime: Long
        get() = prefs.getLong(SHARE_SHOWN_TIME, 0L)
        set(value) = prefs.edit { putLong(SHARE_SHOWN_TIME, value).apply() }

    var swipeDownAction: Int
        get() = prefs.getInt(SWIPE_DOWN_ACTION, Constants.SwipeDownAction.NOTIFICATIONS)
        set(value) = prefs.edit { putInt(SWIPE_DOWN_ACTION, value).apply() }

    var appName1: String
        get() = prefs.getString(page(APP_NAME_1), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_1), value).apply() }

    var appName2: String
        get() = prefs.getString(page(APP_NAME_2), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_2), value).apply() }

    var appName3: String
        get() = prefs.getString(page(APP_NAME_3), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_3), value).apply() }

    var appName4: String
        get() = prefs.getString(page(APP_NAME_4), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_4), value).apply() }

    var appName5: String
        get() = prefs.getString(page(APP_NAME_5), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_5), value).apply() }

    var appName6: String
        get() = prefs.getString(page(APP_NAME_6), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_6), value).apply() }

    var appName7: String
        get() = prefs.getString(page(APP_NAME_7), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_7), value).apply() }

    var appName8: String
        get() = prefs.getString(page(APP_NAME_8), "").toString()
        set(value) = prefs.edit { putString(page(APP_NAME_8), value).apply() }

    var appPackage1: String
        get() = prefs.getString(page(APP_PACKAGE_1), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_1), value).apply() }

    var appPackage2: String
        get() = prefs.getString(page(APP_PACKAGE_2), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_2), value).apply() }

    var appPackage3: String
        get() = prefs.getString(page(APP_PACKAGE_3), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_3), value).apply() }

    var appPackage4: String
        get() = prefs.getString(page(APP_PACKAGE_4), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_4), value).apply() }

    var appPackage5: String
        get() = prefs.getString(page(APP_PACKAGE_5), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_5), value).apply() }

    var appPackage6: String
        get() = prefs.getString(page(APP_PACKAGE_6), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_6), value).apply() }

    var appPackage7: String
        get() = prefs.getString(page(APP_PACKAGE_7), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_7), value).apply() }

    var appPackage8: String
        get() = prefs.getString(page(APP_PACKAGE_8), "").toString()
        set(value) = prefs.edit { putString(page(APP_PACKAGE_8), value).apply() }

    var appActivityClassName1: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_1), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_1), value).apply() }

    var appActivityClassName2: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_2), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_2), value).apply() }

    var appActivityClassName3: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_3), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_3), value).apply() }

    var appActivityClassName4: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_4), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_4), value).apply() }

    var appActivityClassName5: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_5), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_5), value).apply() }

    var appActivityClassName6: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_6), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_6), value).apply() }

    var appActivityClassName7: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_7), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_7), value).apply() }

    var appActivityClassName8: String?
        get() = prefs.getString(page(APP_ACTIVITY_CLASS_NAME_8), "").toString()
        set(value) = prefs.edit { putString(page(APP_ACTIVITY_CLASS_NAME_8), value).apply() }

    var appUser1: String
        get() = prefs.getString(page(APP_USER_1), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_1), value).apply() }

    var appUser2: String
        get() = prefs.getString(page(APP_USER_2), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_2), value).apply() }

    var appUser3: String
        get() = prefs.getString(page(APP_USER_3), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_3), value).apply() }

    var appUser4: String
        get() = prefs.getString(page(APP_USER_4), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_4), value).apply() }

    var appUser5: String
        get() = prefs.getString(page(APP_USER_5), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_5), value).apply() }

    var appUser6: String
        get() = prefs.getString(page(APP_USER_6), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_6), value).apply() }

    var appUser7: String
        get() = prefs.getString(page(APP_USER_7), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_7), value).apply() }

    var appUser8: String
        get() = prefs.getString(page(APP_USER_8), "").toString()
        set(value) = prefs.edit { putString(page(APP_USER_8), value).apply() }

    var appNameSwipeLeft: String
        get() = prefs.getString(APP_NAME_SWIPE_LEFT, "Camera").toString()
        set(value) = prefs.edit { putString(APP_NAME_SWIPE_LEFT, value).apply() }

    var appNameSwipeRight: String
        get() = prefs.getString(APP_NAME_SWIPE_RIGHT, "Phone").toString()
        set(value) = prefs.edit { putString(APP_NAME_SWIPE_RIGHT, value).apply() }

    var appPackageSwipeLeft: String
        get() = prefs.getString(APP_PACKAGE_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_SWIPE_LEFT, value).apply() }

    var appActivityClassNameSwipeLeft: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT, value).apply() }

    var appPackageSwipeRight: String
        get() = prefs.getString(APP_PACKAGE_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_SWIPE_RIGHT, value).apply() }

    var appActivityClassNameRight: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT, value).apply() }

    var appUserSwipeLeft: String
        get() = prefs.getString(APP_USER_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(APP_USER_SWIPE_LEFT, value).apply() }

    var appUserSwipeRight: String
        get() = prefs.getString(APP_USER_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(APP_USER_SWIPE_RIGHT, value).apply() }

    var clockAppPackage: String
        get() = prefs.getString(CLOCK_APP_PACKAGE, "").toString()
        set(value) = prefs.edit { putString(CLOCK_APP_PACKAGE, value).apply() }

    var clockAppUser: String
        get() = prefs.getString(CLOCK_APP_USER, "").toString()
        set(value) = prefs.edit { putString(CLOCK_APP_USER, value).apply() }

    var clockAppClassName: String?
        get() = prefs.getString(CLOCK_APP_CLASS_NAME, "").toString()
        set(value) = prefs.edit { putString(CLOCK_APP_CLASS_NAME, value).apply() }

    var calendarAppPackage: String
        get() = prefs.getString(CALENDAR_APP_PACKAGE, "").toString()
        set(value) = prefs.edit { putString(CALENDAR_APP_PACKAGE, value).apply() }

    var calendarAppUser: String
        get() = prefs.getString(CALENDAR_APP_USER, "").toString()
        set(value) = prefs.edit { putString(CALENDAR_APP_USER, value).apply() }

    var calendarAppClassName: String?
        get() = prefs.getString(CALENDAR_APP_CLASS_NAME, "").toString()
        set(value) = prefs.edit { putString(CALENDAR_APP_CLASS_NAME, value).apply() }

    var screenTimeAppPackage: String
        get() = prefs.getString(SCREEN_TIME_APP_PACKAGE, "").toString()
        set(value) = prefs.edit { putString(SCREEN_TIME_APP_PACKAGE, value).apply() }

    var screenTimeAppUser: String
        get() = prefs.getString(SCREEN_TIME_APP_USER, "").toString()
        set(value) = prefs.edit { putString(SCREEN_TIME_APP_USER, value).apply() }

    var screenTimeAppClassName: String?
        get() = prefs.getString(SCREEN_TIME_APP_CLASS_NAME, "").toString()
        set(value) = prefs.edit { putString(SCREEN_TIME_APP_CLASS_NAME, value).apply() }

    var isShortcut1: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_1), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_1), value) }

    var shortcutId1: String
        get() = prefs.getString(page(SHORTCUT_ID_1), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_1), value) }

    var isShortcut2: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_2), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_2), value) }

    var shortcutId2: String
        get() = prefs.getString(page(SHORTCUT_ID_2), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_2), value) }

    var isShortcut3: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_3), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_3), value) }

    var shortcutId3: String
        get() = prefs.getString(page(SHORTCUT_ID_3), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_3), value) }

    var isShortcut4: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_4), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_4), value) }

    var shortcutId4: String
        get() = prefs.getString(page(SHORTCUT_ID_4), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_4), value) }

    var isShortcut5: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_5), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_5), value) }

    var shortcutId5: String
        get() = prefs.getString(page(SHORTCUT_ID_5), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_5), value) }

    var isShortcut6: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_6), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_6), value) }

    var shortcutId6: String
        get() = prefs.getString(page(SHORTCUT_ID_6), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_6), value) }

    var isShortcut7: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_7), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_7), value) }

    var shortcutId7: String
        get() = prefs.getString(page(SHORTCUT_ID_7), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_7), value) }

    var isShortcut8: Boolean
        get() = prefs.getBoolean(page(IS_SHORTCUT_8), false)
        set(value) = prefs.edit { putBoolean(page(IS_SHORTCUT_8), value) }

    var shortcutId8: String
        get() = prefs.getString(page(SHORTCUT_ID_8), "").toString()
        set(value) = prefs.edit { putString(page(SHORTCUT_ID_8), value) }

    var shortcutIdSwipeLeft: String
        get() = prefs.getString(SHORTCUT_ID_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_SWIPE_LEFT, value) }

    var isShortcutSwipeLeft: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_SWIPE_LEFT, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_SWIPE_LEFT, value) }

    var shortcutIdSwipeRight: String
        get() = prefs.getString(SHORTCUT_ID_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_SWIPE_RIGHT, value) }

    var isShortcutSwipeRight: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_SWIPE_RIGHT, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_SWIPE_RIGHT, value) }

    fun getAppName(location: Int): String {
        return when (location) {
            1 -> prefs.getString(page(APP_NAME_1), "").toString()
            2 -> prefs.getString(page(APP_NAME_2), "").toString()
            3 -> prefs.getString(page(APP_NAME_3), "").toString()
            4 -> prefs.getString(page(APP_NAME_4), "").toString()
            5 -> prefs.getString(page(APP_NAME_5), "").toString()
            6 -> prefs.getString(page(APP_NAME_6), "").toString()
            7 -> prefs.getString(page(APP_NAME_7), "").toString()
            8 -> prefs.getString(page(APP_NAME_8), "").toString()
            else -> ""
        }
    }

    fun getAppPackage(location: Int): String {
        return when (location) {
            1 -> prefs.getString(page(APP_PACKAGE_1), "").toString()
            2 -> prefs.getString(page(APP_PACKAGE_2), "").toString()
            3 -> prefs.getString(page(APP_PACKAGE_3), "").toString()
            4 -> prefs.getString(page(APP_PACKAGE_4), "").toString()
            5 -> prefs.getString(page(APP_PACKAGE_5), "").toString()
            6 -> prefs.getString(page(APP_PACKAGE_6), "").toString()
            7 -> prefs.getString(page(APP_PACKAGE_7), "").toString()
            8 -> prefs.getString(page(APP_PACKAGE_8), "").toString()
            else -> ""
        }
    }

    fun getAppActivityClassName(location: Int): String {
        return when (location) {
            1 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_1), "").toString()
            2 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_2), "").toString()
            3 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_3), "").toString()
            4 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_4), "").toString()
            5 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_5), "").toString()
            6 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_6), "").toString()
            7 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_7), "").toString()
            8 -> prefs.getString(page(APP_ACTIVITY_CLASS_NAME_8), "").toString()
            else -> ""
        }
    }

    fun getAppUser(location: Int): String {
        return when (location) {
            1 -> prefs.getString(page(APP_USER_1), "").toString()
            2 -> prefs.getString(page(APP_USER_2), "").toString()
            3 -> prefs.getString(page(APP_USER_3), "").toString()
            4 -> prefs.getString(page(APP_USER_4), "").toString()
            5 -> prefs.getString(page(APP_USER_5), "").toString()
            6 -> prefs.getString(page(APP_USER_6), "").toString()
            7 -> prefs.getString(page(APP_USER_7), "").toString()
            8 -> prefs.getString(page(APP_USER_8), "").toString()
            else -> ""
        }
    }

    fun getShortcutId(location: Int): String {
        return when (location) {
            1 -> shortcutId1
            2 -> shortcutId2
            3 -> shortcutId3
            4 -> shortcutId4
            5 -> shortcutId5
            6 -> shortcutId6
            7 -> shortcutId7
            8 -> shortcutId8
            else -> ""
        }
    }

    fun getIsShortcut(location: Int): Boolean {
        return when (location) {
            1 -> isShortcut1
            2 -> isShortcut2
            3 -> isShortcut3
            4 -> isShortcut4
            5 -> isShortcut5
            6 -> isShortcut6
            7 -> isShortcut7
            8 -> isShortcut8
            else -> false
        }
    }

    fun setAppActivityClassName(location: Int, activityClassName: String) {
        when (location) {
            1 -> appActivityClassName1 = activityClassName
            2 -> appActivityClassName2 = activityClassName
            3 -> appActivityClassName3 = activityClassName
            4 -> appActivityClassName4 = activityClassName
            5 -> appActivityClassName5 = activityClassName
            6 -> appActivityClassName6 = activityClassName
            7 -> appActivityClassName7 = activityClassName
            8 -> appActivityClassName8 = activityClassName
        }
    }

    fun updateAppActivityClassName(packageName: String, activityClassName: String) {
        for (page in 0 until homePages) for (i in 1..8) {
            if (prefs.getString(slotKey("APP_PACKAGE_$i", page), "") == packageName)
                prefs.edit { putString(slotKey("APP_ACTIVITY_CLASS_NAME_$i", page), activityClassName) }
        }
        if (clockAppPackage == packageName) clockAppClassName = activityClassName
        if (calendarAppPackage == packageName) calendarAppClassName = activityClassName
        if (screenTimeAppPackage == packageName) screenTimeAppClassName = activityClassName
        if (appPackageSwipeLeft == packageName) appActivityClassNameSwipeLeft = activityClassName
        if (appPackageSwipeRight == packageName) appActivityClassNameRight = activityClassName
    }

    fun getAppRenameLabel(appPackage: String): String = prefs.getString(appPackage, "").toString()

    fun setAppRenameLabel(appPackage: String, renameLabel: String) = prefs.edit { putString(appPackage, renameLabel) }
}