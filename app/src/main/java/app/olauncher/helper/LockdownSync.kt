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
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Lockdown shared with the Mac and the TV, each switched on or off on its own; the connection stays
 * while either is on. Off by default. Every switched-on device takes the one lockdown; a switched-off
 * one is sent a lockdown that's off.
 *
 * Both sides use a Firebase Realtime Database through its plain web API, under a long random key
 * that only this phone and the paired Mac know:
 * - /lockdown/<key>/phone is what this phone says: the Mac's lockdown worked out here (on until
 *   turned off, a timer, and its windows from schedules and screen time, absolute), the phone's own
 *   for showing, breaks, what ending early needs, and what the Mac blocks.
 * - /lockdown/<key>/items is the shared list of blocked apps and sites ([BlockItems]), which both sides
 *   change, each switch keeping the time it changed.
 * - /lockdown/<key>/tv is the TV's apps, sent by the PC that watches the TV (tools/tv-time), which
 *   follows the TV's own lockdown from /phone's "tv": worked out here like the Mac's (on until turned
 *   off, a timer, its windows, its break), and the blocked apps.
 * - /lockdown/<key>/mac/commands is what the Mac asks for (start, add time, end, a break), each once;
 *   the phone removes them as it does them. /mac/installed is the Mac's apps, to choose from here.
 *
 * Kept light on the battery: nothing stays connected. The phone sends its state only when it has
 * changed, and reads the Mac's side only when the phone is being used (unlocked, the home screen), at
 * most once a minute; never while the screen is off. Every request is counted, so the cost can be seen.
 */
object LockdownSync {

    private const val PREFS = "lockdown_sync"
    private const val READ_EVERY_MS = 60_000L

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the connection is on: the database is set and the Mac or the TV is switched on */
    fun isOn(context: Context) = deviceOn(context, Lockdown.Device.MAC) || deviceOn(context, Lockdown.Device.TV)

    /** Whether the database and pairing key are set */
    fun linked(context: Context) = url(context).isNotEmpty() && key(context).isNotEmpty()

    fun url(context: Context) = store(context).getString("url", "").orEmpty()

    fun key(context: Context) = store(context).getString("key", "").orEmpty()

    private fun deviceKey(device: Lockdown.Device) = if (device == Lockdown.Device.TV) "tvOn" else "macOn"

    /**
     * Whether [device] (the Mac or the TV; the phone always is) is switched on. Before each had its own
     * switch, one switch was the whole connection: the Mac followed it, and the TV did once its PC had
     * sent its apps.
     */
    fun deviceOn(context: Context, device: Lockdown.Device): Boolean {
        if (device == Lockdown.Device.PHONE) return true
        if (!linked(context)) return false
        val s = store(context)
        val old = s.getBoolean("on", false)
        return s.getBoolean(deviceKey(device), if (device == Lockdown.Device.TV) old && tvInstalled(context).isNotEmpty() else old)
    }

    /**
     * Switches [device] on or off. The connection stays while either is on; a device switched off is
     * sent a lockdown that's off. Switching off loosens, so callers go through the unlock first.
     */
    fun setDeviceOn(context: Context, device: Lockdown.Device, on: Boolean) {
        if (device == Lockdown.Device.PHONE) return
        val s = store(context)
        // Both written, so the other device keeps what it had before each had its own switch
        val mac = deviceOn(context, Lockdown.Device.MAC)
        val tv = deviceOn(context, Lockdown.Device.TV)
        s.edit().putBoolean("macOn", mac).putBoolean("tvOn", tv).putBoolean(deviceKey(device), on)
            .putBoolean("on", if (device == Lockdown.Device.MAC) on || tv else on || mac).remove("sent").apply()
        // The last send says the switched-off device is off, even when that ends the connection
        push(context, final = true)
        if (on) pull(context, force = true)
    }

    /** Sets the database at [url], making a new pairing key the first time, and switches [device] on */
    fun turnOn(context: Context, url: String, device: Lockdown.Device = Lockdown.Device.MAC) {
        val clean = url.trim().trimEnd('/')
        val key = key(context).ifEmpty { newKey() }
        store(context).edit().putString("url", clean).putString("key", key).remove("sent").remove("sentApps").apply()
        setDeviceOn(context, device, true)
    }

    /** Switches the Mac and the TV off */
    fun turnOff(context: Context) {
        setDeviceOn(context, Lockdown.Device.MAC, false)
        setDeviceOn(context, Lockdown.Device.TV, false)
    }

    private fun newKey(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..40).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** The Mac's apps, as it last said: bundle id to name */
    fun macInstalled(context: Context): Map<String, String> = runCatching {
        val o = JSONObject(store(context).getString("macInstalled", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** The TV's apps, by name, as the PC last sent them; empty without a TV */
    fun tvInstalled(context: Context): List<String> = runCatching {
        val a = JSONArray(store(context).getString("tvInstalled", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** When the Mac was last heard from (its commands or apps), or 0 */
    fun macSeen(context: Context) = store(context).getLong("macSeen", 0L)

    /** When the TV's PC last sent its apps, or 0 */
    fun tvSeen(context: Context) = store(context).getLong("tvSeen", 0L)

    /**
     * When [device] last checked in (wall clock): through the database, or its screen time file, the
     * later; null when neither has been seen
     */
    fun lastSeen(context: Context, device: Lockdown.Device): Long? = when (device) {
        Lockdown.Device.PHONE -> System.currentTimeMillis()
        Lockdown.Device.MAC -> maxOf(macSeen(context), DeviceTime.updated(context, DeviceTime.Device.MAC))
        Lockdown.Device.TV -> maxOf(tvSeen(context), DeviceTime.updated(context, DeviceTime.Device.TV))
    }.takeIf { it > 0L }

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

    /** A plan's windows of lockdown from [today] to a week on, as [[start, end]] on the wall clock, the ones ended over a day before [now] left out */
    fun windowsJson(plan: Lockdown.Plan, zone: ZoneId, today: LocalDate, now: Long): JSONArray {
        val windows = plan.schedules.flatMap { it.windows(today, today.plusDays(7)) } + plan.windows
        return JSONArray().apply {
            windows.map { Lockdown.epoch(it.start, zone) to Lockdown.epoch(it.end, zone) }.distinct().sortedBy { it.first }
                .filter { it.second > now - 24 * 60 * 60_000L }
                .forEach { (s, e) -> put(JSONArray().put(s).put(e)) }
        }
    }

    /**
     * The lockdown a device ([plan], [breakUntil]) is sent, in the form the Mac app and the TV's PC read:
     * on until turned off, a timer, its windows and its break. A switched-off device's plan is empty, so
     * this says it's off.
     */
    fun deviceJson(plan: Lockdown.Plan, breakUntil: Long, zone: ZoneId, today: LocalDate, now: Long): JSONObject = JSONObject()
        .put("manual", plan.manual)
        .put("timedUntil", plan.timedEnd?.let { Lockdown.epoch(it, zone) } ?: 0L)
        .put("windows", windowsJson(plan, zone, today, now))
        .put("breakUntil", breakUntil)

    /** What this phone says now. The send is skipped when it's the same as last time. */
    fun phoneState(context: Context, prefs: Prefs = Prefs(context)): JSONObject {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val time = java.time.LocalDateTime.now()
        val macOn = deviceOn(context, Lockdown.Device.MAC)
        val tvOn = deviceOn(context, Lockdown.Device.TV)
        // One list: each device blocks what it can
        val items = BlockItems.effective(prefs.lockdownItems, macInstalled(context), tvInstalled(context))
        val shared = Lockdown.sharedPlan(context, prefs)
        val macPlan = Lockdown.planFor(shared, macOn)
        val tvPlan = Lockdown.planFor(shared, tvOn)
        // Phone apps blocked here take their sites to the Mac too
        val phoneAppSites = if (prefs.lockdownAppMode == Lockdown.BLOCK) Lockdown.sitesOf(BlockItems.phoneApps(items)) else emptySet()
        val sites = (BlockItems.macSites(items) + phoneAppSites).sorted()
        val phoneOn = shared.on(time)
        val challenge = Lockdown.challenge(prefs)
        val breakUntil = Lockdown.breakUntil(prefs)
        val macActive = macPlan.on(time)
        val macUntil = macPlan.until(time)?.let { Lockdown.epoch(it, zone) }
        val tvActive = tvPlan.on(time)
        return JSONObject()
            .put("v", 2)
            // The Mac's lockdown, worked out here: the one lockdown, or off while it's switched off
            .put("mac", deviceJson(macPlan, if (macOn) breakUntil else 0L, zone, today, now))
            // The phone's, to show on the Mac
            .put("phone", JSONObject()
                .put("on", phoneOn)
                .put("until", if (phoneOn) Lockdown.activeUntil(context, prefs) ?: 0L else 0L)
                // When the timed lockdown ends, or 0: while it runs, nothing is unblocked one at a time
                .put("timedUntil", Lockdown.timedEnd(context, prefs) ?: 0L)
                .put("breakUntil", breakUntil))
            // Always: every device blocks everything on the list it can
            .put("matched", true)
            // The TV's lockdown, worked out here like the Mac's; "blocking" is now, for a TV tool from before
            .put("tv", deviceJson(tvPlan, if (tvOn) breakUntil else 0L, zone, today, now)
                .put("blocking", tvActive && breakUntil <= now)
                .put("until", if (tvActive) tvPlan.until(time)?.let { Lockdown.epoch(it, zone) } ?: 0L else 0L)
                .put("apps", JSONArray(if (tvOn) BlockItems.tvApps(items).sorted() else emptyList())))
            .put("sites", JSONArray(sites))
            .put("macApps", JSONArray(BlockItems.macApps(items).sorted()))
            .put("challenge", challenge.encode())
            .put("phrase", prefs.lockdownPhrase)
            .put("passwordHash", prefs.lockdownPasswordHash)
            // Written again, so breaks always say they're shared
            .put("breaks", Lockdown.breaks(prefs).encode())
            .put("breakLog", Breaks.encodeLog(Breaks.trim(Lockdown.breakLog(prefs), now)))
            // The Mac requests done here, so the Mac knows when to stop acting on its own
            .put("applied", JSONArray(applied(context)))
            // For a Mac app from before: its own lockdown as one on-or-timed state
            .put("manualOn", macPlan.manual)
            .put("timedUntil", if (macActive && !macPlan.manual) macUntil ?: 0L else 0L)
            .put("schedules", "")
            .put("phoneOnly", false)
            .put("unlock", when (challenge.type) {
                Challenge.Type.PASSWORD -> "password"
                Challenge.Type.WAIT -> "wait"
                else -> "nothing"
            })
            .put("waitMinutes", if (challenge.type == Challenge.Type.WAIT) challenge.amount else 15)
            .put("appliedCommand", store(context).getString("command", "").orEmpty())
    }

    /** The phone's apps, for choosing what to block from the Mac */
    private fun phoneApps(context: Context): JSONArray {
        val pm = context.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { Lockdown.canBlock(context, it.first) }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        return JSONArray().apply { apps.forEach { (p, n) -> put(JSONObject().put("p", p).put("n", n)) } }
    }

    /**
     * Sends this phone's state if it's changed since the last send, and any changes to the shared list.
     * [final]: sent even when the connection has just been switched off, so the devices hear it's off.
     */
    fun push(context: Context, final: Boolean = false) {
        if (!isOn(context) && !(final && linked(context))) return
        val prefs = Prefs(context)
        val state = runCatching { phoneState(context, prefs) }.getOrNull() ?: return
        val text = state.toString()
        val s = store(context)
        val base = "${url(context)}/lockdown/${key(context)}"
        if (s.getString("sent", null) != text) {
            s.edit().putString("sent", text).apply()
            val body = JSONObject(text).put("updatedAt", System.currentTimeMillis()).toString()
            thread {
                val ok = runCatching { request(context, "PUT", "$base/phone.json", body) }.isSuccess
                // Tried again on the next change or check
                if (!ok) s.edit().remove("sent").apply()
            }
        }
        pushItems(context, prefs)
        // The phone's apps, now and then
        val day = LocalDate.now().toString()
        if (s.getString("sentAppsDay", null) != day) {
            val apps = runCatching { phoneApps(context).toString() }.getOrNull() ?: return
            s.edit().putString("sentAppsDay", day).apply()
            if (s.getString("sentApps", null) != apps) thread {
                if (runCatching { request(context, "PUT", "$base/phoneApps.json", apps) }.isSuccess) s.edit().putString("sentApps", apps).apply()
            }
        }
    }

    /** The shared list as last seen in the database */
    private fun remoteItems(context: Context) = BlockItems.decode(store(context).getString("remoteItems", null))

    /** Sends the items that differ from the database's copy, each switch on its own so the Mac's changes to others aren't undone */
    private fun pushItems(context: Context, prefs: Prefs) {
        val local = prefs.lockdownItems
        val remote = remoteItems(context)
        val changed = BlockItems.changed(local, remote)
        if (changed.isEmpty()) return
        val body = JSONObject()
        changed.forEach { (id, item) -> item.json().let { j -> j.keys().forEach { body.put("$id/$it", j.get(it)) } } }
        val s = store(context)
        s.edit().putString("remoteItems", BlockItems.encode(remote + changed)).apply()
        thread {
            val ok = runCatching { request(context, "PATCH", "${url(context)}/lockdown/${key(context)}/items.json", body.toString()) }.isSuccess
            if (!ok) s.edit().putString("remoteItems", BlockItems.encode(remote)).apply()
        }
    }

    // --- Mac to phone ---

    private var lastPull = 0L

    /** The ids of the Mac requests done here lately */
    private fun applied(context: Context): List<String> = runCatching {
        val a = JSONArray(store(context).getString("applied", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /**
     * Reads the Mac's side when the phone is in use: at most once a minute unless [force] (turning
     * sharing on, opening the Lockdown page).
     */
    fun pull(context: Context, force: Boolean = false) {
        if (!isOn(context)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastPull != 0L && now - lastPull < READ_EVERY_MS) return
        lastPull = now
        val app = context.applicationContext
        val url = "${url(context)}/lockdown/${key(context)}.json"
        thread {
            val text = runCatching { request(app, "GET", url, null) }.getOrNull() ?: return@thread
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return@thread
            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { apply(app, root) } }
        }
    }

    /** Takes in the Mac's side: its requests, its changes to the shared list, its apps */
    private fun apply(context: Context, root: JSONObject) {
        val prefs = Prefs(context)
        val s = store(context)
        val mac = root.optJSONObject("mac")
        if (mac != null) s.edit().putLong("macSeen", System.currentTimeMillis()).apply()
        // Its apps
        mac?.optJSONArray("installed")?.let { list ->
            val o = JSONObject()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { o.put(it.optString("b"), it.optString("n")) }
            s.edit().putString("macInstalled", o.toString()).apply()
        }
        // The TV's apps, and when its PC sent them
        root.optJSONObject("tv")?.let { tv ->
            tv.optJSONArray("installed")?.let { s.edit().putString("tvInstalled", it.toString()).apply() }
            tv.optLong("seen").takeIf { it > 0L }?.let { s.edit().putLong("tvSeen", it).apply() }
        }
        // The shared list: taken together with this phone's copy
        val remote = root.optJSONObject("items")?.let { BlockItems.decode(it.toString()) }.orEmpty()
        var local = prefs.lockdownItems
        // Mac apps chosen before the list was shared
        if (remote.isEmpty()) mac?.optJSONArray("apps")?.let { apps ->
            val names = macInstalled(context)
            for (i in 0 until apps.length()) {
                val bundle = apps.optString(i).ifEmpty { null } ?: continue
                if (local.values.none { it.bundle == bundle }) local = BlockItems.block(local, BlockItems.Kind.APP, names[bundle] ?: bundle, true, now = 1, bundle = bundle)
            }
        }
        val merged = BlockItems.mergeAll(local, remote)
        if (merged != prefs.lockdownItems) prefs.lockdownItems = merged
        s.edit().putString("remoteItems", BlockItems.encode(remote)).apply()
        // Its requests, in order, each once; a switched-off Mac's are dropped
        val macOn = deviceOn(context, Lockdown.Device.MAC)
        val commands = mac?.optJSONObject("commands")
        val done = applied(context).toMutableList()
        val toRemove = JSONObject()
        commands?.keys()?.asSequence()?.toList()?.sorted()?.forEach { pushId ->
            val command = commands.optJSONObject(pushId) ?: return@forEach
            val id = command.optString("id").ifEmpty { pushId }
            if (id !in done) {
                if (macOn) runCommand(context, prefs, command)
                done += id
            }
            toRemove.put(pushId, JSONObject.NULL)
        }
        // A single request, from a Mac app from before
        mac?.optJSONObject("command")?.let { command ->
            val id = command.optString("id")
            if (id.isNotEmpty() && id != s.getString("command", null)) {
                s.edit().putString("command", id).apply()
                if (macOn) runCommand(context, prefs, command)
            }
        }
        s.edit().putString("applied", JSONArray(done.takeLast(50)).toString()).apply()
        if (toRemove.length() > 0) {
            val url = "${url(context)}/lockdown/${key(context)}/mac/commands.json"
            thread { runCatching { request(context, "PATCH", url, toRemove.toString()) } }
        }
        LockdownSystem.sync(context)
    }

    /**
     * Does one thing the Mac asked, on the one lockdown: whatever devices it names, it acts on every
     * switched-on device. Old requests (the phone was away a day) aren't acted on.
     */
    private fun runCommand(context: Context, prefs: Prefs, command: JSONObject) {
        val at = command.optLong("at")
        if (System.currentTimeMillis() - at > 24 * 60 * 60_000L) return
        when (command.optString("type")) {
            "start" -> {
                val until = command.optLong("until")
                if (until <= 0L) Lockdown.turnOn(prefs)
                else Lockdown.lockForTotal(context, until - System.currentTimeMillis(), prefs)
            }
            "add" -> Lockdown.addTime(context, command.optLong("ms"), prefs)
            // The Mac checked what ending early needs before asking
            "end" -> {
                Lockdown.turnOff(prefs)
                Lockdown.endTimed(prefs)
            }
            "break" -> if (Lockdown.breakLog(prefs).none { it.id == command.optString("id") })
                Lockdown.startBreak(context, prefs, Lockdown.Device.MAC, command.optString("id"), at)
            "endBreak" -> Lockdown.endBreak(prefs)
        }
    }

    private fun request(context: Context, method: String, url: String, body: String?): String {
        counted(context)
        val connection = URL(if (method == "PATCH") "$url?x-http-method-override=PATCH" else url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH; Firebase takes it this way
            if (method == "PATCH") {
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else connection.requestMethod = method
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
