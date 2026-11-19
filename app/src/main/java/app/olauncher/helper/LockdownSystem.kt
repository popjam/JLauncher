package app.olauncher.helper

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.ui.LockdownGate

/**
 * What lockdown changes outside JLauncher: grayscale, its notification, and the Quick Settings tile.
 * [sync] brings them in line with lockdown now; it's called as things change and every few seconds
 * by the accessibility service.
 */
object LockdownSystem {

    const val CHANNEL = "lockdown"
    private const val NOTIFICATION_ID = 7301

    /** Extras on MainActivity's intent: open the Lockdown page, and with [EXTRA_LOCK_FOR] its "Lock for a set time" */
    const val EXTRA_OPEN = "openLockdown"
    const val EXTRA_LOCK_FOR = "lockFor"

    fun sync(context: Context) {
        val prefs = Prefs(context)
        runCatching { syncGrayscale(context, prefs) }
        runCatching { syncNotification(context, prefs) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, LockdownTileService::class.java)) }
        runCatching { LockdownSync.push(context) }
    }

    // --- Grayscale ---

    fun canGrayscale(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun grantCommand(context: Context) = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** Grey while lockdown is on (when chosen) and from screen time stage 2; only turns off what it turned on */
    private fun syncGrayscale(context: Context, prefs: Prefs) {
        if (!canGrayscale(context)) return
        val want = (prefs.lockdownGrayscale && Lockdown.isActive(context, prefs)) || Limits.stageToday(context, prefs) >= 2
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getInt(resolver, DALTONIZER_ENABLED, 0) == 1
        val on = enabled && Settings.Secure.getInt(resolver, DALTONIZER, -1) == MONOCHROME
        when {
            // Colour correction the user turned on themselves is left alone
            want && !on && !(enabled && !prefs.lockdownGrayscaleApplied) -> {
                Settings.Secure.putInt(resolver, DALTONIZER, MONOCHROME)
                Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 1)
                prefs.lockdownGrayscaleApplied = true
            }
            !want && prefs.lockdownGrayscaleApplied -> {
                if (on) Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 0)
                prefs.lockdownGrayscaleApplied = false
            }
        }
    }

    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val DALTONIZER = "accessibility_display_daltonizer"
    private const val MONOCHROME = 0

    // --- The notification ---

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun syncNotification(context: Context, prefs: Prefs) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!prefs.lockdownNotification || !canNotify(context) || !Lockdown.isActive(context, prefs)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.lockdown), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val addHour = PendingIntent.getBroadcast(context, 2, Intent(context, LockdownReceiver::class.java).setAction(ACTION_ADD_HOUR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(LockdownGate.notificationTitle(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.lockdown_add_hour), addHour)
            .addAction(0, context.getString(R.string.lockdown_open), open)
        LockdownGate.screenTimeLine(context)?.let { (text, progress) ->
            builder.setContentText(text)
            if (progress != null) builder.setProgress(100, progress, false)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    const val ACTION_ADD_HOUR = "app.olauncher.LOCKDOWN_ADD_HOUR"

    // --- Opening the Lockdown page from outside ---

    fun openIntent(context: Context, lockFor: Boolean) = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_OPEN, true).putExtra(EXTRA_LOCK_FOR, lockFor)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** The notification's "+1 hour": an hour more of timed lockdown, or an hour from now */
class LockdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockdownSystem.ACTION_ADD_HOUR) return
        val prefs = Prefs(context)
        Lockdown.addTime(context, 60 * 60_000L, prefs)
        LockdownSystem.sync(context)
    }
}

/** The Quick Settings tile: shows whether lockdown is on, and opens "Lock for a set time" */
class LockdownTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val on = Lockdown.isActive(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.lockdown)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (!on) null else Lockdown.activeUntil(this)?.let { getString(R.string.lockdown_until, LockdownGate.time(this, it)) }
                ?: getString(R.string.on)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = LockdownSystem.openIntent(this, lockFor = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
