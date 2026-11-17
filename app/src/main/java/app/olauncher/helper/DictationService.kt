package app.olauncher.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.olauncher.R
import app.olauncher.VoiceNoteActivity

/**
 * Keeps a [Dictation] recording going with the screen off, with a notification to pause or finish it
 * (on the lock screen too), and listens for headphones' play/pause button, a press of which finishes
 * it. Runs only while there's a recording.
 */
class DictationService : Service() {

    private var session: MediaSession? = null
    private val update: () -> Unit = { if (Dictation.active) show() else stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voice_note_app), NotificationManager.IMPORTANCE_LOW))
        Dictation.addListener(update)
        listenToHeadphones()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> Dictation.togglePause()
            DONE -> Dictation.done(this)
        }
        if (!Dictation.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification(), type) }
            .onFailure { e -> e.printStackTrace() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Dictation.removeListener(update)
        session?.run {
            isActive = false
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun show() {
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notify_mic)
        .setContentTitle(getString(if (Dictation.recorder?.paused == true) R.string.voice_paused else R.string.voice_recording))
        .setOngoing(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Tapping it brings the recording popup back
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, VoiceNoteActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, getString(if (Dictation.recorder?.paused == true) R.string.voice_resume else R.string.voice_pause), action(PAUSE))
        .addAction(0, getString(R.string.voice_done), action(DONE))
        .build()

    private fun action(name: String) = PendingIntent.getService(this, name.hashCode(),
        Intent(this, DictationService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    /**
     * Headphones' play/pause button finishes the recording. Android sends it to whatever played sound
     * last, so a moment of silence is played to be that.
     */
    private fun listenToHeadphones() {
        session = MediaSession(this, "JLauncher voice note").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (event.keyCode !in BUTTONS) return false
                    if (event.action == KeyEvent.ACTION_UP) Dictation.done(this@DictationService)
                    return true
                }
            })
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build())
            isActive = true
        }
        playSilence()
    }

    private fun playSilence() {
        Thread {
            runCatching {
                val rate = 16_000
                val samples = ShortArray(rate / 5)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(400)
                track.release()
            }
        }.start()
    }

    companion object {
        private const val CHANNEL = "dictation"
        private const val ID = 4107
        private const val PAUSE = "pause"
        private const val DONE = "done"

        private val BUTTONS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        )

        /** Whether recordings can show their notification (Android 13 on asks) */
        fun canNotify(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
