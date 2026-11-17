package app.olauncher.helper

/**
 * Tidying spoken words written out by speech recognition: what can be done without the network. Filler
 * sounds go, a stuttered word is said once, and each stretch of speech becomes a sentence unless the
 * pause after it was mid-sentence. Anything that changes the meaning is left alone; [AiTidy]
 * does the rest when it can.
 */
object Transcript {

    /** Sounds made while thinking, never meant as words ("er" and "ah" can be: the ER, ah I see) */
    private val FILLERS = Regex("(?i)(^|[\\s,.!?;:])(?:u+m+|u+h+m*|e+r+m+|h+m+|m+h*m+)(?=$|[\\s,.!?;:])[,.]?")

    /** A "yeah" (or two) starting a stretch of speech, before more words */
    private val LEADING_YEAH = Regex("(?i)^(?:yeah|yep|yea)(?:[\\s,.]+(?:yeah|yep|yea))*[\\s,.]+(?=\\S)")

    /** The same word twice in a row; [KEEP_DOUBLED] are fine said twice */
    private val DOUBLED = Regex("(?i)\\b([\\p{L}']+)(?:[\\s,]+\\1\\b)+")
    private val KEEP_DOUBLED = setOf("had", "that", "is", "do", "very", "really", "so", "no", "bye", "ha")

    private val LONE_I = Regex("(?<![\\p{L}'])i(?=$|[\\s,.!?;:]|'(?:m|ve|d|ll)\\b)")
    private val SPACES = Regex("[ \\t]+")
    private val SPACE_BEFORE_MARK = Regex("\\s+([,.!?;:])")
    private val COMMA_RUN = Regex(",(\\s*,)+")
    private val SENTENCE_START = Regex("([.!?]\\s+)(\\p{Ll})")

    /**
     * Words a sentence doesn't end on: a pause after one is someone thinking mid-sentence, so the
     * next stretch of speech carries on the same sentence
     */
    private val CARRIES_ON = setOf(
        "a", "an", "the", "and", "but", "or", "so", "because", "to", "of", "in", "on", "at", "for", "with", "about",
        "from", "into", "by", "my", "your", "his", "her", "their", "our", "its", "this", "that", "these", "those",
        "i", "we", "you", "they", "he", "she", "it", "was", "is", "are", "were", "be", "been", "am", "if", "when",
        "then", "than", "like", "just", "really", "very", "some", "any", "what", "which", "who", "where", "how",
    )

    /**
     * Everyday words that only start with a capital because they started a stretch of speech: lowered
     * when that stretch carries on a sentence. Anything else (a name, a place) keeps its capital.
     */
    private val EVERYDAY = CARRIES_ON + setOf(
        "went", "go", "going", "get", "got", "had", "have", "has", "did", "do", "does", "said", "say", "think",
        "thought", "felt", "feel", "not", "no", "yes", "now", "there", "here", "home", "work", "shop", "school",
        "much", "more", "all", "one", "two", "lot", "bit", "day", "time", "way", "thing", "things", "people",
        "today", "tomorrow", "yesterday", "morning", "afternoon", "evening", "night", "week", "still", "also",
        "again", "back", "out", "up", "down", "over", "after", "before", "around", "made", "make", "came", "come",
        "saw", "see", "want", "wanted", "need", "needed", "know", "knew", "good", "bad", "nice", "great", "fine",
        "tired", "happy", "sad", "kind", "sort", "pretty", "quite", "maybe", "probably", "actually",
    )

    /**
     * [segments], each a stretch of speech between pauses, as tidy sentences in one paragraph; empty if
     * nothing was said but fillers. A pause ends a sentence unless it came straight after a word a
     * sentence can't end on.
     */
    fun clean(segments: List<String>): String {
        val sentences = mutableListOf<String>()
        segments.mapNotNull { cleanSegment(it) }.forEach { (next, saidWithCapital) ->
            val last = sentences.lastOrNull()
            val lastWord = last?.removeSuffix(".")?.substringAfterLast(' ')?.lowercase()
            if (last != null && last.endsWith(".") && !last.endsWith("..") && lastWord in CARRIES_ON)
                sentences[sentences.size - 1] = last.removeSuffix(".") + " " + carryingOn(next, saidWithCapital)
            else sentences += next
        }
        return sentences.joinToString(" ")
    }

    /**
     * [text], carrying on a sentence: its first word lowered unless the recogniser gave it a capital
     * ([saidWithCapital]) for a reason other than starting the stretch, as a name has ("I" stays)
     */
    private fun carryingOn(text: String, saidWithCapital: Boolean): String {
        val first = text.substringBefore(' ').trimEnd(',', '.', '!', '?', ';', ':')
        if (first == "I" || first.startsWith("I'")) return text
        return if (!saidWithCapital || first.lowercase() in EVERYDAY) text.replaceFirstChar { it.lowercase() } else text
    }

    /** [raw] as a tidy sentence, and whether it started with a capital before being given one; null if it was only fillers */
    private fun cleanSegment(raw: String): Pair<String, Boolean>? {
        var text = raw.replace('\n', ' ').replace(SPACES, " ").trim()
        text = FILLERS.replace(text) { it.groupValues[1] }
        text = text.replace(SPACES, " ").trim().trimStart(',', '.', ' ')
        text = LEADING_YEAH.replace(text, "")
        text = DOUBLED.replace(text) { match ->
            val word = match.groupValues[1]
            if (word.lowercase() in KEEP_DOUBLED) match.value else word
        }
        text = LONE_I.replace(text, "I")
        text = text.replace(SPACE_BEFORE_MARK, "$1").replace(COMMA_RUN, ",").replace(SPACES, " ").trim().trim(',', ' ')
        if (text.none { it.isLetterOrDigit() }) return null
        val saidWithCapital = text.first().isUpperCase()
        text = text.replaceFirstChar { it.uppercase() }
        text = SENTENCE_START.replace(text) { it.groupValues[1] + it.groupValues[2].uppercase() }
        if (text.last() !in ".!?") text += "."
        return text to saidWithCapital
    }

    private val WORD = Regex("[\\p{L}\\p{N}']+")

    fun wordCount(text: String) = WORD.findAll(text).count()

    /**
     * Whether [tidied], the AI's version of [local], can stand in for it: about the same number of words
     * (filler and repeats gone, nothing summarised away or made up).
     */
    fun acceptTidy(local: String, tidied: String?): Boolean {
        if (tidied.isNullOrBlank()) return false
        val before = wordCount(local)
        val after = wordCount(tidied)
        if (before == 0) return false
        return after >= before * 0.75 && after <= before * 1.25 + 10
    }
}
