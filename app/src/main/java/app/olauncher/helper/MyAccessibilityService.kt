package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.data.Prefs

class MyAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var moodPromptView: View? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val moodCheck = object : Runnable {
        override fun run() {
            checkMoodPrompt()
            handler.postDelayed(this, MOOD_CHECK_INTERVAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerScreenReceiver()
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
            handler.post(moodCheck)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
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

    override fun onInterrupt() {

    }

    override fun onDestroy() {
        handler.removeCallbacks(moodCheck)
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        removeMoodPrompt()
        super.onDestroy()
    }

    // --- Daily mood prompt ---

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(moodCheck)
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        handler.removeCallbacks(moodCheck)
                        handler.post(moodCheck)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun checkMoodPrompt() {
        if (moodPromptView != null) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) return
        if (MoodStore(this).isPromptDue()) showMoodPrompt()
    }

    private fun showMoodPrompt() {
        try {
            val context = themedContext()
            val view = LayoutInflater.from(context).inflate(R.layout.view_mood_prompt, null)
            MoodPrompt.bind(view) { mood ->
                MoodStore(this).answerPrompt(mood)
                removeMoodPrompt()
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
            moodPromptView = view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeMoodPrompt() {
        val view = moodPromptView ?: return
        moodPromptView = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Applies the launcher's own light/dark choice, which may differ from the system's. */
    private fun themedContext(): Context {
        val config = Configuration(resources.configuration)
        val nightMask = when (Prefs(this).appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            else -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMask
        return ContextThemeWrapper(createConfigurationContext(config), R.style.AppTheme)
    }

    companion object {
        private const val MOOD_CHECK_INTERVAL = 30_000L
    }
}
