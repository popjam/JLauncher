package app.olauncher.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
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
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
 * One day's note on a page of its own, to read or write at length: the whole page scrolls with the
 * text, the line being typed stays above the keyboard, and it's saved as it's written. Record adds
 * spoken words to the end. Opened from a search, the search's words are marked until it's changed.
 */
class JournalEntryFragment : Fragment() {

    private lateinit var store: MoodStore
    private lateinit var date: LocalDate
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var input: EditText
    private lateinit var words: TextView

    /** The note as last saved (or loaded), to tell whether there's anything to save */
    private var saved = ""
    private val handler = Handler(Looper.getMainLooper())
    private val saveSoon = Runnable { save() }
    private var marked = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        store = MoodStore(context)
        date = arguments?.getString(DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val ink = context.getColorFromAttr(R.attr.primaryColor)
        fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)
        val day = store.load().days[date.toString()]
        saved = day?.note.orEmpty()

        words = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = 13f
            setTextColor(faint(150))
        }
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            day?.mood?.let { mood ->
                addView(PixelFaceView(context).apply { this.mood = mood },
                    LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()).apply { marginEnd = 10.dpToPx() })
            }
            addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pillButton(context, ink, getString(R.string.journal_open_day), null) {
                save()
                findNavController().navigate(R.id.action_global_dayFragment, DayFragment.args(date))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() })
            addView(voiceButton(ink) { record() })
        }
        input = EditText(context).apply {
            setText(saved)
            hint = getString(if (date == LocalDate.now()) R.string.journal_today_hint else R.string.journal_day_hint)
            textSize = 17f
            setTextColor(ink)
            setHintTextColor(faint(110))
            setLineSpacing(4.dpToPx().toFloat(), 1.1f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            background = null
            // Grows with the text: the page scrolls, not the box
            isVerticalScrollBarEnabled = false
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (marked && s != null) unmark(s)
                    showWords()
                    handler.removeCallbacks(saveSoon)
                    handler.postDelayed(saveSoon, SAVE_DELAY_MS)
                    if (hasFocus()) post { keepCursorVisible() }
                }
            })
            setOnFocusChangeListener { _, focused -> if (!focused) save() else post { keepCursorVisible() } }
        }
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 48.dpToPx(), 20.dpToPx(), 96.dpToPx())
            addView(pageHeader(context, StatIcons.BOOK, title(date)) { close() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (-20).dpToPx() })
            addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 4.dpToPx() })
            // Takes the rest of the page, so a tap anywhere below the text starts writing
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = 14.dpToPx()
                marginStart = 4.dpToPx()
                marginEnd = 4.dpToPx()
            })
        }
        scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            addView(content)
        }
        // The keyboard takes the bottom of the page: the text stops above it
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = maxOf(ime, bars))
            if (input.hasFocus()) view.post { keepCursorVisible() }
            insets
        }
        showWords()
        markSearch(arguments?.getString(QUERY).orEmpty())
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(scroll)
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) close() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        VoiceNotes.addListener(voiceAdded)
    }

    override fun onResume() {
        super.onResume()
        // Changed elsewhere meanwhile (on the day page, say) and not here: shown as it is now
        val now = store.load().days[date.toString()]?.note.orEmpty()
        if (now != saved && input.text.toString() == saved) {
            saved = now
            input.setText(now)
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        VoiceNotes.removeListener(voiceAdded)
        input.hideKeyboard()
        super.onDestroyView()
    }

    private fun close() {
        save()
        input.hideKeyboard()
        findNavController().popBackStack()
    }

    private fun save() {
        handler.removeCallbacks(saveSoon)
        if (view == null) return
        val text = input.text?.toString().orEmpty()
        if (text.trim() == saved.trim()) return
        store.setNote(date, text)
        saved = text
    }

    private fun record() {
        save()
        recordVoiceNote(date)
    }

    /** Spoken words, already added to the day's note: onto the end here too, keeping anything typed meanwhile */
    private val voiceAdded: (LocalDate, String) -> Unit = { day, text ->
        if (day == date && view != null) {
            val now = input.text?.toString().orEmpty().trimEnd()
            val cursor = input.selectionEnd
            val typing = input.hasFocus()
            input.setText(if (now.isEmpty()) text else now + "\n\n" + text)
            // Typing meanwhile: the cursor stays where it was
            if (typing && cursor >= 0) input.setSelection(cursor.coerceAtMost(input.length()))
            saved = store.load().days[date.toString()]?.note.orEmpty()
            // Typed meanwhile and not yet saved: saved with the new words
            save()
            if (!typing) scroll.post { scroll.smoothScrollTo(0, content.height) }
        }
    }

    private fun showWords() {
        val count = Transcript.wordCount(input.text?.toString().orEmpty())
        words.text = if (count == 0) "" else resources.getQuantityString(R.plurals.journal_words, count, count)
    }

    /** Scrolls just enough that the line with the cursor is above the keyboard */
    private fun keepCursorVisible() {
        if (view == null) return
        val layout = input.layout ?: return
        val line = layout.getLineForOffset(input.selectionEnd.coerceAtLeast(0))
        var top = 0
        var v: View = input
        while (v !== content) {
            top += v.top
            v = v.parent as? View ?: return
        }
        val lineBottom = top + input.paddingTop + layout.getLineBottom(line) + 24.dpToPx()
        val lineTop = top + input.paddingTop + layout.getLineTop(line) - 24.dpToPx()
        val visible = scroll.height - scroll.paddingBottom
        when {
            lineBottom > scroll.scrollY + visible -> scroll.scrollTo(0, lineBottom - visible)
            lineTop < scroll.scrollY -> scroll.scrollTo(0, lineTop.coerceAtLeast(0))
        }
    }

    /** Marks each of [query]'s words in the note and scrolls to the first */
    private fun markSearch(query: String) {
        val text = input.text ?: return
        val ranges = JournalText.matchRanges(text.toString(), JournalText.words(query))
        ranges.forEach { range ->
            text.setSpan(BackgroundColorSpan(JournalText.MARK), range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        marked = ranges.isNotEmpty()
        val first = ranges.firstOrNull()?.first ?: return
        input.post {
            val layout = input.layout ?: return@post
            val y = input.top + input.paddingTop + layout.getLineTop(layout.getLineForOffset(first))
            scroll.scrollTo(0, (y - scroll.height / 3).coerceAtLeast(0))
        }
    }

    private fun unmark(text: Editable) {
        marked = false
        text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach { text.removeSpan(it) }
    }

    private fun title(date: LocalDate): String {
        val pattern = if (date.year == LocalDate.now().year) "EEE d MMMM" else "EEE d MMM yyyy"
        return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    companion object {
        const val DATE = "date"
        const val QUERY = "query"
        private const val SAVE_DELAY_MS = 1500L

        fun args(date: LocalDate, query: String = "") = bundleOf(DATE to date.toString(), QUERY to query)
    }
}
