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
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every note written, newest first, each with that day's mood face, and a search across them. Tapping
 * a note opens its day. Pulled down or swiped back like the other stats pages.
 */
class JournalFragment : Fragment() {

    private lateinit var store: MoodStore
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var search: EditText
    private var notes: List<Pair<LocalDate, MoodStore.Day>> = emptyList()

    /** Today's note, written straight in at the top */
    private lateinit var todayInput: EditText
    private lateinit var todayTitle: TextView
    private var todayShown: String? = null
    private var todayDate: LocalDate = LocalDate.now()

    /** Days with a voice note */
    private var voiced: Set<LocalDate> = emptySet()
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
                    handler.postDelayed({ if (view != null) show() }, 120)
                }
            })
        }
        todayTitle = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = 13f
            setTextColor(ink)
            alpha = 0.6f
            setTypeface(null, Typeface.BOLD)
        }
        todayInput = EditText(context).apply {
            hint = getString(R.string.journal_today_hint)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(ColorUtils.setAlphaComponent(ink, 110))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP
            background = GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(ink, 18))
            }
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            setOnFocusChangeListener { _, focused -> if (!focused) saveToday() }
            // Holding it while not typing records a voice note, like the day page's note
            setOnLongClickListener {
                if (hasFocus() && androidx.core.view.ViewCompat.getRootWindowInsets(this)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) return@setOnLongClickListener false
                recordToday()
                true
            }
        }
        val todayRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(todayTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dpToPx() })
            addView(voiceButton(ink) { recordToday() })
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 48.dpToPx(), 20.dpToPx(), 64.dpToPx())
            addView(pageHeader(context, StatIcons.BOOK, getString(R.string.journal)) { findNavController().popBackStack() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (-20).dpToPx() })
            addView(todayRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20.dpToPx()
            })
            addView(todayInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dpToPx()
            })
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 24.dpToPx()
            })
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

    override fun onResume() {
        super.onResume()
        fillToday()
        load()
        show()
    }

    override fun onPause() {
        saveToday()
        stopVoicePlayback()
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        search.hideKeyboard()
        todayInput.hideKeyboard()
        super.onDestroyView()
    }

    /** Today's note into the box (it may have changed on the day page, or the day turned over) */
    private fun fillToday() {
        val now = LocalDate.now()
        if (now != todayDate) {
            saveToday()
            todayDate = now
            todayShown = null
        }
        todayTitle.text = getString(R.string.today) + " · " + dateText(todayDate)
        val note = store.load().days[todayDate.toString()]?.note.orEmpty()
        if (todayShown == null || (!todayInput.hasFocus() && note != todayShown)) {
            todayShown = note
            todayInput.setText(note)
        }
    }

    private fun saveToday() {
        val text = todayInput.text?.toString() ?: return
        if (text.trim() == todayShown?.trim().orEmpty()) return
        store.setNote(todayDate, text)
        todayShown = text
        if (view != null && isResumed) {
            load()
            show()
        }
    }

    /** Records a voice note for today; its words go on the end of today's note */
    private fun recordToday() {
        saveToday()
        val date = todayDate
        recordVoiceNote(date, onText = { text ->
            val showing = date == todayDate && view != null
            val now = (if (showing) todayInput.text?.toString() else store.load().days[date.toString()]?.note).orEmpty().trim()
            val note = if (now.isEmpty()) text else now + "\n" + text
            store.setNote(date, note)
            if (date == todayDate && view != null) {
                todayShown = note
                todayInput.setText(note)
            }
            if (view != null) { load(); show() }
        }, onSaved = { if (view != null) { load(); show() } })
    }

    private fun load() {
        voiced = app.olauncher.helper.VoiceNotes.dates(requireContext())
        val days = store.load().days
        val written = days.mapNotNull { (key, day) ->
            if (day.note.isNullOrBlank()) return@mapNotNull null
            runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to day }
        }
        // Days with only a recording (not written out) are listed too
        val spoken = voiced.filter { date -> written.none { it.first == date } }
            .map { it to (days[it.toString()] ?: MoodStore.Day(mood = null, usageMs = null)) }
        notes = (written + spoken).sortedByDescending { it.first }
    }

    /** The notes matching the search (every word of it, anywhere in the note or its date), newest first */
    private fun show() {
        val context = requireContext()
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shown = notes.filter { (date, day) ->
            val haystack = (day.note.orEmpty() + " " + dateText(date)).lowercase(Locale.getDefault())
            words.all { it in haystack }
        }
        count.text = when {
            notes.isEmpty() -> getString(R.string.journal_empty)
            words.isEmpty() -> resources.getQuantityString(R.plurals.journal_count, notes.size, notes.size)
            else -> resources.getQuantityString(R.plurals.journal_found, shown.size, shown.size)
        }
        list.removeAllViews()
        val ink = ink
        val highlight = ColorUtils.setAlphaComponent(0xFFFFD54F.toInt(), 110)
        var lastMonth: String? = null
        shown.forEach { (date, day) ->
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
                        text = if (date in voiced) getString(R.string.journal_with_voice, dateText(date)) else dateText(date)
                        textSize = 13f
                        setTextColor(ink)
                        alpha = 0.6f
                    })
                    addView(TextView(context, null, 0, R.style.TextSmall).apply {
                        text = day.note?.takeIf { it.isNotBlank() }?.let { marked(it, words, highlight, ink) } ?: getString(R.string.voice_note_only)
                        textSize = 15f
                        setTextColor(ink)
                        setPadding(0, 3.dpToPx(), 0, 0)
                        setLineSpacing(0f, 1.15f)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // The whole day, where the note can be changed too
                setOnClickListener { findNavController().navigate(R.id.action_global_dayFragment, DayFragment.args(date)) }
            })
        }
    }

    /** [text] with each of [words] marked */
    private fun marked(text: String, words: List<String>, color: Int, ink: Int): CharSequence {
        if (words.isEmpty()) return text
        val spannable = SpannableString(text)
        words.forEach { word ->
            var at = text.indexOf(word, ignoreCase = true)
            while (at >= 0 && at + word.length <= text.length) {
                spannable.setSpan(BackgroundColorSpan(color), at, at + word.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(ink), at, at + word.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                at = text.indexOf(word, at + word.length, ignoreCase = true)
            }
        }
        return spannable
    }

    private fun dateText(date: LocalDate): String {
        val pattern = if (date.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"
        return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }
}
