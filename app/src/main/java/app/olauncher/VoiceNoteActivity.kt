package app.olauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import app.olauncher.data.Prefs
import app.olauncher.helper.VoiceNotes
import app.olauncher.ui.MIC_REQUEST
import app.olauncher.ui.missingForRecording
import app.olauncher.ui.showRecorder
import java.time.LocalDate

/**
 * The recording popup on its own, over whatever's open, for today's note. It's in the app list as
 * "Voice note", so a phone's programmable key (the Jelly Star's, say) can be set to open it.
 */
class VoiceNoteActivity : AppCompatActivity() {

    private var showing = false

    override fun attachBaseContext(context: Context) {
        val config = Configuration(context.resources.configuration)
        config.fontScale = Prefs(context).textSizeScale
        applyOverrideConfiguration(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(Prefs(this).appTheme)
        super.onCreate(savedInstanceState)
        val missing = missingForRecording(this)
        if (missing.isEmpty()) record() else requestPermissions(missing, MIC_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        // The notification is only nice to have: the mic is what's needed
        if (VoiceNotes.canRecord(this)) record()
        else {
            Toast.makeText(this, R.string.voice_allow_mic, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun record() {
        if (showing) return
        showing = true
        showRecorder(this, lifecycle, LocalDate.now(), onClosed = {
            showing = false
            if (!isFinishing) finish()
        })
    }
}
