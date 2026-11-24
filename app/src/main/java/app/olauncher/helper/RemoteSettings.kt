package app.olauncher.helper

import org.json.JSONObject

/**
 * Lockdown settings the Mac can change: the schedules, Lock after, breaks, what unlocking needs and
 * the phrase. The Mac sends the whole new value of one setting with when it was changed there; the
 * phone takes it unless it changed that setting itself later, so the latest change wins. The Mac asks
 * for the unlock before sending anything that loosens, as the phone would.
 *
 * The phone's own changes are noticed by comparing each setting with how it was last seen, so a
 * change made here gets its time without every place that changes a setting having to say so.
 */
object RemoteSettings {

    const val SCHEDULES = "schedules"
    const val TRIGGERS = "triggers"
    const val BREAKS = "breaks"
    const val CHALLENGE = "challenge"
    const val PHRASE = "phrase"

    val KEYS = listOf(SCHEDULES, TRIGGERS, BREAKS, CHALLENGE, PHRASE)

    /**
     * [value] the way the phone keeps [key], or null when it can't be taken: unreadable, a phrase under
     * 4 characters, or the password when none is set (only the phone sets one)
     */
    fun normalize(key: String, value: String, passwordSet: Boolean): String? = when (key) {
        SCHEDULES -> Lockdown.formatSchedules(Lockdown.parseSchedules(value)).takeIf { it.isNotEmpty() || value.isBlank() }
        TRIGGERS -> Lockdown.formatTriggers(Lockdown.parseTriggers(value)).takeIf { it.isNotEmpty() || value.isBlank() }
        BREAKS -> runCatching { JSONObject(value) }.getOrNull()?.let { Breaks.Config.decode(value).encode() }
        CHALLENGE -> Challenge.decode(value)?.takeIf { it.type != Challenge.Type.PASSWORD || passwordSet }?.encode()
        PHRASE -> value.trim().replace(Regex("\\s+"), " ").takeIf { it.length >= 4 }
        else -> null
    }

    /** Whether a change made on the Mac at [at] wins over the phone's last change to that setting, at [changedAt] */
    fun wins(at: Long, changedAt: Long) = at >= changedAt

    /**
     * [stamps] (when each setting last changed) brought up to date: a setting whose [current] value
     * differs from the one [seen] last time changed here, at [now]. One never seen before isn't stamped.
     */
    fun stamp(current: Map<String, String>, seen: Map<String, String>, stamps: Map<String, Long>, now: Long): Map<String, Long> =
        stamps + current.filter { (key, value) -> key in seen && seen[key] != value }.mapValues { now }
}
