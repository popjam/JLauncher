package app.olauncher.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.JournalText
import app.olauncher.helper.Transcript
import app.olauncher.helper.VoiceNotes
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every note written, newest first, each with that day's mood face and the start of the note, and a
 * search across them that shows the parts around what was searched for. Today's note is at the top.
 * Tapping a note opens it on a page of its own ([JournalEntryFragment]) to read or write at length.
 */
class JournalFragment : Fragment() {

    private lateinit var store: MoodStore
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var search: EditText
    private lateinit var today: LinearLayout
    private lateinit var pending: LinearLayout
    private var notes: List<Pair<LocalDate, MoodStore.Day>> = emptyList()

    /** How many notes the list shows, before More is tapped */
    private var limit = PAGE

    private val handler = Handler(Looper.getMainLooper())
    private val ink get() = requireContext().getColorFromAttr(R.attr.primaryColor)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        store = MoodStore(context)
        val ink = ink
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        count = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = 13f
            setTextColor(ink)
            alpha = 0.55f
        }
        search = EditText(context).apply {
            hint = getString(R.string.journal_search)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(ColorUtils.setAlphaComponent(ink, 110))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                cornerRadius = 22.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(ink, 22))
            }
            setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 12.dpToPx())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({
                        if (view != null) {
                            limit = PAGE
                            show()
                        }
                    }, 150)
                }
            })
        }
        today = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pending = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 48.dpToPx(), 20.dpToPx(), 64.dpToPx())
            addView(pageHeader(context, StatIcons.BOOK, getString(R.string.journal)) { findNavController().popBackStack() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (-20).dpToPx() })
            addView(today, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dpToPx() })
            addView(pending, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dpToPx() })
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20.dpToPx() })
            addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 14.dpToPx()
                bottomMargin = 4.dpToPx()
                marginStart = 4.dpToPx()
            })
            addView(list)
        }
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        VoiceNotes.addListener(voiceAdded)
        VoiceNotes.addPendingListener(pendingChanged)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        VoiceNotes.removeListener(voiceAdded)
        VoiceNotes.removePendingListener(pendingChanged)
        search.hideKeyboard()
        super.onDestroyView()
    }

    private val voiceAdded: (LocalDate, String) -> Unit = { _, _ -> if (view != null) refresh() }
    private val pendingChanged: () -> Unit = { if (view != null) fillPending(pending) }

    private fun refresh() {
        load()
        fillToday()
        fillPending(pending)
        show()
    }

    private fun load() {
        notes = store.load().days.mapNotNull { (key, day) ->
            if (day.note.isNullOrBlank()) return@mapNotNull null
            runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to day }
        }.sortedByDescending { it.first }
    }

    private fun open(date: LocalDate, query: String = "") {
        search.hideKeyboard()
        findNavController().navigate(R.id.action_global_journalEntryFragment, JournalEntryFragment.args(date, query))
    }

    /** Today: the start of its note (or a line to start one), opening it, and Record */
    private fun fillToday() {
        val context = requireContext()
        val ink = ink
        val date = LocalDate.now()
        val note = notes.firstOrNull { it.first == date }?.second?.note.orEmpty()
        today.removeAllViews()
        today.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(ink, 18))
            }
            foreground = context.getDrawable(android.R.drawable.list_selector_background)
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 12.dpToPx())
            isClickable = true
            setOnClickListener { open(date) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = listOfNotNull(getString(R.string.today) + " · " + dateText(date), wordsText(note)).joinToString(" · ")
                    textSize = 13f
                    setTextColor(ink)
                    alpha = 0.6f
                    setTypeface(null, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(voiceButton(ink) { recordVoiceNote(date) })
            })
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = if (note.isBlank()) getString(R.string.journal_today_hint) else JournalText.preview(note, 400)
                textSize = 15f
                setTextColor(ink)
                alpha = if (note.isBlank()) 0.45f else 1f
                setLineSpacing(0f, 1.15f)
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 8.dpToPx(), 0, 2.dpToPx())
            })
        })
    }

    /** The notes matching the search (every word of it, anywhere in the note or its date), newest first */
    private fun show() {
        val context = requireContext()
        val query = search.text.toString()
        val words = JournalText.words(query)
        val todayDate = LocalDate.now()
        // Today's note is at the top, so it's only listed when searching
        val shown = notes.filter { (date, day) ->
            if (words.isEmpty() && date == todayDate) return@filter false
            JournalText.matches(day.note.orEmpty() + " " + dateText(date), words)
        }
        count.text = when {
            notes.isEmpty() -> getString(R.string.journal_empty)
            words.isEmpty() -> resources.getQuantityString(R.plurals.journal_count, notes.size, notes.size)
            else -> resources.getQuantityString(R.plurals.journal_found, shown.size, shown.size)
        }
        list.removeAllViews()
        val ink = ink
        var lastMonth: String? = null
        shown.take(limit).forEach { (date, day) ->
            // A heading for each month
            val month = date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            if (month != lastMonth) {
                lastMonth = month
                list.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = month
                    textSize = 13f
                    setTextColor(ink)
                    alpha = 0.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(4.dpToPx(), 22.dpToPx(), 0, 6.dpToPx())
                })
            }
            val note = day.note.orEmpty()
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 12.dpToPx())
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(PixelFaceView(context).apply {
                    val mood = day.mood
                    if (mood != null) this.mood = mood else visibility = View.INVISIBLE
                }, LinearLayout.LayoutParams(26.dpToPx(), 26.dpToPx()).apply { marginEnd = 14.dpToPx(); topMargin = 2.dpToPx() })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context, null, 0, R.style.TextSmall).apply {
                        text = listOfNotNull(dateText(date), wordsText(note)).joinToString(" · ")
                        textSize = 13f
                        setTextColor(ink)
                        alpha = 0.6f
                    })
                    addView(TextView(context, null, 0, R.style.TextSmall).apply {
                        // The start of it; while searching, the parts around the words searched for
                        text = if (words.isEmpty()) JournalText.preview(note) else marked(JournalText.snippet(note, words), words)
                        textSize = 15f
                        setTextColor(ink)
                        setPadding(0, 3.dpToPx(), 0, 0)
                        setLineSpacing(0f, 1.15f)
                        maxLines = if (words.isEmpty()) 4 else 6
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { open(date, query) }
            })
        }
        // A long journal comes a page at a time
        if (shown.size > limit) list.addView(pillButton(context, ink, getString(R.string.journal_more), null) {
            limit += PAGE
            show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 16.dpToPx()
        })
    }

    /** "1,240 words", for notes long enough for it to matter */
    private fun wordsText(note: String): String? {
        val count = Transcript.wordCount(note)
        return if (count < LONG_WORDS) null else resources.getQuantityString(R.plurals.journal_words, count, count)
    }

    /** [text] with each of [words] marked */
    private fun marked(text: String, words: List<String>): CharSequence {
        val spannable = SpannableString(text)
        JournalText.matchRanges(text, words).forEach { range ->
            spannable.setSpan(BackgroundColorSpan(JournalText.MARK), range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private fun dateText(date: LocalDate): String {
        val pattern = if (date.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"
        return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    companion object {
        private const val PAGE = 50

        /** Notes this long show how many words they are */
        private const val LONG_WORDS = 100
    }
}
