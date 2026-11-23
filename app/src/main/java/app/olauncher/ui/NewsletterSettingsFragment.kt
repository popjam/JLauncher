package app.olauncher.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.NewsletterSync
import app.olauncher.helper.NewsletterSync.Kind
import app.olauncher.helper.NewsletterSync.Setting
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showToast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * A newsletter profile's settings, as the PC sent them: its subreddits, and how posts, comments,
 * images and the book are done. With [SUBREDDIT], one subreddit's own settings instead. Each change
 * goes to the PC, which writes it into the profile's file there; the email and Reddit logins never
 * leave the PC.
 */
class NewsletterSettingsFragment : CardPageFragment() {

    companion object {
        const val PROFILE = "profile"
        const val SUBREDDIT = "subreddit"

        /** Each profile's settings as last changed here, and when: shown over the PC's until it has caught up */
        private val recent = mutableMapOf<String, Pair<Long, JSONObject>>()
        private const val RECENT_MS = 60_000L
    }

    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList

    private val profile by lazy { requireArguments().getString(PROFILE) ?: NewsletterSync.DAILY }
    private var subIndex = -1

    private var settings: JSONObject? = null
    private val handler = Handler(Looper.getMainLooper())
    private var savePending = false
    private val save = Runnable { saveNow() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        subIndex = requireArguments().getInt(SUBREDDIT, -1)
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        val header =LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx())
            addView(header)
            addView(cardList)
        }
        headerHolder = header
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(content)
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    private var headerHolder: LinearLayout? = null

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onPause() {
        // Anything waiting goes now
        if (savePending) saveNow()
        super.onPause()
    }

    private fun recentSettings(): JSONObject? =
        recent[profile]?.takeIf { System.currentTimeMillis() - it.first < RECENT_MS }?.second

    private fun load() {
        val context = requireContext().applicationContext
        if (settings == null) settings = recentSettings()
        render()
        thread {
            val fetched = runCatching { NewsletterSync.fetch(context).profiles[profile] }.getOrNull()
            handler.post {
                if (!isAdded) return@post
                if (fetched == null) context.showToast(R.string.newsletter_cant_reach)
                // A change made here lately wins over what the PC has, until it's caught up
                if (fetched != null && !savePending) settings = recentSettings() ?: fetched
                render()
            }
        }
    }

    private fun changed(next: JSONObject) {
        settings = next
        recent[profile] = System.currentTimeMillis() to next
        savePending = true
        handler.removeCallbacks(save)
        handler.postDelayed(save, 1_200L)
        render()
    }

    private fun saveNow() {
        handler.removeCallbacks(save)
        savePending = false
        val s = settings ?: return
        val context = context?.applicationContext ?: return
        thread {
            if (runCatching { NewsletterSync.saveSettings(context, profile, s) }.isFailure)
                handler.post { context.showToast(R.string.newsletter_cant_reach) }
        }
    }

    private fun render() {
        if (!isAdded) return
        val context = requireContext()
        val s = settings
        val subs = s?.let { NewsletterSync.subreddits(it) }
        val sub = subs?.getOrNull(subIndex)
        headerHolder?.removeAllViews()
        val title = when {
            subIndex >= 0 -> sub?.let { "r/${it.name}" } ?: ""
            profile == NewsletterSync.WEEKLY -> getString(R.string.newsletter_weekly)
            else -> getString(R.string.newsletter_daily)
        }
        headerHolder?.addView(pageHeader(context, StatIcons.BOOK, title) { findNavController().popBackStack() })
        cardList.removeAllViews()
        if (s == null) return
        if (subIndex >= 0) {
            if (sub != null && subs != null) subredditPage(s, subs, subIndex)
            return
        }
        subredditsSection(s, subs.orEmpty())
        section(R.string.newsletter_posts, s, listOf(
            "defaults" to Setting("postsPerSubreddit", Kind.INT),
            "defaults" to Setting("sort", Kind.CHOICE, NewsletterSync.SORTS),
            "defaults" to Setting("timeframe", Kind.CHOICE, NewsletterSync.TIMEFRAMES),
            "reddit" to Setting("randomizeSubredditOrder", Kind.BOOL),
            "defaults" to Setting("includeInternalLinks", Kind.BOOL),
            "defaults" to Setting("includeVideoContent", Kind.BOOL),
            "defaults" to Setting("skipImageAndGalleryPosts", Kind.BOOL),
            "defaults" to Setting("allowImagePostsIfCantFindOtherPosts", Kind.BOOL),
            "defaults" to Setting("skipUnfetchableArticles", Kind.BOOL),
            "reddit" to Setting("sourceMode", Kind.CHOICE, NewsletterSync.SOURCE_MODES),
        ))
        section(R.string.newsletter_comments, s, listOf(
            "defaults" to Setting("commentsPerPost", Kind.INT),
            "defaults" to Setting("commentStyle", Kind.CHOICE, NewsletterSync.COMMENT_STYLES),
            "defaults" to Setting("maxCommentDepth", Kind.INT),
            "defaults" to Setting("minCommentLength", Kind.INT),
            "defaults" to Setting("skipAutoModerator", Kind.BOOL),
            "defaults" to Setting("showNestedCommentGuideLines", Kind.BOOL),
        ))
        section(R.string.newsletter_images, s, listOf(
            "reddit" to Setting("downloadImages", Kind.BOOL),
            "reddit" to Setting("imageOptimizationPreset", Kind.CHOICE, NewsletterSync.IMAGE_PRESETS),
        ))
        section(R.string.newsletter_book, s, listOf(
            "epub" to Setting("title", Kind.TEXT),
            "epub" to Setting("generateDatedCover", Kind.BOOL),
            "epub" to Setting("hierarchicalTOC", Kind.BOOL),
            "epub" to Setting("simplifiedTOC", Kind.BOOL),
        ))
    }

    // --- The profile ---

    private fun subredditsSection(s: JSONObject, subs: List<NewsletterSync.Subreddit>) {
        val card = Card(getString(R.string.newsletter_subreddits_count, subs.size))
        val rows = subs.mapIndexed { i, sub ->
            row(sub.name, ownSummary(sub.own)) {
                findNavController().navigate(R.id.newsletterSettingsFragment, bundleOf(PROFILE to profile, SUBREDDIT to i))
            }
        } + row(getString(R.string.newsletter_add_subreddit), null, faded = true) { askAdd(s, subs) }
        card.body(rows)
    }

    /** What a subreddit sets its own way, shortly: "controversial · day · 10" */
    private fun ownSummary(own: JSONObject): String? {
        val parts = listOfNotNull(own.optString("sort").ifEmpty { null }, own.optString("timeframe").ifEmpty { null },
            if (own.has("postsPerSubreddit")) own.optInt("postsPerSubreddit").toString() else null)
        val others = own.length() - listOf("sort", "timeframe", "postsPerSubreddit").count { own.has(it) }
        return (parts + if (others > 0) listOf("+$others") else emptyList()).joinToString(" · ").ifEmpty { null }
    }

    private fun askAdd(s: JSONObject, subs: List<NewsletterSync.Subreddit>) {
        val field = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "")
        AppDialog(requireContext())
            .setTitle(R.string.newsletter_add_subreddit)
            .setView(padded(field))
            .setPositiveButton(R.string.add) { _, _ ->
                val name = NewsletterSync.cleanName(field.text.toString())
                when {
                    name == null -> requireContext().showToast(R.string.newsletter_bad_name)
                    else -> changed(NewsletterSync.withSubreddits(s, subs + NewsletterSync.Subreddit(name, JSONObject())))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A section of settings, each read from and written to [place]: "reddit", "defaults" or "epub" */
    private fun section(title: Int, s: JSONObject, settings: List<Pair<String, Setting>>) {
        val card = Card(getString(title))
        card.body(settings.map { (place, setting) ->
            val holder = holder(s, place)
            row(label(setting.key), shown(setting, holder.opt(setting.key))) {
                edit(setting, holder.opt(setting.key), allowDefault = false) { value ->
                    val next = JSONObject(s.toString())
                    holder(next, place).apply { if (value == null) remove(setting.key) else put(setting.key, value) }
                    changed(next)
                }
            }
        })
    }

    private fun holder(s: JSONObject, place: String): JSONObject {
        fun child(o: JSONObject, key: String) = o.optJSONObject(key) ?: JSONObject().also { o.put(key, it) }
        return when (place) {
            "epub" -> child(s, "epub")
            "reddit" -> child(s, "reddit")
            else -> child(child(s, "reddit"), "defaults")
        }
    }

    // --- One subreddit ---

    private fun subredditPage(s: JSONObject, subs: List<NewsletterSync.Subreddit>, index: Int) {
        val sub = subs[index]
        val defaults = holder(s, "defaults")
        val card = Card(getString(R.string.newsletter_own_settings))
        card.body(NewsletterSync.SUBREDDIT_SETTINGS.map { setting ->
            val own = sub.own.opt(setting.key)
            val value = if (own != null) shown(setting, own) else getString(R.string.newsletter_as_default, shown(setting, defaults.opt(setting.key)))
            row(label(setting.key), value, faded = own == null) {
                edit(setting, own, allowDefault = true) { picked ->
                    val ownNext = JSONObject(sub.own.toString()).apply { if (picked == null) remove(setting.key) else put(setting.key, picked) }
                    changed(NewsletterSync.withSubreddits(s, subs.mapIndexed { i, x -> if (i == index) NewsletterSync.Subreddit(x.name, ownNext) else x }))
                }
            }
        })
        val arrange = Card(getString(R.string.newsletter_arrange))
        arrange.body(listOfNotNull(
            if (index > 0) row(getString(R.string.newsletter_move_up), null) { move(s, subs, index, -1) } else null,
            if (index < subs.lastIndex) row(getString(R.string.newsletter_move_down), null) { move(s, subs, index, 1) } else null,
            row(getString(R.string.newsletter_remove), null, faded = true) {
                if (subs.size <= 1) return@row requireContext().showToast(R.string.newsletter_keep_one)
                changed(NewsletterSync.withSubreddits(s, subs.filterIndexed { i, _ -> i != index }))
                findNavController().popBackStack()
            },
        ))
    }

    private fun move(s: JSONObject, subs: List<NewsletterSync.Subreddit>, index: Int, by: Int) {
        val list = subs.toMutableList()
        list.add(index + by, list.removeAt(index))
        // This page follows the subreddit to where it went
        subIndex = index + by
        requireArguments().putInt(SUBREDDIT, subIndex)
        changed(NewsletterSync.withSubreddits(s, list))
    }

    // --- Changing one setting ---

    /** Asks for [setting]'s new value and gives it to [onPicked]; null means the default */
    private fun edit(setting: Setting, current: Any?, allowDefault: Boolean, onPicked: (Any?) -> Unit) {
        val context = requireContext()
        when (setting.kind) {
            Kind.BOOL -> {
                val options = listOfNotNull(if (allowDefault) getString(R.string.newsletter_default) else null, getString(R.string.on), getString(R.string.off))
                val selected = when (current) { true -> 0; false -> 1; else -> -1 }.let { if (allowDefault) if (it < 0) 0 else it + 1 else it }
                choose(label(setting.key), options, selected) { which ->
                    val i = if (allowDefault) which - 1 else which
                    onPicked(if (i < 0) null else i == 0)
                }
            }
            Kind.CHOICE -> {
                val options = (if (allowDefault) listOf(getString(R.string.newsletter_default)) else emptyList()) + setting.choices
                val at = setting.choices.indexOf(current as? String)
                val selected = if (allowDefault) at + 1 else at
                choose(label(setting.key), options, selected) { which ->
                    val i = if (allowDefault) which - 1 else which
                    onPicked(setting.choices.getOrNull(i))
                }
            }
            Kind.INT, Kind.TEXT -> {
                val number = setting.kind == Kind.INT
                val field = field(if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    current?.toString().orEmpty())
                val dialog = AppDialog(context)
                    .setTitle(label(setting.key))
                    .setView(padded(field))
                    .setPositiveButton(R.string.save) { _, _ ->
                        val text = field.text.toString().trim()
                        if (number) text.toIntOrNull()?.takeIf { it in 0..1000 }?.let(onPicked) ?: context.showToast(R.string.newsletter_bad_number)
                        else if (text.isNotEmpty()) onPicked(text)
                    }
                    .setNegativeButton(R.string.cancel, null)
                if (allowDefault) dialog.setNeutralButton(R.string.newsletter_default) { _, _ -> onPicked(null) }
                dialog.show()
            }
        }
    }

    private fun choose(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AppDialog(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .show()
    }

    private fun shown(setting: Setting, value: Any?): String = when {
        value == null || value == JSONObject.NULL -> "–"
        setting.kind == Kind.BOOL -> getString(if (value == true) R.string.on else R.string.off)
        else -> value.toString()
    }

    private fun label(key: String): String = getString(when (key) {
        "postsPerSubreddit" -> R.string.newsletter_set_posts
        "sort" -> R.string.newsletter_set_sort
        "timeframe" -> R.string.newsletter_set_timeframe
        "randomizeSubredditOrder" -> R.string.newsletter_set_random
        "includeInternalLinks" -> R.string.newsletter_set_internal
        "includeVideoContent" -> R.string.newsletter_set_video
        "skipImageAndGalleryPosts" -> R.string.newsletter_set_skip_images
        "allowImagePostsIfCantFindOtherPosts" -> R.string.newsletter_set_images_if_short
        "skipUnfetchableArticles" -> R.string.newsletter_set_skip_unfetchable
        "sourceMode" -> R.string.newsletter_set_source
        "commentsPerPost" -> R.string.newsletter_set_comments
        "commentStyle" -> R.string.newsletter_set_comment_style
        "maxCommentDepth" -> R.string.newsletter_set_depth
        "minCommentLength" -> R.string.newsletter_set_min_length
        "skipAutoModerator" -> R.string.newsletter_set_automod
        "showNestedCommentGuideLines" -> R.string.newsletter_set_guides
        "downloadImages" -> R.string.newsletter_set_download_images
        "imageOptimizationPreset" -> R.string.newsletter_set_image_quality
        "title" -> R.string.newsletter_set_title
        "generateDatedCover" -> R.string.newsletter_set_cover
        "hierarchicalTOC" -> R.string.newsletter_set_toc_grouped
        "simplifiedTOC" -> R.string.newsletter_set_toc_simple
        else -> R.string.settings
    })

    // --- Pieces ---

    private fun field(type: Int, text: String) = EditText(requireContext()).apply {
        inputType = type
        setText(text)
        setSelection(this.text.length)
        isSingleLine = true
    }

    private fun padded(view: View) = FrameLayout(requireContext()).apply {
        setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 0)
        addView(view)
    }

    private fun row(title: String, value: String?, faded: Boolean = false, onTap: () -> Unit): View =
        settingRow(title, value, faded = faded, onTap = onTap)

    private fun Card.body(rows: List<View>) {
        rowsSection(rows)
    }
}
