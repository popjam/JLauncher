package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import app.olauncher.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Lockdown shared with the Mac, when it's turned on. Off by default.
 *
 * Both sides use a Firebase Realtime Database through its plain web API, under a long random key
 * that only this phone and the paired Mac know: /lockdown/<key>/phone is what this phone says
 * (on or off, the timer, schedules, sites, how it's unlocked), and /lockdown/<key>/mac is what the
 * Mac asks for (start, add time, end) and its own blocked apps.
 *
 * Kept light on the battery: nothing stays connected. The phone sends its state only when it has
 * changed, and reads the Mac's requests only when the phone is being used (unlocked, the home
 * screen), at most once a minute; never while the screen is off. Every request is counted, so the
 * cost can be seen.
 */
object LockdownSync {

    private const val PREFS = "lockdown_sync"
    private const val READ_EVERY_MS = 60_000L

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context) = store(context).getBoolean("on", false) && url(context).isNotEmpty() && key(context).isNotEmpty()

    fun url(context: Context) = store(context).getString("url", "").orEmpty()

    fun key(context: Context) = store(context).getString("key", "").orEmpty()

    /** Turns sharing on with the database at [url], making a new pairing key the first time */
    fun turnOn(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        val key = key(context).ifEmpty { newKey() }
        store(context).edit().putBoolean("on", true).putString("url", clean).putString("key", key).remove("sent").apply()
        push(context)
        pull(context, force = true)
    }

    fun turnOff(context: Context) {
        store(context).edit().putBoolean("on", false).remove("sent").apply()
    }

    private fun newKey(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..40).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    // --- What it costs ---

    /** Requests made today, and when the last one was (wall clock), for the Mac row */
    fun requestsToday(context: Context): Int {
        val s = store(context)
        return if (s.getString("day", null) == LocalDate.now().toString()) s.getInt("count", 0) else 0
    }

    fun lastRequest(context: Context): Long = store(context).getLong("last", 0L)

    private fun counted(context: Context) {
        val s = store(context)
        val today = LocalDate.now().toString()
        val count = if (s.getString("day", null) == today) s.getInt("count", 0) else 0
        s.edit().putString("day", today).putInt("count", count + 1).putLong("last", System.currentTimeMillis()).apply()
    }

    // --- Phone to Mac ---

    /** What this phone says now. The send is skipped when it's the same as last time. */
    fun phoneState(context: Context, prefs: Prefs = Prefs(context)): JSONObject {
        val sites = (prefs.lockdownSites + if (prefs.lockdownAppMode == Lockdown.BLOCK) Lockdown.sitesOf(prefs.lockdownBlockedApps) else emptySet()).sorted()
        val why = Lockdown.why(context, prefs)
        return JSONObject()
            .put("manualOn", prefs.lockdownModeOn)
            // The stored end, not one worked out now, so the state doesn't change as time passes
            .put("timedUntil", if (Lockdown.timedActive(context, prefs)) prefs.lockdownUntil else 0L)
            .put("schedules", prefs.lockdownSchedules)
            // On for a reason only the phone can tell (a place, a screen time stage)
            .put("phoneOnly", why == Lockdown.Why.PLACE || why == Lockdown.Why.STAGE)
            .put("phoneOnlyUntil", if (why == Lockdown.Why.STAGE) Lockdown.activeUntil(context, prefs) ?: 0L else 0L)
            .put("sites", JSONArray(sites))
            .put("unlock", Lockdown.unlock(prefs).name.lowercase())
            .put("passwordHash", prefs.lockdownPasswordHash)
            .put("waitMinutes", prefs.lockdownWaitMinutes)
            // The last Mac request done here, so the Mac knows when to stop acting on its own
            .put("appliedCommand", store(context).getString("command", "").orEmpty())
    }

    /** Sends this phone's state if it's changed since the last send */
    fun push(context: Context) {
        if (!isOn(context)) return
        val state = runCatching { phoneState(context) }.getOrNull() ?: return
        val text = state.toString()
        val s = store(context)
        if (s.getString("sent", null) == text) return
        s.edit().putString("sent", text).apply()
        val url = "${url(context)}/lockdown/${key(context)}/phone.json"
        val body = JSONObject(text).put("updatedAt", System.currentTimeMillis()).toString()
        thread {
            val ok = runCatching { request(context, "PUT", url, body) }.isSuccess
            // Tried again on the next change or check
            if (!ok) s.edit().remove("sent").apply()
        }
    }

    // --- Mac to phone ---

    private var lastPull = 0L

    /**
     * Reads what the Mac has asked for, when the phone is in use: at most once a minute unless
     * [force] (turning sharing on, opening the Lockdown page).
     */
    fun pull(context: Context, force: Boolean = false) {
        if (!isOn(context)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastPull != 0L && now - lastPull < READ_EVERY_MS) return
        lastPull = now
        val app = context.applicationContext
        val url = "${url(context)}/lockdown/${key(context)}/mac/command.json"
        thread {
            val text = runCatching { request(app, "GET", url, null) }.getOrNull() ?: return@thread
            val command = runCatching { JSONObject(text) }.getOrNull() ?: return@thread
            android.os.Handler(android.os.Looper.getMainLooper()).post { apply(app, command) }
        }
    }

    /** Does what the Mac asked, once: start (for a time or until turned off), add time, or end */
    private fun apply(context: Context, command: JSONObject) {
        val id = command.optString("id")
        val s = store(context)
        if (id.isEmpty() || id == s.getString("command", null)) return
        s.edit().putString("command", id).apply()
        // Old requests (the phone was away) aren't acted on
        if (System.currentTimeMillis() - command.optLong("at") > 24 * 60 * 60_000L) return
        val prefs = Prefs(context)
        when (command.optString("type")) {
            "start" -> {
                val until = command.optLong("until")
                if (until <= 0L) prefs.lockdownModeOn = true
                else Lockdown.lockForTotal(context, until - System.currentTimeMillis(), prefs)
            }
            "add" -> Lockdown.addTime(context, command.optLong("ms"), prefs)
            // The Mac checked the unlock (password or wait) before asking
            "end" -> {
                prefs.lockdownModeOn = false
                Lockdown.endTimed(prefs)
            }
        }
        LockdownSystem.sync(context)
    }

    private fun request(context: Context, method: String, url: String, body: String?): String {
        counted(context)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
