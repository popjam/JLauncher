package app.olauncher.ui

import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.DeviceTime
import app.olauncher.helper.Music
import app.olauncher.helper.Sleep
import app.olauncher.helper.Social
import app.olauncher.helper.Spending
import app.olauncher.helper.StatInfo
import app.olauncher.helper.StatKind
import app.olauncher.helper.Steps
import app.olauncher.helper.Vibecoding
import app.olauncher.helper.allStats
import app.olauncher.helper.bindFaceLabels
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything about one day together, and a way to change it: the top card edits the mood (or the stat
 * tapped in the list under it) and the note, then what was filled in by itself (screen time on each
 * device, sleep, where the day went, purchases, music, steps and vibecoding). One of [DayFragment]'s
 * pages; holding a stat opens its page.
 */
class DayPageFragment : CardPageFragment() {

    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList
    private lateinit var title: TextView
    private lateinit var store: MoodStore
    private lateinit var date: LocalDate
    private var noteInput: EditText? = null
    private var noteShown: String? = null
    private lateinit var scroll: ScrollView

    /** The stat the top card edits: Mood until another is tapped */
    private var editing: String = MoodStore.MOOD

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        store = MoodStore(context)
        date = arguments?.getString(DayFragment.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 4.dpToPx(), bottom = 64.dpToPx())
        }
        val ink = context.getColorFromAttr(R.attr.primaryColor)
        val header = pageHeader(context, null, "") { findNavController().popBackStack() }.also { h ->
            // The day's name goes in the header's title
            title = (h as LinearLayout).getChildAt(h.childCount - 1) as TextView
            title.textSize = 24f
        }
        return FrameLayout(context).apply {
            isClickable = true
            addView(ScrollView(context).also { scroll = it }.apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    updatePadding(top = 40.dpToPx())
                    addView(header)
                    addView(cardList)
                })
            })
        }
    }

    /** Drawn as soon as it's made, so a day swiped to (built beside the one showing) is already there */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render()
    }

    /** Drawn again on coming back only if the stats changed meanwhile (on a stat's page, say) */
    override fun onResume() {
        super.onResume()
        if (store.lastSaved() != renderedAt) render()
    }

    /** When the stats it was drawn from were saved */
    private var renderedAt = -1L

    override fun onPause() {
        saveNote()
        stopVoicePlayback()
        super.onPause()
    }

    override fun onDestroyView() {
        noteInput?.hideKeyboard()
        noteInput = null
        super.onDestroyView()
    }

    private fun saveNote() {
        val text = noteInput?.text?.toString() ?: return
        if (text.trim() == noteShown?.trim().orEmpty()) return
        store.setNote(date, text)
        noteShown = text
    }

    private fun render() {
        renderedAt = store.lastSaved()
        val data = store.load()
        val stats = requireContext().allStats(data)
        val day = data.days[date.toString()]
        val today = LocalDate.now()
        title.text = when (date) {
            today -> getString(R.string.today)
            today.minusDays(1) -> getString(R.string.yesterday)
            else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEEE d MMMM" else "EEE d MMM yyyy", Locale.getDefault()))
        }
        cardList.removeAllViews()
        topCard(stats, day)
        statsCard(stats, day)
        screenCard(day)
        sleepCard()
        placesCard()
        moneyCard()
        musicCard()
        moreCard()
    }

    // --- What was written down, and can be changed ---

    /**
     * The picked stat's faces (Mood's at first), the one that day standing out, with their labels;
     * tapping one sets it, tapping it again clears it. The day's note under them.
     */
    private fun topCard(stats: List<StatInfo>, day: MoodStore.Day?) {
        val context = requireContext()
        val stat = stats.firstOrNull { it.id == editing } ?: stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
        val card = Card(stat.name, icon = StatIcons.forStat(stat), iconColor = stat.color)
        // Back to Mood from another stat
        if (stat.id != MoodStore.MOOD) {
            card.subtitle(getString(R.string.day_back_to_mood))
            card.headerEnd.getChildAt(0)?.setOnClickListener {
                editing = MoodStore.MOOD
                saveNote()
                render()
            }
        }
        card.scene.visibility = View.GONE
        val chosen = day?.face(stat.id)
        val faces = stat.faces
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            faces.forEach { face ->
                addView(PixelFaceView(context).apply {
                    mood = face
                    // Counted stats (medicines, coffee and the like) in their own shades
                    if (stat.medicine || stat.kind == StatKind.SLEEP) color = stat.faceColor(context, face)
                    alpha = if (chosen == null || chosen == face) 1f else 0.3f
                    contentDescription = faceName(stat, face)
                    setOnClickListener {
                        store.setFace(date, stat.id, if (chosen == face) null else face)
                        saveNote()
                        render()
                    }
                }, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f))
            }
        })
        if (stat.labels.any { it.isNotEmpty() } || stat.id != MoodStore.MOOD) {
            val labels = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            bindFaceLabels(labels, faces.map { faceName(stat, it) }, faces.map { if (chosen == null || chosen == it) 1f else 0.45f })
            body.addView(labels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dpToPx() })
        }
        card.addBody(body)
        val note = day?.note.orEmpty()
        noteShown = note
        noteInput = EditText(context).apply {
            setText(note)
            hint = getString(R.string.day_note_hint)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(faint(110))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            gravity = Gravity.TOP
            background = GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(faint(18))
            }
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            setOnFocusChangeListener { _, focused -> if (!focused) saveNote() }
            // Holding it while not typing records a voice note, written into the note
            setOnLongClickListener {
                if (hasFocus() && androidx.core.view.ViewCompat.getRootWindowInsets(this)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) return@setOnLongClickListener false
                recordVoiceNote(date, onText = { text -> addToNote(text) }, onSaved = { if (isAdded) render() })
                true
            }
        }
        card.root.addView(noteInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 14.dpToPx()
        })
        voiceRows(card, date, onText = { text -> addToNote(text) }) { if (isAdded) render() }?.let { rows ->
            card.root.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 6.dpToPx()
            })
        }
    }

    /** A voice note's words, added to the end of the note and saved */
    private fun addToNote(text: String) {
        val now = (noteInput?.text?.toString() ?: store.load().days[date.toString()]?.note).orEmpty().trim()
        val note = if (now.isEmpty()) text else now + "\n" + text
        store.setNote(date, note)
        noteShown = note
        noteInput?.setText(note)
    }

    /** Every stat with its face that day; tapping one edits it in the top card, holding one opens its page */
    private fun statsCard(stats: List<StatInfo>, day: MoodStore.Day?) {
        val context = requireContext()
        val card = Card(getString(R.string.day_stats))
        card.subtitle(getString(R.string.day_tap_to_change))
        card.scene.visibility = View.GONE
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        stats.forEach { stat ->
            val face = day?.face(stat.id)
            // Screen time is counted, not picked
            val editable = stat.id != MoodStore.USAGE
            val value = when {
                stat.id == MoodStore.USAGE -> day?.totalUsageMs?.let { duration(it) }
                face == null -> null
                else -> faceName(stat, face)
            }
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 9.dpToPx(), 0, 9.dpToPx())
                if (stat.id == editing) background = GradientDrawable().apply {
                    cornerRadius = 10.dpToPx().toFloat()
                    setColor(faint(22))
                } else if (editable) setBackgroundResource(android.R.drawable.list_selector_background)
                setPadding(8.dpToPx(), 9.dpToPx(), 8.dpToPx(), 9.dpToPx())
                addView(View(context).apply { background = StatIcons.drawable(StatIcons.forStat(stat), stat.color, 16.dpToPx()) },
                    LinearLayout.LayoutParams(16.dpToPx(), 16.dpToPx()).apply { marginEnd = 12.dpToPx() })
                addView(card.textView(15f, 235).apply { text = stat.name }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (face != null && stat.id != MoodStore.USAGE && !stat.isBlank(face)) addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(stat.faceColor(context, face))
                    }
                }, LinearLayout.LayoutParams(9.dpToPx(), 9.dpToPx()).apply { marginEnd = 8.dpToPx() })
                addView(card.textView(14f, if (value == null) 90 else 190).apply { text = value ?: "–" })
                if (editable) setOnClickListener {
                    editing = stat.id
                    saveNote()
                    render()
                    scroll.post { scroll.smoothScrollTo(0, 0) }
                }
                setOnLongClickListener {
                    openStat(stat.id)
                    true
                }
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
    }

    private fun faceName(stat: StatInfo, face: Int): String {
        val place = stat.faces.indexOf(face)
        stat.labels.getOrNull(place)?.takeIf { it.isNotEmpty() }?.let { return it }
        return when {
            stat.id == MoodStore.MOOD -> resources.getStringArray(R.array.mood_names)[face]
            stat.id == MoodStore.USAGE -> resources.getStringArray(R.array.usage_faces)[face]
            stat.isBlank(face) -> getString(R.string.none_taken)
            else -> resources.getStringArray(R.array.mood_names)[face]
        }
    }

    // --- What filled itself in ---

    /** Screen time on each device, and the phone's top app */
    private fun screenCard(day: MoodStore.Day?) {
        val total = day?.totalUsageMs ?: return
        val card = Card(getString(R.string.screen_time), icon = StatIcons.SCREEN, iconColor = faint(200))
        card.headline(duration(total), day.topApp?.let { app -> getString(R.string.day_top_app, app, duration(day.topAppMs ?: 0)) } ?: "")
        val rows = listOfNotNull(
            day.usageMs?.let { Triple(StatIcons.PHONE, getString(R.string.device_phone), it) },
            day.deviceMs["mac"]?.takeIf { it > 0 }?.let { Triple(StatIcons.LAPTOP, getString(R.string.device_mac), it) },
            day.deviceMs["tv"]?.takeIf { it > 0 }?.let { Triple(StatIcons.TV, getString(R.string.device_tv), it) },
        )
        rows(card, rows.map { (icon, name, ms) -> Triple(icon, name, duration(ms)) })
    }

    /** The night that ended this morning */
    private fun sleepCard() {
        val night = Sleep.nights(requireContext())[date] ?: return
        val card = Card(getString(R.string.day_sleep), icon = StatIcons.SLEEP, iconColor = faint(200))
        card.headline(duration(night.asleepMs), getString(R.string.sleep_span, clock(night.bedtime.toLocalTime()), clock(night.wake.toLocalTime())))
        card.scene.visibility = View.GONE
    }

    /** Where the day went: a bar from midnight to midnight in each place's colour, and the time at each */
    private fun placesCard() {
        val context = requireContext()
        val day = Social.days(context)[date] ?: return
        val homes = Social.homes(context)
        val work = Social.work(context)
        fun name(place: Int) = if (Social.isNamed(place)) app.olauncher.helper.Places.place(context, place)?.name ?: getString(R.string.social_out_place) else when (place) {
            Social.OUT -> getString(R.string.social_out_place)
            Social.UNKNOWN -> getString(R.string.social_unknown_place)
            Social.WORK -> work?.name ?: getString(R.string.work)
            else -> homes.getOrNull(place)?.name ?: getString(R.string.social_home_n, place + 1)
        }
        fun color(place: Int) = if (Social.isNamed(place)) app.olauncher.helper.Places.place(context, place)?.color ?: 0xFF4FC3F7.toInt() else when (place) {
            Social.OUT -> 0xFF4FC3F7.toInt()
            Social.UNKNOWN -> faint(50)
            Social.WORK -> 0xFF7986CB.toInt()
            1 -> 0xFFFFB74D.toInt()
            else -> 0xFF81C784.toInt()
        }
        val card = Card(getString(R.string.day_places), icon = StatIcons.SOCIAL, iconColor = faint(200))
        val (start, end) = Social.dayBounds(context, date)
        val length = (end - start).toFloat()
        val stretches = day.stretches
        card.describe = { key ->
            val s = stretches[key as Int]
            listOf(name(s.place), clock(s.startTime.toLocalTime()) + " – " + clock(s.endTime.toLocalTime()), duration(s.ms)).joinToString("\n")
        }
        card.draw { sc ->
            val h = sc.dp(22f)
            sc.rect(0f, 0f, sc.width, h, faint(18), sc.dp(6f))
            stretches.forEachIndexed { i, s ->
                val x1 = (s.start - start) / length * sc.width
                val x2 = (s.end - start) / length * sc.width
                sc.rect(x1, 0f, maxOf(x2, x1 + sc.dp(2f)), h, color(s.place), sc.dp(4f))
                sc.target(i, RectF(x1, 0f, maxOf(x2, x1 + sc.dp(8f)), h + sc.dp(6f)))
            }
            listOf(0, 6, 12, 18).map { h -> h to clock(java.time.LocalTime.of(h, 0)).replace(":00", "") }.forEach { (hour, label) ->
                sc.text(label, hour / 24f * sc.width + sc.dp(2f), h + sc.dp(16f), faint(140), sc.sp(11f), android.graphics.Paint.Align.LEFT)
            }
            sc.height = h + sc.dp(22f)
        }
        val totals = stretches.groupBy { it.place }.mapValues { (_, list) -> list.sumOf { it.ms } }.entries.sortedByDescending { it.value }
        totals.forEach { (place, ms) -> card.keyItem(color(place), "${name(place)} · ${duration(ms)}") }
    }

    /** What was bought, with what counts towards Money spent */
    private fun moneyCard() {
        val purchases = Spending.purchases(requireContext()).filter { it.date == date }
        if (purchases.isEmpty()) return
        val card = Card(getString(R.string.day_money), icon = StatIcons.MONEY, iconColor = faint(200))
        val spent = purchases.filter { !it.essential }.sumOf { it.cents }
        val essentials = purchases.filter { it.essential }.sumOf { it.cents }
        card.headline(money(spent), listOfNotNull(getString(R.string.spent).lowercase(Locale.getDefault()),
            essentials.takeIf { it > 0 }?.let { getString(R.string.plus_essentials, money(it)) }).joinToString(" · "))
        rows(card, purchases.sortedBy { it.time }.map { p ->
            val time = clock(Instant.ofEpochMilli(p.time).atZone(ZoneId.systemDefault()).toLocalTime())
            Triple(null, p.merchant + (if (p.essential) " · " + getString(R.string.essential) else ""), money(p.cents) + "  " + time)
        })
    }

    /** How long, and who */
    private fun musicCard() {
        val day = Music.days(requireContext())[date] ?: return
        val card = Card(getString(R.string.day_music), icon = StatIcons.MUSIC, iconColor = faint(200))
        card.headline(duration(day.ms), day.artists.maxByOrNull { it.value }?.key?.let { getString(R.string.day_mostly, it) } ?: "")
        val tracks = day.plays.entries.sortedByDescending { it.value }.take(3).map { (key, n) ->
            val (title, artist) = Music.splitTrack(key)
            Triple(null, listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" · "), resources.getQuantityString(R.plurals.music_plays, n, n))
        }
        val artists = day.artists.entries.sortedByDescending { it.value }.take(3).map { (a, ms) -> Triple(null, a, duration(ms)) }
        rows(card, tracks.ifEmpty { artists })
    }

    /** Steps, the Mac's and TV's first and last use, and vibecoding */
    private fun moreCard() {
        val context = requireContext()
        val rows = listOfNotNull(
            Steps.days(context)[date]?.let { Triple(StatIcons.STEPS, getString(R.string.steps_title), String.format(Locale.getDefault(), "%,d", it)) },
            Vibecoding.days(context)[date]?.takeIf { it.minutes > 0 }?.let { v ->
                Triple(StatIcons.CODE, getString(R.string.day_vibecoding), duration(v.minutes * 60_000L) + " · " + resources.getQuantityString(R.plurals.day_prompts, v.prompts, v.prompts))
            },
            DeviceTime.days(context, DeviceTime.Device.MAC)[date]?.takeIf { it.first != null }?.let { d ->
                Triple(StatIcons.LAPTOP, getString(R.string.day_mac_from), listOfNotNull(d.first, d.last).joinToString(" – "))
            },
        )
        if (rows.isEmpty()) return
        val card = Card(getString(R.string.day_more))
        rows(card, rows)
    }

    /** Rows of an icon (or none), a name and a value on the right, in place of a drawing */
    private fun rows(card: Card, rows: List<Triple<String?, String, String>>) {
        if (rows.isEmpty()) {
            card.scene.visibility = View.GONE
            return
        }
        val context = requireContext()
        card.scene.visibility = View.GONE
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { (icon, name, value) ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
                if (icon != null) addView(View(context).apply { background = StatIcons.drawable(icon, faint(190), 15.dpToPx()) },
                    LinearLayout.LayoutParams(15.dpToPx(), 15.dpToPx()).apply { marginEnd = 12.dpToPx() })
                addView(card.textView(14f, 230).apply {
                    text = name
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(card.textView(13f, 160).apply {
                    text = value
                    updatePadding(left = 10.dpToPx())
                })
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
    }
}
