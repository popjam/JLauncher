package app.olauncher.helper

/**
 * What has to be done to end lockdown early, loosen it, or take a break: from nothing at all to not
 * being able to at all. [amount] is in each type's own unit (seconds, taps, times, characters,
 * minutes); types without one ignore it.
 *
 * Written as "TYPE:amount", the same on the phone and the Mac.
 */
data class Challenge(val type: Type, val amount: Int = 0) {

    enum class Type {
        /** Just a confirmation */
        NONE,
        /** Keep a countdown open on screen for [amount] seconds */
        HOLD,
        /** Tap a button [amount] times */
        TAPS,
        /** Type the chosen phrase out [amount] times, without pasting */
        PHRASE,
        /** Type out [amount] random characters exactly, without pasting */
        RANDOM,
        /** The lockdown password */
        PASSWORD,
        /** Ask, then wait [amount] minutes; it then stays open for a while */
        WAIT,
        /** Not possible at all */
        NEVER,
    }

    fun encode() = "${type.name}:$amount"

    /** Roughly how many seconds of effort it takes, to rank them; the phrase's length matters for [Type.PHRASE] */
    fun effortSeconds(phraseLength: Int = 40): Long = when (type) {
        Type.NONE -> 0
        Type.HOLD -> amount.toLong()
        Type.TAPS -> amount / 4L
        Type.PHRASE -> phraseLength.coerceAtLeast(1).toLong() * amount / 3
        Type.RANDOM -> amount * 3L / 2
        Type.PASSWORD -> 20
        Type.WAIT -> amount * 60L
        Type.NEVER -> Long.MAX_VALUE
    }

    /** 0 simple … 5 almost impossible, 6 impossible */
    fun difficulty(phraseLength: Int = 40): Int {
        val s = effortSeconds(phraseLength)
        return when {
            type == Type.NEVER -> 6
            s <= 0 -> 0
            s < 30 -> 1
            s < 180 -> 2
            s < 900 -> 3
            s < 3600 -> 4
            else -> 5
        }
    }

    val hasAmount get() = amount(type).isNotEmpty()

    companion object {
        val DEFAULT = Challenge(Type.NONE)

        fun decode(text: String?): Challenge? {
            val parts = text?.split(':') ?: return null
            val type = runCatching { Type.valueOf(parts[0]) }.getOrNull() ?: return null
            val amount = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val choices = amount(type)
            // An amount that isn't offered is moved to the nearest one that is
            val fixed = if (choices.isEmpty() || amount in choices) amount else choices.minByOrNull { kotlin.math.abs(it - amount) }!!
            return Challenge(type, fixed)
        }

        /** The amounts offered for each type */
        fun amount(type: Type): List<Int> = when (type) {
            Type.HOLD -> listOf(10, 30, 60, 300, 900, 1800, 3600)
            Type.TAPS -> listOf(20, 100, 300, 1000, 3000, 10000)
            Type.PHRASE -> listOf(1, 3, 5, 10, 25)
            Type.RANDOM -> listOf(30, 80, 200, 500, 1000, 2500)
            Type.WAIT -> listOf(5, 15, 30, 60, 120, 360, 720, 1440)
            else -> emptyList()
        }

        /** The usual amount when a type is first picked */
        fun defaultAmount(type: Type): Int = when (type) {
            Type.HOLD -> 30
            Type.TAPS -> 100
            Type.PHRASE -> 1
            Type.RANDOM -> 80
            Type.WAIT -> 15
            else -> 0
        }

        /**
         * Whether going from [old] to [new] makes things easier, so it needs [old] to be passed first.
         * Leaving the password always does, since only the password should change it.
         */
        fun loosens(old: Challenge, new: Challenge, oldPhraseLength: Int = 40, newPhraseLength: Int = oldPhraseLength): Boolean {
            if (old == new && oldPhraseLength == newPhraseLength) return false
            if (old.type == Type.PASSWORD) return true
            return new.effortSeconds(newPhraseLength) <= old.effortSeconds(oldPhraseLength)
        }

        /** Characters that can't be mistaken for each other */
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        /** [length] random characters in groups of five, for [Type.RANDOM] */
        fun randomText(length: Int, random: java.util.Random = java.security.SecureRandom()): String =
            (0 until length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(5).joinToString(" ") { it.joinToString("") }

        /** Whether [typed] matches [expected], ignoring case and runs of spaces (or all spaces, with [ignoreSpaces]) */
        fun matches(typed: String, expected: String, ignoreSpaces: Boolean = false): Boolean {
            fun clean(s: String) = s.trim().lowercase().replace(Regex("\\s+"), if (ignoreSpaces) "" else " ")
            return clean(typed) == clean(expected) && expected.isNotBlank()
        }
    }
}
