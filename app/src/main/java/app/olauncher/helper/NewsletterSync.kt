package app.olauncher.helper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Reddit newsletter, run on the PC by its JLauncher bridge (jlauncher-bridge.js in the
 * Reddit-Newsletter project), which emails it to the Kindle and copies it to Google Drive.
 *
 * It goes through lockdown's Firebase database, under the same pairing key, in /lockdown/<key>/newsletter:
 * - requests/<id>: what this phone asks for (run a profile, stop, change a profile's settings), each
 *   done once by the PC and removed;
 * - status: the run going or the last one, with how far it's got;
 * - profiles/<name>: each profile's settings (never the email or Reddit logins, which stay on the PC);
 * - issues: the issues on Drive, newest first;
 * - bridge: when the PC was last heard from.
 *
 * Nothing stays connected: the page reads it when it's opened, and every few seconds while a run is
 * going and the page is showing.
 */
object NewsletterSync {

    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    val PROFILES = listOf(DAILY, WEEKLY)

    /** How long before the PC counts as not running the bridge; it says it's there every 5 minutes */
    const val PC_AWAY_MS = 15 * 60_000L

    /** What can be set for a profile, and for one subreddit: each setting's key and kind */
    enum class Kind { INT, BOOL, CHOICE, TEXT }

    class Setting(val key: String, val kind: Kind, val choices: List<String> = emptyList())

    val SORTS = listOf("hot", "new", "top", "rising", "controversial")
    val TIMEFRAMES = listOf("hour", "day", "week", "month", "year", "all")
    val COMMENT_STYLES = listOf("threaded", "nested", "html")
    val SOURCE_MODES = listOf("html", "rss", "json", "auto")
    val IMAGE_PRESETS = listOf("default", "aggressive", "extreme")

    /** The settings every subreddit has, which one subreddit can set its own way */
    val SUBREDDIT_SETTINGS = listOf(
        Setting("postsPerSubreddit", Kind.INT),
        Setting("sort", Kind.CHOICE, SORTS),
        Setting("timeframe", Kind.CHOICE, TIMEFRAMES),
        Setting("commentsPerPost", Kind.INT),
        Setting("commentStyle", Kind.CHOICE, COMMENT_STYLES),
        Setting("maxCommentDepth", Kind.INT),
        Setting("minCommentLength", Kind.INT),
        Setting("skipAutoModerator", Kind.BOOL),
        Setting("showNestedCommentGuideLines", Kind.BOOL),
        Setting("includeInternalLinks", Kind.BOOL),
        Setting("includeVideoContent", Kind.BOOL),
        Setting("skipImageAndGalleryPosts", Kind.BOOL),
        Setting("allowImagePostsIfCantFindOtherPosts", Kind.BOOL),
        Setting("skipUnfetchableArticles", Kind.BOOL),
    )

    class Status(
        val state: String,
        val profile: String?,
        val stage: String?,
        val index: Int?,
        val total: Int?,
        val subreddit: String?,
        val percent: Int,
        val startedAt: Long,
        val finishedAt: Long,
        val message: String?,
        val issue: String?,
    ) {
        val running get() = state == "running"
    }

    /** An issue on Drive: its file name, profile, and when it was made, "2026-11-23 21:40" */
    data class Issue(val file: String, val profile: String, val date: String, val size: Long)

    class Snapshot(
        val status: Status?,
        val profiles: Map<String, JSONObject>,
        val issues: List<Issue>,
        val pcSeen: Long,
    )

    // --- Reading what's there ---

    fun parse(text: String?): Snapshot {
        val root = text?.trim()?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val status = root.optJSONObject("status")?.let { s ->
            Status(
                state = s.optString("state", "idle"),
                profile = s.str("profile"),
                stage = s.str("stage"),
                index = s.int("index"),
                total = s.int("total"),
                subreddit = s.str("subreddit"),
                percent = s.optInt("percent", 0).coerceIn(0, 100),
                startedAt = s.optLong("startedAt", 0L),
                finishedAt = s.optLong("finishedAt", 0L),
                message = s.str("message"),
                issue = s.str("issue"),
            )
        }
        val profiles = root.optJSONObject("profiles")?.let { p ->
            p.keys().asSequence().mapNotNull { name -> p.optJSONObject(name)?.optJSONObject("settings")?.let { name to it } }.toMap()
        } ?: emptyMap()
        return Snapshot(status, profiles, issues(root.opt("issues")), root.optJSONObject("bridge")?.optLong("seen", 0L) ?: 0L)
    }

    /** Firebase gives a list back as an object when it has gaps */
    internal fun list(value: Any?): List<Any> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it)?.takeIf { v -> v != JSONObject.NULL } }
        is JSONObject -> value.keys().asSequence().filter { k -> k.all { it.isDigit() } }.sortedBy { it.toInt() }.mapNotNull { value.opt(it) }.toList()
        else -> emptyList()
    }

    private fun issues(value: Any?): List<Issue> = list(value).mapNotNull { o ->
        (o as? JSONObject)?.let {
            val file = it.str("file") ?: return@let null
            Issue(file, it.optString("profile"), it.optString("date"), it.optLong("size", 0L))
        }
    }

    private fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }
    private fun JSONObject.int(key: String) = if (isNull(key) || !has(key)) null else optInt(key)

    // --- A profile's settings ---

    /** The subreddits as names, with any settings of their own */
    class Subreddit(val name: String, val own: JSONObject)

    fun subreddits(settings: JSONObject): List<Subreddit> =
        list(settings.optJSONObject("reddit")?.opt("subreddits")).mapNotNull { entry ->
            when (entry) {
                is String -> Subreddit(entry, JSONObject())
                is JSONObject -> entry.optString("name").takeIf { it.isNotEmpty() }?.let { name ->
                    Subreddit(name, JSONObject(entry.toString()).apply { remove("name") })
                }
                else -> null
            }
        }

    /** The list back as the program reads it: a plain name, or the name with its own settings */
    fun withSubreddits(settings: JSONObject, subs: List<Subreddit>): JSONObject {
        val copy = JSONObject(settings.toString())
        val reddit = copy.optJSONObject("reddit") ?: JSONObject().also { copy.put("reddit", it) }
        reddit.put("subreddits", JSONArray().apply {
            subs.forEach { sub -> put(if (sub.own.length() == 0) sub.name else JSONObject(sub.own.toString()).put("name", sub.name)) }
        })
        return copy
    }

    /** A subreddit's name as Reddit takes it, from what was typed ("r/AskReddit", a link...), or null */
    fun cleanName(typed: String): String? {
        val name = typed.trim().substringAfterLast("/r/").removePrefix("r/").removePrefix("/").substringBefore('/').substringBefore('?').trim()
        return name.takeIf { it.matches(Regex("[A-Za-z0-9_]{2,40}")) }
    }

    // --- How far a run has got ---

    /** Which subreddit it's on, as (name, 1-based number, how many), while fetching */
    fun fetching(status: Status): Triple<String, Int, Int>? =
        if (status.stage == "subreddits" && status.subreddit != null && status.total != null && status.index != null)
            Triple(status.subreddit, status.index + 1, status.total) else null

    // --- Talking to the database ---

    private fun base(context: Context): String? {
        val url = LockdownSync.url(context)
        val key = LockdownSync.key(context)
        return if (url.isEmpty() || key.isEmpty()) null else "$url/lockdown/$key/newsletter"
    }

    /** Whether the phone has a database and key to use (set up with the Mac in Lockdown) */
    fun paired(context: Context) = base(context) != null

    /** Everything there now; off the main thread */
    fun fetch(context: Context): Snapshot {
        val base = base(context) ?: error("Not paired")
        return parse(request("GET", "$base.json", null))
    }

    fun run(context: Context, profile: String) = send(context, JSONObject().put("type", "run").put("profile", profile))

    fun stop(context: Context) = send(context, JSONObject().put("type", "stop"))

    fun saveSettings(context: Context, profile: String, settings: JSONObject) {
        send(context, JSONObject().put("type", "settings").put("profile", profile).put("settings", settings))
        // What the page shows until the PC sends them back
        val base = base(context) ?: return
        runCatching { request("PUT", "$base/profiles/$profile/settings.json", settings.toString()) }
    }

    private fun send(context: Context, request: JSONObject) {
        val base = base(context) ?: error("Not paired")
        request("POST", "$base/requests.json", request.put("at", System.currentTimeMillis()).toString())
    }

    private fun request(method: String, url: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
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
