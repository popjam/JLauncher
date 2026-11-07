package app.olauncher.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import app.olauncher.R
import app.olauncher.data.BackupManager

/**
 * Whether everything that fills the stats in by itself is working: usage access, notification access
 * (and its listener actually running), location for homes and work, steps, CommBank's notifications
 * still being read, the MacBook's, TV's and vibecoding files coming in, and backups. A problem is
 * noted from when it was first seen, so the pages can say since when.
 */
object Tracking {

    /** One thing tracked: [ok], or what's wrong in [detail], the stats it holds up, and how to fix it if there's a way */
    class Item(
        val key: String,
        val title: String,
        val ok: Boolean,
        val detail: String,
        val affects: Set<StatKind>,
        val since: Long? = null,
        val fix: ((Context) -> Unit)? = null,
    )

    fun items(context: Context): List<Item> {
        val list = mutableListOf<Item>()
        val now = System.currentTimeMillis()
        fun ago(time: Long): String {
            val hours = (now - time) / 3_600_000L
            return if (hours >= 24) context.resources.getQuantityString(R.plurals.tracking_days, (hours / 24).toInt(), (hours / 24).toInt())
            else context.formattedTimeSpent(now - time)
        }
        fun open(intent: Intent) = { c: Context -> runCatching { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
        val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

        // Usage access: screen time and sleep
        val usage = context.appUsagePermissionGranted()
        list += Item("usage", context.getString(R.string.tracking_usage), usage,
            context.getString(if (usage) R.string.tracking_usage_ok else R.string.tracking_usage_off),
            setOf(StatKind.SCREEN, StatKind.SLEEP), fix = if (usage) null else open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))

        // Notification access, and the listener running: money, music, and the Wi-Fi watcher for places
        val enabled = Spending.isEnabled(context)
        val prefs = prefs(context)
        val connected = prefs.getLong(CONNECTED, 0L)
        val disconnected = prefs.getLong(DISCONNECTED, 0L)
        val running = enabled && disconnected <= connected
        list += Item("notifications", context.getString(R.string.tracking_notifications), running,
            when {
                !enabled -> context.getString(R.string.tracking_notifications_off)
                !running -> context.getString(R.string.tracking_notifications_stopped, ago(disconnected))
                else -> context.getString(R.string.tracking_notifications_ok)
            },
            setOf(StatKind.MONEY, StatKind.MUSIC, StatKind.SOCIAL, StatKind.WORK), if (!running) disconnected.takeIf { enabled && it > 0 } else null,
            fix = when {
                !enabled -> { c -> Spending.openSettings(c) }
                !running -> { c -> restartListener(c) }
                else -> null
            })

        // Location, once a home or work is set: knowing the Wi-Fi's name needs it, all the time, and switched on
        if (Social.hasPlaces(context)) {
            val permission = Social.hasLocation(context) && Social.hasBackgroundLocation(context)
            val on = (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled != false
            list += Item("location", context.getString(R.string.tracking_location), permission && on,
                context.getString(when {
                    !permission -> R.string.tracking_location_permission
                    !on -> R.string.tracking_location_off
                    else -> R.string.tracking_location_ok
                }),
                setOf(StatKind.SOCIAL, StatKind.WORK),
                fix = when {
                    !permission -> open(appSettings)
                    !on -> open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    else -> null
                })
        }

        // Steps, where the phone has a counter
        if (Steps.hasSensor(context)) {
            val steps = Steps.hasPermission(context)
            list += Item("steps", context.getString(R.string.tracking_steps), steps,
                context.getString(if (steps) R.string.tracking_steps_ok else R.string.tracking_steps_off),
                setOf(StatKind.EXERCISE), fix = if (steps) null else open(appSettings))
        }

        // CommBank still being read: notifications with amounts that couldn't be read, and no purchases, all week
        if (enabled) {
            val unread = Spending.unreadSince(context, now - WEEK_MS)
            val bought = Spending.purchases(context).any { it.time >= now - WEEK_MS }
            val ok = unread < UNREAD_WORRY || bought
            list += Item("bank", context.getString(R.string.tracking_bank), ok,
                if (ok) context.getString(R.string.tracking_bank_ok) else context.getString(R.string.tracking_bank_unread, unread),
                setOf(StatKind.MONEY))
        }

        // The other devices' files, from Drive, once they've come in at all
        DeviceTime.Device.values().forEach { device ->
            val updated = DeviceTime.updated(context, device)
            if (updated <= 0) return@forEach
            val fresh = now - updated < STALE_MS
            list += Item("device_" + device.key, context.getString(device.label), fresh,
                context.getString(if (fresh) R.string.tracking_device_ok else R.string.tracking_device_stale, ago(updated)),
                if (device == DeviceTime.Device.MAC) setOf(StatKind.SCREEN, StatKind.LIBIDO) else setOf(StatKind.SCREEN), if (fresh) null else updated)
        }
        val vibe = Vibecoding.updated(context)
        if (vibe > 0) {
            val fresh = now - vibe < STALE_MS
            list += Item("vibecoding", context.getString(R.string.tracking_vibecoding), fresh,
                context.getString(if (fresh) R.string.tracking_device_ok else R.string.tracking_device_stale, ago(vibe)),
                setOf(StatKind.VIBECODING), if (fresh) null else vibe)
        }

        // Backups to Drive
        if (BackupManager.backupFolder(context).isNotEmpty()) {
            val failing = BackupManager.isFolderFailing(context)
            list += Item("backup", context.getString(R.string.tracking_backup), !failing,
                context.getString(if (failing) R.string.tracking_backup_failing else R.string.tracking_backup_ok), emptySet())
        }

        // Since when each problem's been seen
        val edit = prefs.edit()
        val dated = list.map { item ->
            val key = SINCE + item.key
            if (item.ok) {
                edit.remove(key)
                item
            } else {
                val since = item.since ?: prefs.getLong(key, 0L).takeIf { it > 0 } ?: now.also { edit.putLong(key, it) }
                Item(item.key, item.title, item.ok, item.detail, item.affects, since, item.fix)
            }
        }
        edit.apply()
        return dated
    }

    /** What isn't working */
    fun problems(context: Context) = items(context).filter { !it.ok }

    /** The problems holding up a stat of [kind] */
    fun problemsFor(context: Context, kind: StatKind) = problems(context).filter { kind in it.affects }

    /** Asks Android to start the notification listener again, after it's been stopped */
    fun restartListener(context: Context) {
        runCatching { NotificationListenerService.requestRebind(ComponentName(context, SpendingListener::class.java)) }
    }

    fun listenerConnected(context: Context) = prefs(context).edit().putLong(CONNECTED, System.currentTimeMillis()).apply()

    fun listenerDisconnected(context: Context) = prefs(context).edit().putLong(DISCONNECTED, System.currentTimeMillis()).apply()

    /** When the app opens: if the listener's been stopped while access is on, it's asked to start again */
    fun restartIfStopped(context: Context) {
        val prefs = prefs(context)
        if (Spending.isEnabled(context) && prefs.getLong(DISCONNECTED, 0L) > prefs.getLong(CONNECTED, 0L)) restartListener(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)

    private const val CONNECTED = "connected"
    private const val DISCONNECTED = "disconnected"
    private const val SINCE = "since_"
    private const val WEEK_MS = 7 * 24 * 3_600_000L
    private const val STALE_MS = 2 * 24 * 3_600_000L
    private const val UNREAD_WORRY = 3
}
