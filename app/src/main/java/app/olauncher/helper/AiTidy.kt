package app.olauncher.helper

import android.content.Context
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Claude, for the journal: tidying a dictated note once the phone has written it out (filler and false
 * starts go, punctuation and paragraphs go in, and the words, facts and feelings stay as they were
 * said), and summarising a long note ([JournalSummaries]). Only with an Anthropic API key, which is
 * kept on the phone alone (not in the backup).
 */
object AiTidy {

    private const val MODEL = "claude-opus-5-5"

    private val TIDY = """
        You tidy up journal entries that someone dictated out loud and a phone's speech recognition wrote down. The person rambles while they think, so the text has filler words, false starts, repeats and missing punctuation, and the recogniser often capitalises a word in the middle of a sentence where the speaker paused.

        Rewrite it as clean, readable prose in their own voice:
        - Remove filler and verbal tics (um, uh, like, you know, I mean, yeah, sort of, kind of) where they carry no meaning, along with false starts and words said twice.
        - Add punctuation and capitals, fix capitals the recogniser put mid-sentence, and split it into paragraphs where the topic changes.
        - Fix a word the recogniser clearly misheard only when the right word is obvious from context (a medicine, an app or a place named nearby, say). When it isn't obvious, leave the words as they were.
        - Keep everything they actually said: every event, name, number, dose, plan, worry, feeling and opinion, in their order, in the first person, with their wording and tone. Don't summarise, shorten ideas, add anything, give advice, or comment.
        - No headings, lists, quotation marks around the whole thing, or notes about what you changed.

        Reply with the tidied entry inside <entry></entry> and nothing else.
    """.trimIndent()

    private val SUMMARY = """
        You summarise someone's journal entry for the day, so that scrolling back through their journal they can see at a glance what the day held.

        Write one or two plain sentences in the first person, as if they wrote it: how they felt, what happened, and anything they decided or want to change. Use their own words where you can. Don't add advice, judgement or anything they didn't say.

        Reply with the summary inside <summary></summary> and nothing else.
    """.trimIndent()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("ai_tidy", Context.MODE_PRIVATE)

    fun key(context: Context): String? = prefs(context).getString("key", null)?.takeIf { it.isNotBlank() }

    fun setKey(context: Context, key: String?) {
        prefs(context).edit().apply { if (key.isNullOrBlank()) remove("key") else putString("key", key.trim()) }.apply()
    }

    fun isOn(context: Context) = key(context) != null

    /** [text], a dictated note, tidied; null if it couldn't be (no key, no network, refused). Blocks: off the main thread. */
    fun tidy(context: Context, text: String): String? =
        ask(context, TIDY, "<transcript>\n$text\n</transcript>")?.let { tagged(it, "entry") }

    /** A sentence or two summing up [note], a day's journal entry; null if it couldn't be. Blocks: off the main thread. */
    fun summarise(context: Context, note: String): String? =
        ask(context, SUMMARY, "<entry>\n$note\n</entry>")?.let { tagged(it, "summary") }

    /** What's inside [reply]'s <[tag]></[tag]>, or null if it isn't there */
    fun tagged(reply: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(reply)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    /** Claude's reply to [message] under [system]; null if there's no key, it failed, or it didn't finish */
    private fun ask(context: Context, system: String, message: String): String? {
        val key = key(context) ?: return null
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofMinutes(3))
            .build()
        return try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16_000L)
                .system(system)
                .addUserMessage(message)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                // If the model declines, another one has a go
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val reply = client.beta().messages().create(params)
            if (reply.stopReason().orElse(null) != BetaStopReason.END_TURN) return null
            reply.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            client.close()
        }
    }
}
