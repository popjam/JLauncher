package app.olauncher.helper

import app.olauncher.ui.Style
import java.util.Locale

/** Showing long journal notes in short spaces: the start of one, or the parts around a search's words */
object JournalText {

    private val WHITESPACE = Regex("\\s+")

    /** The search's words, lowercased */
    fun words(query: String): List<String> =
        query.trim().lowercase(Locale.getDefault()).split(WHITESPACE).filter { it.isNotEmpty() }

    /** Whether [text] has every one of [words] somewhere in it */
    fun matches(text: String, words: List<String>): Boolean {
        val haystack = text.lowercase(Locale.getDefault())
        return words.all { it in haystack }
    }

    /**
     * Where each of [words] appears in [text], to mark; none if lowercasing changes the text's length
     * (a few letters lowercase to more than one), which would put the marks in the wrong places
     */
    fun matchRanges(text: String, words: List<String>): List<IntRange> {
        val lower = text.lowercase(Locale.getDefault())
        if (lower.length != text.length) return emptyList()
        val ranges = mutableListOf<IntRange>()
        words.filter { it.isNotEmpty() }.forEach { word ->
            var at = lower.indexOf(word)
            while (at >= 0) {
                ranges += at until at + word.length
                at = lower.indexOf(word, at + word.length)
            }
        }
        return ranges.sortedBy { it.first }
    }

    /** The colour search matches are marked with */
    const val MARK = Style.HIGHLIGHT

    /** About the first [max] characters of [text], on one flow (line breaks as spaces), cut at a word with "…" */
    fun preview(text: String, max: Int = 280): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= max) return flat
        return cutAtWord(flat, max) + "…"
    }

    /**
     * The parts of [text] around the first place each of [words] appears, about [radius] characters
     * either side, joined with "…"; parts that overlap are merged. The start of the note if none appear.
     */
    fun snippet(text: String, words: List<String>, radius: Int = 90): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        val lower = flat.lowercase(Locale.getDefault())
        // A few letters lowercase to more than one, which would put the parts in the wrong places
        if (lower.length != flat.length) return preview(flat, radius * 2)
        val hits = words.mapNotNull { word -> lower.indexOf(word).takeIf { it >= 0 }?.let { it to it + word.length } }.sortedBy { it.first }
        if (hits.isEmpty()) return preview(flat, radius * 2)
        val ranges = mutableListOf<IntRange>()
        hits.forEach { (start, end) ->
            var from = (start - radius).coerceAtLeast(0)
            var to = (end + radius).coerceAtMost(flat.length)
            // Out to whole words
            if (from > 0) from = flat.indexOf(' ', from).let { if (it in from until start) it + 1 else from }
            if (to < flat.length) to = flat.lastIndexOf(' ', to).let { if (it >= end) it else to }
            val last = ranges.lastOrNull()
            if (last != null && from <= last.last + 1) ranges[ranges.size - 1] = last.first..maxOf(last.last, to - 1)
            else ranges += from until to
        }
        return buildString {
            ranges.forEachIndexed { i, range ->
                if (range.first > 0 || i > 0) append("…")
                append(flat.substring(range.first, range.last + 1).trim())
            }
            if (ranges.last().last + 1 < flat.length) append("…")
        }
    }

    private fun cutAtWord(text: String, max: Int): String {
        val space = text.lastIndexOf(' ', max)
        return (if (space > max / 2) text.substring(0, space) else text.substring(0, max)).trimEnd(',', ';', ':', ' ')
    }
}
