package app.olauncher.helper

import android.content.Context
import app.olauncher.data.MoodStore

/**
 * Stats JLauncher fills in by itself, like Sleep and Music: each is added once, the first time there's
 * something to fill it with, and remembered by id. Deleting one stops it being filled; it isn't added again.
 */
object AutoStats {

    const val SLEEP = "sleep"
    const val MUSIC = "music"
    const val SOCIAL = "social"
    const val STEPS = "steps"
    const val MAC = "mac"
    const val VIBECODING = "vibecoding"
    const val BUSPIRONE = "buspirone"
    const val WORK = "work"
    const val WEATHER = "weather"
    const val PEOPLE = "people"

    /** Which of these [id] is ([SLEEP], [MUSIC] and so on), or null for a stat filled in by hand. */
    fun keyOf(context: Context, id: String): String? =
        prefs(context).all.entries.firstOrNull { it.value == id }?.key

    /** The stat's id, or null if it hasn't been added or was deleted. */
    fun id(context: Context, key: String, data: MoodStore.Data = MoodStore(context).load()): String? =
        prefs(context).getString(key, null)?.takeIf { data.stat(it) != null }

    /**
     * [key]'s stat id, adding it with [create] the first time. A stat already called [name] is taken
     * on instead (one restored from a backup: which stats are filled in isn't part of it). Otherwise
     * stats [replaces] picks are deleted first (with their history), and the new one takes the first
     * one's place among the tabs.
     */
    fun ensure(
        context: Context,
        key: String,
        name: String,
        replaces: (MoodStore.Stat) -> Boolean = { false },
        create: (MoodStore) -> MoodStore.Stat,
    ): String? = synchronized(LOCK) {
        val prefs = prefs(context)
        val store = MoodStore(context)
        prefs.getString(key, null)?.let { id -> return id.takeIf { store.load().stat(it) != null } }

        val data = store.load()
        data.stats.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { existing ->
            prefs.edit().putString(key, existing.id).apply()
            return existing.id
        }
        val order = context.allStats(data).map { it.id }
        val old = data.stats.filter(replaces)
        old.forEach { store.deleteStat(it.id) }
        val stat = create(store)
        val place = old.firstOrNull()?.let { order.indexOf(it.id) } ?: -1
        if (place >= 0) {
            val oldIds = old.map { it.id }.toSet()
            val newOrder = order.toMutableList().apply { set(place, stat.id) }.filter { it !in oldIds }
            store.setOrder(newOrder)
        }
        prefs.edit().putString(key, stat.id).apply()
        return stat.id
    }

    /**
     * After a restore: a stat added here that the backup also has (same name, another id, as when it
     * was added before restoring) is dropped for the backup's, which is filled in from then on.
     */
    fun afterRestore(context: Context) = synchronized(LOCK) {
        val prefs = prefs(context)
        val store = MoodStore(context)
        val data = store.load()
        prefs.all.forEach { (key, value) ->
            val id = value as? String ?: return@forEach
            val mine = data.stat(id) ?: return@forEach
            val restored = data.stats.firstOrNull { it.id != id && it.name.equals(mine.name, ignoreCase = true) }
                ?: return@forEach
            store.deleteStat(id)
            prefs.edit().putString(key, restored.id).apply()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val LOCK = Any()
    private const val PREFS = "auto_stats"
}
