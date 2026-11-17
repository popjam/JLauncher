package app.olauncher.helper

import android.content.Context
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Tidying a dictated note with Claude, once the phone has written it out: filler and false starts go,
 * punctuation and paragraphs go in, and the words, facts and feelings stay as they were said. Only with
 * an Anthropic API key, which is kept on the phone alone (not in the backup).
 */
object AiTidy {

    private const val MODEL = "claude-opus-5-5"

    private val SYSTEM = """
        You tidy up journal entries that someone dictated out loud and a phone's speech recognition wrote down. The person rambles while they think, so the text has filler words, false starts, repeats and missing punctuation.

        Rewrite it as clean, readable prose in their own voice:
        - Remove filler and verbal tics (um, uh, like, you know, I mean, yeah, sort of, kind of) where they carry no meaning, along with false starts and words said twice.
        - Add punctuation and capitals, and split it into paragraphs where the topic changes.
        - Fix a word the recogniser clearly misheard only when the right word is obvious from context.
        - Keep everything they actually said: every event, name, number, plan, worry, feeling and opinion, in their order, in the first person, with their wording and tone. Don't summarise, shorten ideas, add anything, give advice, or comment.
        - No headings, lists, quotation marks around the whole thing, or notes about what you changed.

        Reply with the tidied entry only.
    """.trimIndent()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("ai_tidy", Context.MODE_PRIVATE)

    fun key(context: Context): String? = prefs(context).getString("key", null)?.takeIf { it.isNotBlank() }

    fun setKey(context: Context, key: String?) {
        prefs(context).edit().apply { if (key.isNullOrBlank()) remove("key") else putString("key", key.trim()) }.apply()
    }

    fun isOn(context: Context) = key(context) != null

    /** [text] tidied, or null if it couldn't be (no key, no network, refused). Blocks: off the main thread. */
    fun tidy(context: Context, text: String): String? {
        val key = key(context) ?: return null
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofMinutes(3))
            .build()
        return try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16_000L)
                .system(SYSTEM)
                .addUserMessage("<transcript>\n$text\n</transcript>")
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                // If the model declines, another one has a go
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val message = client.beta().messages().create(params)
            if (message.stopReason().orElse(null) != BetaStopReason.END_TURN) return null
            message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").trim().ifEmpty { null }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            client.close()
        }
    }
}
