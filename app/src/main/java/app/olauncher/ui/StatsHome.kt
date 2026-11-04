package app.olauncher.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.StatInfo
import app.olauncher.helper.dpToPx
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.setStatDot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The stats page's Home tab: a pixel pet, quick figures, streaks, notes and a run of small cards that
 * each look at the data a different way. Everything for it lives here and in [PixelPetView], with its
 * strings in strings_stats_home.xml; [MoodFragment] only adds the tab and hands over the data (search
 * for StatsHome), so it can all be taken out in one go.
 *
 * It's built once per load of the data, so switching tabs back to it costs nothing.
 */
class StatsHome(private val container: LinearLayout, private val openStat: (String) -> Unit) {

    private val context = container.context
    private val res = context.resources
    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)

    private var data = MoodStore.Data()
    private var stats = emptyList<StatInfo>()
    private var shown: MoodStore.Data? = null
    private var today = LocalDate.now()

    /** Days with anything saved, oldest first */
    private var allDates = emptyList<LocalDate>()

    /** Days with a mood, oldest first */
    private var moodDates = emptyList<LocalDate>()

    /** Rebuilds the cards, unless they're already showing [data]. */
    fun show(data: MoodStore.Data, stats: List<StatInfo>) {
        if (data === shown && today == LocalDate.now()) return
        shown = data
        this.data = data
        this.stats = stats
        today = LocalDate.now()
        allDates = data.days.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        moodDates = allDates.filter { moodOn(it) != null }
        container.removeAllViews()
        petCard()
        quickCard()
        forecastCard()
        streaksCard()
        notesCard()
        recipeCard()
        yearRingCard()
        nextDayCard()
        beforeAfterCard()
        webCard()
        screenCostCard()
        weatherCard()
        bounceCard()
        weekShapeCard()
        wordsCard()
        galaxyCard()
        milestonesCard()
    }

    // --- Values ---

    private fun dayOf(date: LocalDate) = data.days[date.toString()]

    private val mood get() = stats.first { it.id == MoodStore.MOOD }

    /** 1 (awful) to 5 (great) */
    private fun moodOn(date: LocalDate): Int? = dayOf(date)?.mood?.let { MoodStore.score(it, data.moodInverted) }

    /** Any stat's day, scored 1 (its sad face) to 5 (happy); Screen time by its usage faces */
    private fun scoreOn(stat: StatInfo, date: LocalDate): Int? = dayOf(date)?.face(stat.id)?.let { MoodStore.score(it, stat.inverted) }

    /** Whether a medicine counts as taken for [date], the day before's when it affects the next day and [shift]. */
    private fun takenOn(med: StatInfo, date: LocalDate, shift: Boolean = true): Boolean? =
        dayOf(if (shift && med.nextDay) date.minusDays(1) else date)?.face(med.id)?.let { !med.isBlank(it) }

    /** Higher is better: a score, taken (1) or not (0), or less screen time */
    private fun goodness(stat: StatInfo, date: LocalDate): Float? = when {
        stat.id == MoodStore.USAGE -> dayOf(date)?.usageMs?.let { -it / HOUR_MS }
        stat.medicine -> takenOn(stat, date)?.let { if (it) 1f else 0f }
        else -> scoreOn(stat, date)?.toFloat()
    }

    private fun moodColor(score: Int) = mood.faceColor(context, MoodStore.faceForScore(score.coerceIn(1, 5), mood.inverted))

    private fun moodWord(score: Int) = res.getStringArray(R.array.home_mood_words)[score.coerceIn(1, 5) - 1]

    private fun faceLabel(stat: StatInfo, face: Int): String {
        val place = stat.faces.indexOf(face)
        stat.labels.getOrNull(place)?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (stat.medicine) res.getString(if (place <= 0) R.string.none_taken else R.string.taken)
        else moodWord(MoodStore.score(face, stat.inverted))
    }

    private fun dateText(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

    private fun fullDateText(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()))

    private fun decimal(value: Float) = String.format(Locale.getDefault(), "%.1f", value)

    private fun matches(stat: StatInfo, vararg words: String) = words.any { stat.name.contains(it, ignoreCase = true) }

    /** Days in a row ending on [from] (or the day before, if [from] doesn't count yet) where [counts]. */
    private fun runEnding(from: LocalDate, counts: (LocalDate) -> Boolean): Int {
        var day = if (counts(from)) from else from.minusDays(1)
        var run = 0
        while (counts(day)) {
            run++
            day = day.minusDays(1)
        }
        return run
    }

    /** The longest run of days in a row, among [days] (sorted), where [counts]. */
    private fun bestRun(days: List<LocalDate>, counts: (LocalDate) -> Boolean): Int {
        var best = 0
        var run = 0
        var last: LocalDate? = null
        days.forEach { day ->
            if (!counts(day)) {
                run = 0
            } else {
                run = if (last != null && ChronoUnit.DAYS.between(last, day) == 1L && run > 0) run + 1 else 1
                best = maxOf(best, run)
            }
            last = day
        }
        return best
    }

    // --- Cards ---

    /** A card with a title, something on its right, the views added, and the tapped point's details last. */
    private inner class Card(title: String, subtitle: String? = null) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_rect_shade_color)
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 14.dpToPx())
        }
        val details = text(13f, 255).apply {
            gravity = Gravity.CENTER
            setLineSpacing(4.dpToPx().toFloat(), 1f)
            isVisible = false
        }

        init {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(15f, 255).apply { text = title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                subtitle?.let { addView(text(13f, 150).apply { text = it }) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dpToPx()
            })
            root.addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10.dpToPx()
            })
            container.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 0)
            })
        }

        fun add(view: View, topMargin: Int = 0) {
            root.addView(view, root.childCount - 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = topMargin
            })
        }

        fun showDetails(text: CharSequence?) {
            details.isVisible = !text.isNullOrEmpty()
            details.text = text
        }

        /** A drawing; tapping its targets shows what [describe] says about them. */
        fun scene(describe: (Any) -> CharSequence? = { null }, build: (StatSceneView.Scene) -> Unit): StatSceneView {
            val scene = StatSceneView(context)
            scene.onPicked = { showDetails(it?.let(describe)) }
            scene.setScene(build = build)
            add(scene)
            return scene
        }

        fun note(text: String) = add(text(13f, 150).apply {
            this.text = text
            gravity = Gravity.CENTER
            updatePadding(top = 12.dpToPx(), bottom = 12.dpToPx())
        })

        fun key(vararg items: Pair<Int, String>) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { (color, label) ->
                row.addView(text(13f, 150).apply {
                    text = label
                    gravity = Gravity.CENTER_VERTICAL
                    val size = 11.dpToPx()
                    setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setSize(size, size)
                        setColor(color)
                    }, null, null, null)
                    compoundDrawablePadding = 6.dpToPx()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 14.dpToPx()
                })
            }
            add(row, 12.dpToPx())
        }
    }

    private fun text(size: Float, alpha: Int) = TextView(context, null, 0, R.style.TextSmall).apply {
        textSize = size
        setTextColor(ColorUtils.setAlphaComponent(ink, alpha))
    }

    private fun chip(label: String, color: Int?, on: Boolean, onTap: () -> Unit) = text(13f, 255).apply {
        text = label
        setBackgroundResource(R.drawable.bg_stat_chip)
        setPadding(10.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
        setStatDot(color)
        alpha = if (on) 1f else 0.4f
        if (on) setTypeface(null, Typeface.BOLD)
        setOnClickListener { onTap() }
    }

    // --- Pet ---

    private class PetMood(val look: PixelPetView.Look, val caption: String, val reasons: String)

    /**
     * The pet follows the last day with a mood before today (today's, if that's all there is). Its body
     * takes that mood's colour, its mouth the mood, and the other stats that day add the rest: heart
     * eyes for high libido, sleepy eyes for low energy or sleep, a sweat drop for anxiety, sparkles for
     * creativity, a mug for coffee, a headband after exercise, a halo after meditating, a phone after
     * a long screen day. Three bad days in a row bring a rain cloud, three good ones the sun.
     */
    private fun petMood(): PetMood {
        val name = res.getString(R.string.pet_name)
        val day = generateSequence(today.minusDays(1)) { it.minusDays(1) }.take(14).firstOrNull { moodOn(it) != null }
            ?: today.takeIf { moodOn(it) != null }
            ?: return PetMood(
                PixelPetView.Look(faint(140), PixelPetView.Eyes.SLEEPY, PixelPetView.Mouth.FLAT, setOf(PixelPetView.Prop.ZZZ)),
                res.getString(R.string.pet_waiting, name), ""
            )
        val score = moodOn(day)!!
        val props = mutableSetOf<PixelPetView.Prop>()
        val traits = mutableListOf<String>()
        val reasons = mutableListOf(res.getString(R.string.stat_label, mood.name, moodWord(score)))
        var love = false
        var tired = false
        var anxious = false
        var creative = false

        stats.filter { !it.isBuiltIn }.forEach { stat ->
            if (stat.medicine) {
                if (takenOn(stat, day, shift = false) != true) return@forEach
                val prop = when {
                    matches(stat, "coffee", "caffeine", "espresso", "latte") -> PixelPetView.Prop.MUG to R.string.pet_coffee
                    matches(stat, "exercis", "gym", "workout", "run", "walk", "sport", "swim", "cycl", "lift") -> PixelPetView.Prop.HEADBAND to R.string.pet_exercise
                    matches(stat, "meditat", "mindful", "breath", "yoga") -> PixelPetView.Prop.HALO to R.string.pet_meditation
                    else -> null
                } ?: return@forEach
                props.add(prop.first)
                traits.add(res.getString(prop.second))
                reasons.add(res.getString(R.string.stat_label, stat.name, faceLabel(stat, dayOf(day)!!.face(stat.id)!!)))
            } else {
                val statScore = scoreOn(stat, day) ?: return@forEach
                val matched = when {
                    matches(stat, "libido", "sex", "horny", "desire", "lust") -> (statScore >= 4).also { if (it) love = true }
                    matches(stat, "anxi", "stress", "worr", "panic", "nerv") -> (statScore <= 2).also { if (it) anxious = true }
                    matches(stat, "energy", "tired", "fatigue", "sleep", "rest") -> (statScore <= 2).also { if (it) tired = true }
                    matches(stat, "creativ", "inspir", "idea") -> (statScore >= 4).also { if (it) creative = true }
                    matches(stat, "social", "friend", "connect", "lonel") -> (statScore >= 4).also {
                        if (it) {
                            props.add(PixelPetView.Prop.BLUSH)
                            traits.add(res.getString(R.string.pet_social))
                        }
                    }
                    else -> false
                }
                if (matched) reasons.add(res.getString(R.string.stat_label, stat.name, faceLabel(stat, dayOf(day)!!.face(stat.id)!!)))
            }
        }
        if (love) {
            props.add(PixelPetView.Prop.HEARTS)
            traits.add(res.getString(R.string.pet_love))
        }
        if (anxious) {
            props.add(PixelPetView.Prop.SWEAT)
            traits.add(res.getString(R.string.pet_anxious))
        }
        if (tired) {
            props.add(PixelPetView.Prop.ZZZ)
            traits.add(res.getString(R.string.pet_tired))
        }
        if (creative) {
            props.add(PixelPetView.Prop.SPARKLES)
            traits.add(res.getString(R.string.pet_creative))
        }
        dayOf(day)?.usageMs?.takeIf { it >= LONG_SCREEN_MS }?.let {
            props.add(PixelPetView.Prop.PHONE)
            traits.add(res.getString(R.string.pet_phone))
            reasons.add(res.getString(R.string.stat_label, res.getString(R.string.screen_time), context.formattedTimeSpent(it)))
        }
        val lastThree = (0L..2L).map { moodOn(day.minusDays(it)) }
        if (lastThree.all { it != null && it <= 2 }) {
            props.add(PixelPetView.Prop.RAIN_CLOUD)
            traits.add(res.getString(R.string.pet_rain))
        } else if (lastThree.all { it != null && it >= 4 }) {
            props.add(PixelPetView.Prop.SUN)
            traits.add(res.getString(R.string.pet_sun))
        }
        if (score <= 2) props.add(PixelPetView.Prop.SAD_BROWS)
        if (score == 1) props.add(PixelPetView.Prop.TEAR)
        if (score >= 4 || love) props.add(PixelPetView.Prop.BLUSH)

        val eyes = when {
            love -> PixelPetView.Eyes.HEARTS
            tired -> PixelPetView.Eyes.SLEEPY
            anxious -> PixelPetView.Eyes.WORRIED
            creative -> PixelPetView.Eyes.STARS
            score == 5 -> PixelPetView.Eyes.HAPPY
            else -> PixelPetView.Eyes.NORMAL
        }
        val look = PixelPetView.Look(moodColor(score), eyes, PixelPetView.Mouth.entries[score - 1], props)

        val feeling = res.getStringArray(R.array.pet_feelings)[score - 1]
        val caption = if (traits.isEmpty()) res.getString(R.string.pet_caption, name, feeling)
        else res.getString(
            if (score <= 2) R.string.pet_caption_but else R.string.pet_caption_and, name, feeling,
            if (traits.size == 1) traits[0] else traits.dropLast(1).joinToString(", ") + " " + res.getString(R.string.home_and) + " " + traits.last()
        )
        val from = if (day == today.minusDays(1)) res.getString(R.string.home_yesterday) else dateText(day)
        return PetMood(look, caption, res.getString(R.string.pet_from, from, reasons.joinToString(" · ")))
    }

    private fun petCard() {
        val petMood = petMood()
        val card = Card(res.getString(R.string.pet_name))
        val pet = PixelPetView(context).apply { look = petMood.look }
        card.add(pet)
        card.add(text(16f, 255).apply {
            text = petMood.caption
            gravity = Gravity.CENTER
        }, 10.dpToPx())
        card.add(text(12f, 140).apply {
            text = petMood.reasons
            gravity = Gravity.CENTER
        }, 6.dpToPx())
        // A hop when tapped
        pet.setOnClickListener {
            pet.animate().cancel()
            pet.animate().translationY(-16f * res.displayMetrics.density).setDuration(140).withEndAction {
                pet.animate().translationY(0f).setDuration(420).setInterpolator(BounceInterpolator()).start()
            }.start()
        }
    }

    // --- Quick figures ---

    private fun quickCard() {
        val card = Card(res.getString(R.string.home_at_a_glance))
        val up = moodColor(5)
        val down = moodColor(1)

        val weekStart = today.with(DayOfWeek.MONDAY)
        val thisWeek = (0L..ChronoUnit.DAYS.between(weekStart, today)).mapNotNull { moodOn(weekStart.plusDays(it)) }
        val lastWeek = (1L..7L).mapNotNull { moodOn(weekStart.minusDays(it)) }
        val weekAverage = thisWeek.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val change = if (weekAverage != null && lastWeek.isNotEmpty()) weekAverage - lastWeek.average().toFloat() else null

        val streak = runEnding(today) { moodOn(it) != null }

        val usageToday = dayOf(today)?.usageMs
        val usual = (1L..30L).mapNotNull { dayOf(today.minusDays(it))?.usageMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()

        val month = YearMonth.from(today)
        val monthScores = (1..today.dayOfMonth).mapNotNull { moodOn(month.atDay(it)) }

        val tiles = listOf(
            Tile(res.getString(R.string.home_this_week), weekAverage?.let(::decimal) ?: "–",
                change?.let { res.getString(if (it >= 0) R.string.home_up_vs_last_week else R.string.home_down_vs_last_week, decimal(abs(it))) }
                    ?: res.getString(R.string.home_mood_average),
                change?.let { if (it >= 0.05f) up else if (it <= -0.05f) down else null }) { openStat(MoodStore.MOOD) },
            Tile(res.getString(R.string.home_streak), streak.toString(), res.getString(R.string.home_days_in_a_row), null) {},
            Tile(res.getString(R.string.screen_time), usageToday?.let { context.formattedTimeSpent(it) } ?: "–",
                usual?.let { res.getString(R.string.home_usually, context.formattedTimeSpent(it)) } ?: res.getString(R.string.today),
                if (usageToday != null && usual != null) (if (usageToday > usual) down else up) else null) { openStat(MoodStore.USAGE) },
            Tile(res.getString(R.string.home_good_days), monthScores.count { it >= 4 }.toString(),
                res.getString(R.string.home_of_logged_this_month, monthScores.size), null) { openStat(MoodStore.MOOD) },
        )
        tiles.chunked(2).forEachIndexed { i, pair ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { j, tile ->
                row.addView(tileView(tile), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (j == 1) marginStart = 10.dpToPx()
                })
            }
            card.add(row, if (i == 0) 0 else 10.dpToPx())
        }
    }

    private class Tile(val label: String, val value: String, val note: String, val noteColor: Int?, val onTap: () -> Unit)

    private fun tileView(tile: Tile) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.rounded_box)
        setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
        addView(text(12f, 150).apply { text = tile.label })
        addView(TextView(context, null, 0, R.style.TextLarge).apply {
            text = tile.value
            textSize = 26f
            setTextColor(ink)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(text(12f, 150).apply {
            text = tile.note
            tile.noteColor?.let { setTextColor(it) }
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener { tile.onTap() }
    }

    // --- Forecast ---

    private enum class Sky { SUN, SUN_CLOUD, CLOUD, RAIN, STORM, SNOW }

    private fun skyFor(score: Int) = when (score) {
        5 -> Sky.SUN
        4 -> Sky.SUN_CLOUD
        3 -> Sky.CLOUD
        2 -> Sky.RAIN
        else -> Sky.STORM
    }

    /** A little weather icon about [size] across, centred on [x], [y]. */
    private fun drawSky(s: StatSceneView.Scene, sky: Sky, x: Float, y: Float, size: Float, alpha: Int = 255) {
        fun a(color: Int) = ColorUtils.setAlphaComponent(color, alpha * android.graphics.Color.alpha(color) / 255)
        fun sun(cx: Float, cy: Float, r: Float) {
            s.circle(cx, cy, r, a(SUN_GOLD))
            for (i in 0 until 8) {
                val angle = i * Math.PI / 4
                s.line(cx + (r * 1.35f) * cos(angle).toFloat(), cy + (r * 1.35f) * sin(angle).toFloat(),
                    cx + (r * 1.75f) * cos(angle).toFloat(), cy + (r * 1.75f) * sin(angle).toFloat(), a(SUN_GOLD), s.dp(2f))
            }
        }
        fun cloud(cx: Float, cy: Float, w: Float, color: Int) {
            s.circle(cx - w * 0.22f, cy + w * 0.04f, w * 0.22f, a(color))
            s.circle(cx + w * 0.06f, cy - w * 0.08f, w * 0.3f, a(color))
            s.circle(cx + w * 0.3f, cy + w * 0.06f, w * 0.18f, a(color))
            s.rect(cx - w * 0.44f, cy + w * 0.02f, cx + w * 0.48f, cy + w * 0.24f, a(color), w * 0.11f)
        }
        when (sky) {
            Sky.SUN -> sun(x, y, size * 0.26f)
            Sky.SUN_CLOUD -> {
                sun(x - size * 0.16f, y - size * 0.12f, size * 0.2f)
                cloud(x + size * 0.08f, y + size * 0.1f, size * 0.7f, CLOUD_LIGHT)
            }
            Sky.CLOUD -> cloud(x, y, size * 0.9f, CLOUD_LIGHT)
            Sky.RAIN, Sky.SNOW -> {
                cloud(x, y - size * 0.1f, size * 0.85f, CLOUD_DARK)
                for (i in -1..1) {
                    val dx = x + i * size * 0.24f
                    if (sky == Sky.RAIN) s.line(dx, y + size * 0.22f, dx - size * 0.06f, y + size * 0.4f, a(RAIN_BLUE), s.dp(2f))
                    else s.circle(dx, y + size * 0.32f, s.dp(2.2f), a(android.graphics.Color.WHITE))
                }
            }
            Sky.STORM -> {
                cloud(x, y - size * 0.12f, size * 0.85f, CLOUD_STORM)
                val bolt = Path().apply {
                    moveTo(x + size * 0.04f, y + size * 0.08f)
                    lineTo(x - size * 0.1f, y + size * 0.3f)
                    lineTo(x + size * 0.02f, y + size * 0.3f)
                    lineTo(x - size * 0.08f, y + size * 0.5f)
                }
                s.path(bolt, a(SUN_GOLD), s.dp(2.5f))
            }
        }
    }

    /**
     * The last week's moods as weather, and a guess at tomorrow's: half what that weekday usually is,
     * half how the last three days went.
     */
    private fun forecastCard() {
        val card = Card(res.getString(R.string.home_forecast), res.getString(R.string.home_forecast_sub))
        val days = (5L downTo 0L).map { today.minusDays(it) }
        val tomorrow = today.plusDays(1)
        val sameWeekday = moodDates.filter { it.dayOfWeek == tomorrow.dayOfWeek }.mapNotNull(::moodOn)
        val recent = moodDates.filter { it <= today }.takeLast(3).mapNotNull(::moodOn)
        val guess = when {
            sameWeekday.isNotEmpty() && recent.isNotEmpty() -> (sameWeekday.average() + recent.average()) / 2
            sameWeekday.isNotEmpty() -> sameWeekday.average()
            recent.isNotEmpty() -> recent.average()
            else -> null
        }
        card.scene({ key ->
            if (key == tomorrow) guess?.let {
                res.getString(R.string.home_forecast_tomorrow, moodWord(it.roundToInt()),
                    tomorrow.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    if (sameWeekday.isEmpty()) "–" else decimal(sameWeekday.average().toFloat()),
                    if (recent.isEmpty()) "–" else decimal(recent.average().toFloat()))
            }
            else (key as LocalDate).let { date -> "${dateText(date)} · ${moodOn(date)?.let(::moodWord) ?: "–"}" }
        }) { s ->
            val column = s.width / 7
            val size = minOf(column * 0.9f, s.dp(40f))
            (days + tomorrow).forEachIndexed { i, date ->
                val x = i * column + column / 2
                val isTomorrow = date == tomorrow
                val score = if (isTomorrow) guess?.roundToInt() else moodOn(date)
                if (isTomorrow) s.outline(i * column + s.dp(2f), 0f, (i + 1) * column - s.dp(2f), s.dp(78f), faint(60), s.dp(10f), s.dp(1.5f))
                if (score != null) drawSky(s, skyFor(score), x, s.dp(28f), size, if (isTomorrow) 150 else 255)
                else s.line(x - s.dp(6f), s.dp(28f), x + s.dp(6f), s.dp(28f), faint(70), s.dp(2f))
                val label = when (date) {
                    today -> res.getString(R.string.home_today_short)
                    tomorrow -> res.getString(R.string.home_tomorrow_short)
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }
                s.text(label, x, s.dp(66f), if (date >= today) ink else faint(150), s.sp(12f), bold = date == today)
                s.target(date, RectF(i * column, 0f, (i + 1) * column, s.dp(80f)), RectF(i * column + s.dp(2f), 0f, (i + 1) * column - s.dp(2f), s.dp(78f)), s.dp(10f))
            }
            s.height = s.dp(80f)
        }
    }

    // --- Streaks ---

    private fun streaksCard() {
        val card = Card(res.getString(R.string.home_streaks))
        val logged = runEnding(today) { moodOn(it) != null }
        val bestLogged = bestRun(allDates) { moodOn(it) != null }
        card.scene { s ->
            val size = minOf(s.width, s.dp(190f))
            val cx = s.width / 2
            val cy = size / 2
            val radius = size / 2 - s.dp(10f)
            val segments = 30
            val sweep = 360f / segments
            for (i in 0 until segments) {
                val color = when {
                    i >= minOf(logged, segments) -> faint(28)
                    i >= minOf(logged, segments) - 7 -> ContextCompat.getColor(context, R.color.medicineLight)
                    else -> ContextCompat.getColor(context, R.color.medicineDark)
                }
                s.arc(cx, cy, radius, -90f + i * sweep + 1.5f, sweep - 3f, color, s.dp(14f))
            }
            s.text(logged.toString(), cx, cy + s.sp(14f), ink, s.sp(46f))
            s.text(res.getString(R.string.home_days_logged_in_a_row), cx, cy + s.sp(34f), faint(150), s.sp(12.5f))
            s.height = size
        }
        card.add(text(13f, 150).apply {
            text = res.getString(R.string.home_best_run, bestLogged)
            gravity = Gravity.CENTER
        }, 4.dpToPx())

        // Each medicine's run of days taken, and good moods in a row
        val rows = mutableListOf<Triple<StatInfo, Int, Int>>()
        rows.add(Triple(mood, runEnding(today) { (moodOn(it) ?: 0) >= 4 }, bestRun(moodDates) { (moodOn(it) ?: 0) >= 4 }))
        stats.filter { it.medicine }.forEach { med ->
            rows.add(Triple(med, runEnding(today) { takenOn(med, it, shift = false) == true },
                bestRun(allDates) { takenOn(med, it, shift = false) == true }))
        }
        rows.filter { it.third >= 2 }.forEach { (stat, now, best) ->
            card.add(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                addView(text(14f, 230).apply {
                    text = if (stat.id == MoodStore.MOOD) res.getString(R.string.home_good_moods) else stat.name
                    setStatDot(stat.color)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(16f, 255).apply { text = res.getQuantityString(R.plurals.day_count, now, now) })
                addView(text(12f, 130).apply {
                    text = res.getString(R.string.home_best, best)
                    updatePadding(left = 10.dpToPx())
                })
                setOnClickListener { openStat(stat.id) }
            }, if (stat.id == MoodStore.MOOD) 12.dpToPx() else 0)
        }
    }

    // --- Notes ---

    /**
     * What was written a year, six months or a month ago today, if anything, or a random day on asking;
     * then the latest notes, each opening up when tapped.
     */
    private fun notesCard() {
        val card = Card(res.getString(R.string.home_notes))
        val notes = allDates.filter { dayOf(it)?.note != null }.reversed()
        if (notes.isEmpty()) return card.note(res.getString(R.string.home_no_notes))

        val memory = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_box)
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
        }
        val memoryTitle = text(13f, 150)
        val memoryText = text(16f, 255).apply { setTypeface(typeface, Typeface.ITALIC) }
        memory.addView(memoryTitle)
        memory.addView(memoryText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dpToPx()
        })
        fun remember(date: LocalDate, title: String) {
            memory.isVisible = true
            memoryTitle.text = res.getString(R.string.home_memory_title, title, fullDateText(date))
            memoryTitle.setStatDot(moodOn(date)?.let(::moodColor))
            memoryText.text = res.getString(R.string.home_quote, dayOf(date)?.note.orEmpty())
        }
        val onThisDay = listOf(
            today.minusYears(1) to R.string.home_a_year_ago,
            today.minusMonths(6) to R.string.home_six_months_ago,
            today.minusMonths(1) to R.string.home_a_month_ago,
        ).firstOrNull { (date, _) -> dayOf(date)?.note != null }
        if (onThisDay != null) remember(onThisDay.first, res.getString(onThisDay.second)) else memory.isVisible = false
        card.add(memory)

        var lastRandom: LocalDate? = null
        card.add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(chip(res.getString(R.string.home_random_memory), null, true) {
                val pick = notes.filter { it != lastRandom }.randomOrNull() ?: return@chip
                lastRandom = pick
                remember(pick, res.getString(R.string.home_remember_this))
            })
        }, 10.dpToPx())

        notes.take(RECENT_NOTES).forEach { date ->
            card.add(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10.dpToPx(), 0, 0)
                addView(text(12.5f, 150).apply {
                    text = dateText(date)
                    setStatDot(moodOn(date)?.let(::moodColor))
                })
                val body = text(14.5f, 235).apply {
                    text = dayOf(date)?.note
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    updatePadding(left = 13.dpToPx(), top = 2.dpToPx())
                }
                addView(body)
                setOnClickListener {
                    val open = body.maxLines == 2
                    body.maxLines = if (open) Int.MAX_VALUE else 2
                    body.ellipsize = if (open) null else TextUtils.TruncateAt.END
                }
            }, if (date == notes.first()) 6.dpToPx() else 0)
        }
    }

    // --- Recipe for a good day ---

    /** What the best days had more of than the rest: medicines taken, good stats, short screen days. */
    private fun recipeCard() {
        var best = moodDates.filter { moodOn(it) == 5 }
        var threshold = 5
        if (best.size < MIN_BEST_DAYS) {
            best = moodDates.filter { (moodOn(it) ?: 0) >= 4 }
            threshold = 4
        }
        val card = Card(res.getString(R.string.home_recipe), res.getString(
            if (threshold == 5) R.string.home_recipe_great else R.string.home_recipe_good, best.size
        ))
        val rest = moodDates.filter { (moodOn(it) ?: 0) < threshold }
        if (best.size < MIN_BEST_DAYS || rest.size < MIN_BEST_DAYS) return card.note(res.getString(R.string.needs_more_days))

        class Ingredient(val stat: StatInfo, val name: String, val onBest: Float, val otherwise: Float)

        val ingredients = stats.filter { it.id != MoodStore.MOOD }.mapNotNull { stat ->
            val name: String
            val counts: (LocalDate) -> Boolean?
            when {
                stat.id == MoodStore.USAGE -> {
                    name = res.getString(R.string.home_screen_under, 2)
                    counts = { date -> dayOf(date)?.usageMs?.let { it < 2 * HOUR_MS } }
                }
                stat.medicine -> {
                    name = stat.name
                    counts = { takenOn(stat, it) }
                }
                else -> {
                    val happiest = stat.faces.maxBy { MoodStore.score(it, stat.inverted) }
                    name = res.getString(R.string.stat_label, stat.name, faceLabel(stat, happiest).lowercase())
                    counts = { date -> scoreOn(stat, date)?.let { it >= 4 } }
                }
            }
            val onBest = best.mapNotNull(counts)
            val other = rest.mapNotNull(counts)
            if (onBest.size < 3 || other.size < 3) return@mapNotNull null
            Ingredient(stat, name, onBest.count { it } / onBest.size.toFloat(), other.count { it } / other.size.toFloat())
        }.filter { it.onBest - it.otherwise >= 0.05f }.sortedByDescending { it.onBest - it.otherwise }.take(4)
        if (ingredients.isEmpty()) return card.note(res.getString(R.string.home_recipe_none))

        card.add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(PixelFaceView(context).apply {
                mood = MoodStore.faceForScore(threshold, data.moodInverted)
                color = moodColor(threshold)
            }, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
            addView(text(15f, 255).apply {
                text = res.getString(R.string.home_recipe_lead)
                updatePadding(left = 12.dpToPx())
            })
        })
        card.scene({ key ->
            val ingredient = ingredients[key as Int]
            res.getString(R.string.home_recipe_detail, ingredient.name, (ingredient.onBest * 100).roundToInt(), (ingredient.otherwise * 100).roundToInt())
        }) { s ->
            val rowHeight = s.dp(44f)
            ingredients.forEachIndexed { i, ingredient ->
                val y = s.dp(8f) + i * rowHeight + rowHeight / 2
                val radius = s.dp(13f)
                val cx = radius + s.dp(2f)
                s.arc(cx, y, radius, 0f, 360f, faint(35), s.dp(4f))
                s.arc(cx, y, radius, -90f, 360f * ingredient.onBest, ingredient.stat.color, s.dp(4f))
                s.text(ingredient.name, cx + radius + s.dp(12f), y + s.sp(5f), faint(235), s.sp(14f), Paint.Align.LEFT)
                s.text("${(ingredient.onBest * 100).roundToInt()}%", s.width - s.dp(62f), y + s.sp(6f), ink, s.sp(18f), Paint.Align.RIGHT)
                s.text(res.getString(R.string.home_vs, (ingredient.otherwise * 100).roundToInt()), s.width, y + s.sp(5f), faint(140), s.sp(12f), Paint.Align.RIGHT)
                val area = RectF(0f, y - rowHeight / 2, s.width, y + rowHeight / 2)
                s.target(i, area, area, s.dp(10f))
            }
            s.height = s.dp(8f) + ingredients.size * rowHeight
        }
    }

    // --- Year ring ---

    /** This year round a ring, a sliver per day in its mood's colour, with the most-taken medicine inside. */
    private fun yearRingCard() {
        val year = today.year
        val card = Card(res.getString(R.string.home_year_ring), year.toString())
        val first = LocalDate.of(year, 1, 1)
        val length = first.lengthOfYear()
        val med = stats.filter { it.medicine }.maxByOrNull { med -> (0 until length).count { takenOn(med, first.plusDays(it.toLong()), shift = false) == true } }
            ?.takeIf { med -> (0 until length).any { takenOn(med, first.plusDays(it.toLong()), shift = false) == true } }
        val scores = (0 until length).mapNotNull { moodOn(first.plusDays(it.toLong())) }
        val ring = card.scene { }
        fun draw(picked: LocalDate?) = ring.setScene(keepPicked = true) { s ->
            val size = minOf(s.width, s.dp(300f))
            val cx = s.width / 2
            val cy = size / 2
            val outer = size / 2 - s.dp(18f)
            val sweep = 360f / length
            for (d in 0 until length) {
                val date = first.plusDays(d.toLong())
                val start = -90f + d * sweep
                val color = when {
                    date > today -> faint(14)
                    else -> moodOn(date)?.let(::moodColor) ?: faint(34)
                }
                s.arc(cx, cy, outer - s.dp(16f), start, sweep * 0.8f, color, s.dp(32f))
                if (med != null && date <= today) {
                    val face = dayOf(date)?.face(med.id)
                    s.arc(cx, cy, outer - s.dp(42f), start, sweep * 0.8f,
                        if (face != null && !med.isBlank(face)) med.faceColor(context, face) else faint(26), s.dp(12f))
                }
            }
            // Today, and the tapped day
            listOfNotNull(today.takeIf { it.year == year }, picked).forEach { date ->
                val angle = Math.toRadians(-90.0 + (ChronoUnit.DAYS.between(first, date) + 0.4) * sweep)
                val inner = outer - s.dp(if (med != null) 50f else 34f)
                s.line(cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat(),
                    cx + (outer + s.dp(2f)) * cos(angle).toFloat(), cy + (outer + s.dp(2f)) * sin(angle).toFloat(), ink, s.dp(if (date == picked) 2.5f else 1.5f))
            }
            Month.values().forEach { month ->
                val middle = ChronoUnit.DAYS.between(first, LocalDate.of(year, month, 15))
                val angle = Math.toRadians(-90.0 + middle * sweep)
                s.text(month.getDisplayName(TextStyle.NARROW, Locale.getDefault()), cx + (outer + s.dp(10f)) * cos(angle).toFloat(),
                    cy + (outer + s.dp(10f)) * sin(angle).toFloat() + s.sp(4f), faint(140), s.sp(11f))
            }
            s.text(year.toString(), cx, cy - s.dp(2f), ink, s.sp(26f))
            if (scores.isNotEmpty()) s.text(res.getString(R.string.home_average, decimal(scores.average().toFloat())), cx, cy + s.sp(18f), faint(150), s.sp(12f))
            s.hit = { x, y ->
                val distance = hypot(x - cx, y - cy)
                if (distance < outer - s.dp(60f) || distance > outer + s.dp(4f)) null
                else {
                    var degrees = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())) + 90
                    if (degrees < 0) degrees += 360
                    first.plusDays((degrees / sweep).toLong()).takeIf { it <= today }
                }
            }
            s.height = size
        }
        ring.onPicked = { picked ->
            draw(picked as LocalDate?)
            card.showDetails(picked?.let { key ->
                val date = key as LocalDate
                listOfNotNull(dateText(date), moodOn(date)?.let(::moodWord) ?: "–",
                    med?.let { m -> dayOf(date)?.face(m.id)?.let { res.getString(R.string.stat_label, m.name, faceLabel(m, it)) } }).joinToString(" · ")
            })
        }
        draw(null)
        if (med != null) card.key(moodColor(4) to mood.name, ContextCompat.getColor(context, R.color.medicineDark) to med.name)
    }

    // --- What comes next ---

    /** How each day's mood flows into the next day's. Tapping a mood on the left lights up its flows. */
    private fun nextDayCard() {
        val card = Card(res.getString(R.string.home_next_day), res.getString(R.string.mood))
        val flows = Array(6) { IntArray(6) }
        var total = 0
        moodDates.forEach { date ->
            val next = moodOn(date.plusDays(1)) ?: return@forEach
            flows[moodOn(date)!!][next]++
            total++
        }
        if (total < MIN_FLOWS) return card.note(res.getString(R.string.needs_more_days))
        val view = card.scene { }
        fun draw(picked: Int?) = view.setScene(keepPicked = true) { s ->
            val top = s.dp(6f)
            val height = s.dp(220f)
            val gap = s.dp(7f)
            val usable = height - gap * 4
            val nodeWidth = s.dp(12f)
            val labelSize = s.sp(12f)
            val leftX = (1..5).maxOf { s.textWidth(moodWord(it), labelSize) } + s.dp(10f)
            val rightX = s.width - leftX - nodeWidth
            val outOf = IntArray(6) { from -> flows[from].sum() }
            val into = IntArray(6) { to -> (1..5).sumOf { flows[it][to] } }
            fun positions(totals: IntArray): FloatArray {
                val y = FloatArray(6)
                var at = top
                for (k in 5 downTo 1) {
                    y[k] = at
                    at += totals[k].toFloat() / total * usable + gap
                }
                return y
            }
            val left = positions(outOf)
            val right = positions(into)
            val leftAt = left.copyOf()
            val rightAt = right.copyOf()
            for (from in 5 downTo 1) for (to in 5 downTo 1) {
                val count = flows[from][to]
                if (count == 0) continue
                val h = count.toFloat() / total * usable
                val y0 = leftAt[from]
                val y1 = rightAt[to]
                leftAt[from] += h
                rightAt[to] += h
                val x0 = leftX + nodeWidth
                val x1 = rightX
                val mid = (x0 + x1) / 2
                val band = Path().apply {
                    moveTo(x0, y0)
                    cubicTo(mid, y0, mid, y1, x1, y1)
                    lineTo(x1, y1 + h)
                    cubicTo(mid, y1 + h, mid, y0 + h, x0, y0 + h)
                    close()
                }
                val alpha = when (picked) {
                    null -> 90
                    from -> 210
                    else -> 25
                }
                s.path(band, ColorUtils.setAlphaComponent(moodColor(from), alpha))
            }
            for (k in 5 downTo 1) {
                val hl = maxOf(outOf[k].toFloat() / total * usable, s.dp(2f))
                val hr = maxOf(into[k].toFloat() / total * usable, s.dp(2f))
                s.rect(leftX, left[k], leftX + nodeWidth, left[k] + hl, moodColor(k), s.dp(3f))
                s.rect(rightX, right[k], rightX + nodeWidth, right[k] + hr, moodColor(k), s.dp(3f))
                s.text(moodWord(k), leftX - s.dp(8f), left[k] + hl / 2 + labelSize * 0.35f, if (picked == k) ink else faint(190), labelSize, Paint.Align.RIGHT, bold = picked == k)
                s.text(moodWord(k), rightX + nodeWidth + s.dp(8f), right[k] + hr / 2 + labelSize * 0.35f, faint(190), labelSize, Paint.Align.LEFT)
                if (outOf[k] > 0) {
                    val area = RectF(0f, left[k] - gap / 2, leftX + nodeWidth + s.dp(30f), left[k] + hl + gap / 2)
                    s.target(k, area, RectF(leftX - s.dp(3f), left[k] - s.dp(3f), leftX + nodeWidth + s.dp(3f), left[k] + hl + s.dp(3f)), s.dp(5f))
                }
            }
            s.text(res.getString(R.string.home_a_day), leftX + nodeWidth / 2, top + height + s.dp(18f), faint(140), s.sp(12f))
            s.text(res.getString(R.string.home_the_next), rightX + nodeWidth / 2, top + height + s.dp(18f), faint(140), s.sp(12f))
            s.height = top + height + s.dp(24f)
        }
        view.onPicked = { key ->
            val from = key as Int?
            draw(from)
            card.showDetails(from?.let {
                val out = flows[it].sum()
                res.getString(R.string.home_after_a, moodWord(it).lowercase(), out) + "\n" +
                    (5 downTo 1).filter { to -> flows[it][to] > 0 }.joinToString(" · ") { to ->
                        "${moodWord(to)} ${(flows[it][to] * 100f / out).roundToInt()}%"
                    }
            })
        }
        draw(null)
    }

    // --- Before and after ---

    /** Mood before a medicine was first taken against since, for each medicine with a week of each. */
    private fun beforeAfterCard() {
        val card = Card(res.getString(R.string.home_before_after), res.getString(R.string.mood))
        class Split(val med: StatInfo, val start: LocalDate, val before: List<Int>, val after: List<Int>)

        val splits = stats.filter { it.medicine }.mapNotNull { med ->
            val start = allDates.firstOrNull { takenOn(med, it, shift = false) == true } ?: return@mapNotNull null
            val before = moodDates.filter { it < start }.mapNotNull(::moodOn)
            val after = moodDates.filter { it >= start }.mapNotNull(::moodOn)
            if (before.size < MIN_SPLIT_DAYS || after.size < MIN_SPLIT_DAYS) null else Split(med, start, before, after)
        }
        if (splits.isEmpty()) return card.note(res.getString(R.string.home_before_after_none))

        val chips = FlowLayout(context)
        card.add(chips)
        val view = card.scene { }
        fun show(split: Split) {
            chips.removeAllViews()
            splits.forEach { other -> chips.addView(chip(other.med.name, other.med.color, other == split) { show(other) }) }
            view.setScene { s ->
                val format = DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault())
                val rows = listOf(
                    Triple(res.getString(R.string.home_before), res.getString(R.string.home_until, split.start.minusDays(1).format(format)), split.before),
                    Triple(res.getString(R.string.home_on, split.med.name), res.getString(R.string.home_since, split.start.format(format)), split.after),
                )
                rows.forEachIndexed { i, (name, span, scores) ->
                    val y = s.dp(14f) + i * s.dp(80f)
                    s.text(name, 0f, y + s.sp(13f), ink, s.sp(14f), Paint.Align.LEFT)
                    s.text(span, 0f, y + s.sp(30f), faint(140), s.sp(12f), Paint.Align.LEFT)
                    s.text(decimal(scores.average().toFloat()), s.width, y + s.sp(26f), ink, s.sp(28f), Paint.Align.RIGHT)
                    var x = 0f
                    for (k in 1..5) {
                        val share = scores.count { it == k } / scores.size.toFloat()
                        val width = share * s.width
                        if (width > s.dp(2f)) s.rect(x + s.dp(1f), y + s.dp(42f), x + width - s.dp(1f), y + s.dp(64f), moodColor(k), s.dp(5f))
                        x += width
                    }
                }
                s.height = s.dp(14f) + rows.size * s.dp(80f) - s.dp(10f)
            }
        }
        show(splits.maxBy { abs(it.after.average() - it.before.average()) })
        card.key(*(5 downTo 1).map { moodColor(it) to moodWord(it) }.toTypedArray())
    }

    // --- Web ---

    /**
     * Every stat with enough days, linked to the ones it tends to move with: green when both are better
     * (or taken) together, red when one goes up as the other goes down. Tapping a stat lights up its links.
     */
    private fun webCard() {
        val card = Card(res.getString(R.string.home_web), res.getString(R.string.home_all_time))
        val nodes = stats.filter { stat -> allDates.count { goodness(stat, it) != null } >= MIN_LINK_DAYS }
        if (nodes.size < 3) return card.note(res.getString(R.string.needs_more_days))
        class Link(val a: StatInfo, val b: StatInfo, val r: Float)

        val values = nodes.associateWith { stat -> allDates.associateWith { goodness(stat, it) } }
        val links = mutableListOf<Link>()
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = values.getValue(nodes[i])
            val b = values.getValue(nodes[j])
            val pairs = allDates.mapNotNull { date -> a[date]?.let { x -> b[date]?.let { y -> x to y } } }
            if (pairs.size < MIN_LINK_DAYS) continue
            val r = correlation(pairs)
            if (abs(r) >= MIN_LINK) links.add(Link(nodes[i], nodes[j], r))
        }
        val good = moodColor(5)
        val bad = moodColor(1)
        val view = card.scene { }
        fun draw(picked: String?) = view.setScene(keepPicked = true) { s ->
            val height = s.dp(300f)
            val cx = s.width / 2
            val cy = height / 2
            val radius = minOf(s.width / 2 - s.dp(36f), height / 2 - s.dp(30f))
            val others = nodes.filter { it.id != MoodStore.MOOD }
            val position = mutableMapOf<String, PointF>()
            nodes.firstOrNull { it.id == MoodStore.MOOD }?.let { position[it.id] = PointF(cx, cy) }
            others.forEachIndexed { i, stat ->
                val angle = -Math.PI / 2 + i * 2 * Math.PI / others.size
                position[stat.id] = PointF(cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat())
            }
            val linked = links.filter { it.a.id == picked || it.b.id == picked }.flatMap { listOf(it.a.id, it.b.id) }.toSet()
            links.sortedBy { it.a.id == picked || it.b.id == picked }.forEach { link ->
                val p = position.getValue(link.a.id)
                val q = position.getValue(link.b.id)
                val bend = Path().apply {
                    moveTo(p.x, p.y)
                    quadTo((p.x + q.x) / 2 + (q.y - p.y) * 0.12f, (p.y + q.y) / 2 - (q.x - p.x) * 0.12f, q.x, q.y)
                }
                val on = picked == null || link.a.id == picked || link.b.id == picked
                val alpha = if (picked == null) (60 + abs(link.r) * 180).toInt() else if (on) 230 else 18
                s.path(bend, ColorUtils.setAlphaComponent(if (link.r > 0) good else bad, alpha.coerceAtMost(255)), s.dp(1.5f) + abs(link.r) * s.dp(9f))
            }
            nodes.forEach { stat ->
                val p = position.getValue(stat.id)
                val on = picked == null || stat.id == picked || stat.id in linked
                val r = s.dp(if (stat.id == MoodStore.MOOD) 15f else 10f)
                if (stat.id == picked) s.ring(p.x, p.y, r + s.dp(6f), ink, s.dp(1.5f))
                s.circle(p.x, p.y, r, ColorUtils.setAlphaComponent(stat.color, if (on) 255 else 70))
                val below = p.y >= cy - s.dp(4f)
                s.text(stat.name, p.x, if (below) p.y + r + s.sp(14f) else p.y - r - s.dp(7f), if (on) faint(230) else faint(70), s.sp(12f))
                val area = RectF(p.x - s.dp(26f), p.y - s.dp(22f), p.x + s.dp(26f), p.y + s.dp(28f))
                s.target(stat.id, area, area, s.dp(10f))
            }
            s.height = height
        }
        view.onPicked = { key ->
            val id = key as String?
            draw(id)
            card.showDetails(id?.let { picked ->
                val stat = nodes.first { it.id == picked }
                val mine = links.filter { it.a.id == picked || it.b.id == picked }.sortedByDescending { abs(it.r) }
                fun other(link: Link) = if (link.a.id == picked) link.b.name else link.a.name
                val together = mine.filter { it.r > 0 }.joinToString(", ") { other(it) }
                val apart = mine.filter { it.r < 0 }.joinToString(", ") { other(it) }
                listOfNotNull(
                    stat.name,
                    together.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.home_web_together, it) },
                    apart.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.home_web_apart, it) },
                    res.getString(R.string.home_web_none).takeIf { mine.isEmpty() },
                ).joinToString("\n")
            })
        }
        draw(null)
        card.key(good to res.getString(R.string.home_go_together), bad to res.getString(R.string.home_pull_apart))
    }

    private fun correlation(pairs: List<Pair<Float, Float>>): Float {
        val mx = pairs.map { it.first }.average()
        val my = pairs.map { it.second }.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        pairs.forEach { (x, y) ->
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
        }
        return if (sxx == 0.0 || syy == 0.0) 0f else (sxy / sqrt(sxx * syy)).toFloat()
    }

    // --- Screen time's cost ---

    /** Each of the last 120 days as a dot, screen time across and mood up, with the line through them. */
    private fun screenCostCard() {
        val card = Card(res.getString(R.string.home_screen_cost), res.getString(R.string.last_days, COST_DAYS))
        val points = (0L until COST_DAYS).mapNotNull { back ->
            val date = today.minusDays(back)
            val hours = dayOf(date)?.usageMs?.let { it / HOUR_MS } ?: return@mapNotNull null
            moodOn(date)?.let { Triple(date, hours, it) }
        }
        if (points.size < MIN_LINK_DAYS) return card.note(res.getString(R.string.needs_more_days))
        val mx = points.map { it.second }.average()
        val my = points.map { it.third.toDouble() }.average()
        val slope = (points.sumOf { (it.second - mx) * (it.third - my) } / points.sumOf { (it.second - mx) * (it.second - mx) }.coerceAtLeast(1e-6)).toFloat()
        val maxHours = maxOf(8f, kotlin.math.ceil(points.maxOf { it.second } / 2) * 2)
        card.scene({ key ->
            val date = key as LocalDate
            val point = points.first { it.first == date }
            "${dateText(date)} · ${context.formattedTimeSpent((point.second * HOUR_MS).toLong())} · ${moodWord(point.third)}"
        }) { s ->
            val left = s.dp(16f)
            val bottom = s.dp(160f)
            val top = s.dp(8f)
            fun x(hours: Float) = left + hours / maxHours * (s.width - left - s.dp(6f))
            fun y(score: Float) = bottom - (score - 1) / 4 * (bottom - top)
            for (score in 1..5) {
                s.line(left, y(score.toFloat()), s.width, y(score.toFloat()), faint(22), s.dp(1f))
                s.text(score.toString(), left - s.dp(8f), y(score.toFloat()) + s.sp(4f), faint(140), s.sp(11f), Paint.Align.RIGHT)
            }
            for (h in 0..maxHours.toInt() step 2) s.text(res.getString(R.string.hours_short, h), x(h.toFloat()), bottom + s.dp(18f), faint(140), s.sp(11f))
            points.forEach { (date, hours, score) ->
                // A little spread so days on the same mood don't stack into one dot
                val jitter = ((date.toEpochDay() * 2654435761L) % 1000 / 1000f - 0.5f) * s.dp(12f)
                val px = x(hours)
                val py = y(score.toFloat()) + jitter
                s.circle(px, py, s.dp(4f), ColorUtils.setAlphaComponent(moodColor(score), 200))
                s.target(date, RectF(px - s.dp(6f), py - s.dp(6f), px + s.dp(6f), py + s.dp(6f)), RectF(px - s.dp(7f), py - s.dp(7f), px + s.dp(7f), py + s.dp(7f)), s.dp(7f))
            }
            val start = my.toFloat() - slope * mx.toFloat()
            s.line(x(0f), y((start).coerceIn(1f, 5f)), x(maxHours), y((start + slope * maxHours).coerceIn(1f, 5f)), ink, s.dp(2.5f))
            s.height = bottom + s.dp(24f)
        }
        card.key(ink to res.getString(if (slope <= 0) R.string.home_each_hour_down else R.string.home_each_hour_up, decimal(abs(slope))))
    }

    // --- Weather ---

    private fun skyForWeather(code: Int) = when (code) {
        0, 1 -> Sky.SUN
        2, 3, 45, 48 -> Sky.CLOUD
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> null
    }

    /** Mood under each kind of weather, from the forecast saved each day. */
    private fun weatherCard() {
        val card = Card(res.getString(R.string.home_weather))
        val bySky = moodDates.mapNotNull { date -> dayOf(date)?.weatherCode?.let(::skyForWeather)?.let { it to moodOn(date)!! } }
            .groupBy({ it.first }, { it.second }).filterValues { it.size >= MIN_WEATHER_DAYS }
        if (bySky.size < 2) return card.note(res.getString(R.string.home_weather_none))
        val skies = listOf(Sky.SUN, Sky.CLOUD, Sky.RAIN, Sky.SNOW, Sky.STORM).filter { it in bySky }
        val names = mapOf(Sky.SUN to R.string.home_sky_sun, Sky.CLOUD to R.string.home_sky_cloud, Sky.RAIN to R.string.home_sky_rain,
            Sky.SNOW to R.string.home_sky_snow, Sky.STORM to R.string.home_sky_storm)
        card.scene({ key ->
            val sky = key as Sky
            val scores = bySky.getValue(sky)
            res.getString(R.string.home_weather_detail, res.getString(names.getValue(sky)), scores.size, decimal(scores.average().toFloat()))
        }) { s ->
            val column = s.width / skies.size
            val barTop = s.dp(58f)
            val barBottom = barTop + s.dp(90f)
            skies.forEachIndexed { i, sky ->
                val x = i * column + column / 2
                val average = bySky.getValue(sky).average().toFloat()
                drawSky(s, sky, x, s.dp(24f), s.dp(38f))
                val top = barBottom - average / 5 * (barBottom - barTop)
                val half = minOf(column * 0.3f, s.dp(22f))
                s.rect(x - half, top, x + half, barBottom, moodColor(average.roundToInt()), s.dp(7f))
                s.text(decimal(average), x, top - s.dp(6f), faint(230), s.sp(12.5f))
                s.text(res.getString(names.getValue(sky)), x, barBottom + s.dp(18f), faint(150), s.sp(12f))
                s.target(sky, RectF(i * column, 0f, (i + 1) * column, barBottom + s.dp(24f)),
                    RectF(x - half - s.dp(4f), top - s.dp(22f), x + half + s.dp(4f), barBottom + s.dp(4f)), s.dp(9f))
            }
            s.height = barBottom + s.dp(24f)
        }
    }

    // --- Bouncing back ---

    /** After a bad patch starts, how many days until Okay or better, as a little bar chart. */
    private fun bounceCard() {
        val card = Card(res.getString(R.string.home_bounce))
        val recoveries = mutableListOf<Int>()
        moodDates.forEach { date ->
            // Only the first bad day of a patch
            val bad = moodOn(date)!! <= 2
            val badBefore = (moodOn(date.minusDays(1)) ?: 5) <= 2
            if (bad && !badBefore) {
                moodDates.firstOrNull { it > date && moodOn(it)!! >= 3 && ChronoUnit.DAYS.between(date, it) <= 30 }
                    ?.let { recoveries.add(ChronoUnit.DAYS.between(date, it).toInt()) }
            }
        }
        if (recoveries.size < MIN_BOUNCES) return card.note(res.getString(R.string.home_bounce_none))
        val buckets = (1..4).map { days -> recoveries.count { if (days == 4) it >= 4 else it == days } }
        card.add(TextView(context, null, 0, R.style.TextLarge).apply {
            text = res.getQuantityString(R.plurals.home_days_decimal, if (recoveries.average() < 1.05) 1 else 2, decimal(recoveries.average().toFloat()))
            textSize = 36f
            setTextColor(ink)
            gravity = Gravity.CENTER
        })
        card.add(text(13f, 150).apply {
            text = res.getString(R.string.home_bounce_lead)
            gravity = Gravity.CENTER
        }, 2.dpToPx())
        val labels = res.getStringArray(R.array.home_bounce_buckets)
        card.scene({ key ->
            val i = key as Int
            res.getString(R.string.home_bounce_detail, labels[i].lowercase(), buckets[i])
        }) { s ->
            val column = s.width / 4
            val top = s.dp(22f)
            val bottom = top + s.dp(70f)
            val most = buckets.max().coerceAtLeast(1)
            buckets.forEachIndexed { i, count ->
                val x = i * column + column / 2
                val half = minOf(column * 0.3f, s.dp(24f))
                val barTop = bottom - count.toFloat() / most * (bottom - top)
                // Greener the quicker
                s.rect(x - half, barTop, x + half, bottom, moodColor(5 - i), s.dp(6f))
                s.text(count.toString(), x, barTop - s.dp(6f), faint(220), s.sp(12f))
                s.text(labels[i], x, bottom + s.dp(18f), faint(150), s.sp(12f))
                s.target(i, RectF(i * column, 0f, (i + 1) * column, bottom + s.dp(24f)),
                    RectF(x - half - s.dp(4f), barTop - s.dp(20f), x + half + s.dp(4f), bottom + s.dp(4f)), s.dp(8f))
            }
            s.height = bottom + s.dp(24f)
        }.also { it.setPadding(0, 12.dpToPx(), 0, 0) }
    }

    // --- Week shape ---

    /** The last 7 days across the main stats against the 90 before, as a radar. */
    private fun weekShapeCard() {
        val card = Card(res.getString(R.string.home_week_shape), res.getString(R.string.home_vs_usual))
        val week = (0L..6L).map { today.minusDays(it) }
        val before = (7L..96L).map { today.minusDays(it) }
        class Axis(val stat: StatInfo, val now: Float, val usual: Float)

        val axes = stats.filter { !it.medicine && it.id != MoodStore.USAGE }.mapNotNull { stat ->
            val now = week.mapNotNull { scoreOn(stat, it) }
            val usual = before.mapNotNull { scoreOn(stat, it) }
            if (now.isEmpty() || usual.size < 7) null else Axis(stat, now.average().toFloat(), usual.average().toFloat())
        }.take(MAX_AXES)
        if (axes.size < 3) return card.note(res.getString(R.string.home_week_shape_none))
        val color = moodColor(4)
        card.scene({ key ->
            val axis = axes[key as Int]
            res.getString(R.string.home_week_shape_detail, axis.stat.name, decimal(axis.now), decimal(axis.usual))
        }) { s ->
            val height = s.dp(250f)
            val cx = s.width / 2
            val cy = height / 2
            val radius = minOf(s.width / 2 - s.dp(44f), height / 2 - s.dp(26f))
            fun point(i: Int, value: Float): PointF {
                val angle = -Math.PI / 2 + i * 2 * Math.PI / axes.size
                return PointF(cx + radius * value / 5 * cos(angle).toFloat(), cy + radius * value / 5 * sin(angle).toFloat())
            }
            fun polygon(values: List<Float>) = Path().apply {
                values.forEachIndexed { i, v -> point(i, v).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            for (level in 1..5) s.path(polygon(List(axes.size) { level.toFloat() }), faint(26), s.dp(1f))
            s.path(polygon(axes.map { it.usual }), faint(150), s.dp(1.5f))
            s.path(polygon(axes.map { it.now }), ColorUtils.setAlphaComponent(color, 60))
            s.path(polygon(axes.map { it.now }), color, s.dp(2.5f))
            axes.forEachIndexed { i, axis ->
                val p = point(i, axis.now)
                s.circle(p.x, p.y, s.dp(4f), color)
                val label = point(i, 6.1f)
                s.text(axis.stat.name, label.x, label.y + s.sp(4f), faint(210), s.sp(12f))
                val area = RectF(label.x - s.dp(30f), label.y - s.dp(14f), label.x + s.dp(30f), label.y + s.dp(10f))
                s.target(i, RectF(minOf(area.left, p.x - s.dp(12f)), minOf(area.top, p.y - s.dp(12f)), maxOf(area.right, p.x + s.dp(12f)), maxOf(area.bottom, p.y + s.dp(12f))),
                    RectF(p.x - s.dp(9f), p.y - s.dp(9f), p.x + s.dp(9f), p.y + s.dp(9f)), s.dp(9f))
            }
            s.height = height
        }
        card.key(color to res.getString(R.string.home_last_7_days), faint(150) to res.getString(R.string.home_usual))
    }

    // --- Words ---

    /** The words written most in notes, sized by how many days and coloured by the mood on them. */
    private fun wordsCard() {
        val card = Card(res.getString(R.string.home_words))
        val stop = res.getStringArray(R.array.home_stop_words).toHashSet()
        val days = mutableMapOf<String, MutableList<LocalDate>>()
        allDates.forEach { date ->
            val note = dayOf(date)?.note ?: return@forEach
            WORD.findAll(note.lowercase()).map { it.value.trim('\'') }.filter { it.length >= 3 && it !in stop }.toSet()
                .forEach { days.getOrPut(it) { mutableListOf() }.add(date) }
        }
        val words = days.filterValues { it.size >= MIN_WORD_DAYS }.entries.sortedByDescending { it.value.size }.take(MAX_WORDS)
        if (words.isEmpty()) return card.note(res.getString(R.string.home_words_none))
        val most = words.first().value.size
        val least = words.last().value.size
        val cloud = FlowLayout(context)
        // Mixed up, but the same way each time
        words.sortedBy { it.key.hashCode() }.forEach { (word, dates) ->
            val scores = dates.mapNotNull(::moodOn)
            val average = scores.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            cloud.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = word
                textSize = 13f + 15f * if (most == least) 0.5f else (dates.size - least).toFloat() / (most - least)
                setTextColor(average?.let { moodColor(it.roundToInt()) } ?: faint(200))
                setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
                setOnClickListener {
                    card.showDetails(res.getString(R.string.home_word_detail, word, dates.size,
                        average?.let { decimal(it) } ?: "–", dateText(dates.last())))
                }
            })
        }
        card.add(cloud)
    }

    // --- Galaxy ---

    /** Every day with a mood as a star, from the first in the middle to today on the rim; days with a note shine bigger. */
    private fun galaxyCard() {
        val card = Card(res.getString(R.string.home_galaxy), res.getQuantityString(R.plurals.day_count, moodDates.size, moodDates.size))
        if (moodDates.size < MIN_GALAXY) return card.note(res.getString(R.string.needs_more_days))
        card.scene({ key ->
            val date = key as LocalDate
            listOfNotNull(dateText(date), moodOn(date)?.let(::moodWord), dayOf(date)?.note?.let { it.take(80) + if (it.length > 80) "…" else "" })
                .joinToString(" · ")
        }) { s ->
            val size = minOf(s.width, s.dp(300f))
            val cx = s.width / 2
            val cy = size / 2
            val maxRadius = size / 2 - s.dp(8f)
            val count = moodDates.size
            moodDates.forEachIndexed { i, date ->
                val angle = i * GOLDEN_ANGLE
                val r = maxRadius * sqrt((i + 1f) / count)
                val x = cx + r * cos(angle).toFloat()
                val y = cy + r * sin(angle).toFloat()
                val big = dayOf(date)?.note != null
                val color = moodColor(moodOn(date)!!)
                val star = s.dp(if (big) 3.6f else 2.2f)
                s.circle(x, y, star * 2f, ColorUtils.setAlphaComponent(color, 50))
                s.circle(x, y, star, color)
                if (i == count - 1) s.ring(x, y, s.dp(8f), ink, s.dp(1.5f))
                s.target(date, RectF(x - s.dp(5f), y - s.dp(5f), x + s.dp(5f), y + s.dp(5f)), RectF(x - s.dp(7f), y - s.dp(7f), x + s.dp(7f), y + s.dp(7f)), s.dp(7f))
            }
            s.height = size
        }
    }

    // --- Milestones ---

    /** Medals for days logged, the longest streak, notes, great days and each medicine's longest run. */
    private fun milestonesCard() {
        val card = Card(res.getString(R.string.home_milestones))
        class Medal(val label: String, val goal: Int, val have: Int, val color: Int)

        val series = mutableListOf(
            Triple(res.getString(R.string.home_m_logged), moodDates.size, listOf(7, 30, 100, 200, 365, 500, 1000)) to SUN_GOLD,
            Triple(res.getString(R.string.home_m_streak), bestRun(moodDates) { true }, listOf(7, 14, 30, 60, 100, 365)) to ContextCompat.getColor(context, R.color.medicineLight),
            Triple(res.getString(R.string.home_m_notes), allDates.count { dayOf(it)?.note != null }, listOf(10, 50, 100, 250, 500)) to RAIN_BLUE,
            Triple(res.getString(R.string.home_m_great), moodDates.count { moodOn(it) == 5 }, listOf(10, 50, 100, 250)) to moodColor(5),
        )
        stats.filter { it.medicine }.forEach { med ->
            series.add(Triple(res.getString(R.string.home_m_run, med.name), bestRun(allDates) { takenOn(med, it, shift = false) == true }, listOf(7, 30, 100, 365)) to med.color)
        }
        val medals = series.flatMap { (info, color) ->
            val (label, have, goals) = info
            listOfNotNull(goals.lastOrNull { it <= have }, goals.firstOrNull { it > have }).map { Medal(label, it, have, color) }
        }
        val earned = medals.count { it.have >= it.goal }
        val subtitleView = res.getString(R.string.home_earned, earned)
        (card.root.getChildAt(0) as LinearLayout).addView(text(13f, 150).apply { text = subtitleView })
        // Earned first
        val shown = medals.sortedBy { if (it.have >= it.goal) 0 else 1 }
        card.scene({ key ->
            val medal = shown[key as Int]
            if (medal.have >= medal.goal) res.getString(R.string.home_medal_earned, medal.goal, medal.label)
            else res.getString(R.string.home_medal_to_go, medal.goal, medal.label, medal.goal - medal.have)
        }) { s ->
            val columns = 3
            val column = s.width / columns
            val rowHeight = s.dp(96f)
            shown.forEachIndexed { i, medal ->
                val x = (i % columns) * column + column / 2
                val y = (i / columns) * rowHeight + s.dp(32f)
                val done = medal.have >= medal.goal
                if (done) {
                    s.circle(x, y, s.dp(28f), medal.color)
                    s.ring(x, y, s.dp(22f), ColorUtils.setAlphaComponent(android.graphics.Color.BLACK, 60), s.dp(2f))
                } else {
                    s.ring(x, y, s.dp(28f), faint(70), s.dp(2f))
                    // How far along, round the edge
                    s.arc(x, y, s.dp(27f), -90f, 360f * medal.have / medal.goal, ColorUtils.setAlphaComponent(medal.color, 160), s.dp(2.5f))
                }
                s.text(medal.goal.toString(), x, y + s.sp(6f), if (done) 0xFF111111.toInt() else faint(140), s.sp(17f), bold = true)
                val label = TextUtils.ellipsize(medal.label, android.text.TextPaint().apply { textSize = s.sp(11.5f) }, column - s.dp(6f), TextUtils.TruncateAt.END).toString()
                s.text(label, x, y + s.dp(46f), faint(if (done) 200 else 110), s.sp(11.5f))
                s.target(i, RectF(x - column / 2, y - s.dp(32f), x + column / 2, y + s.dp(52f)), RectF(x - s.dp(32f), y - s.dp(32f), x + s.dp(32f), y + s.dp(32f)), s.dp(32f))
            }
            s.height = ((shown.size + columns - 1) / columns) * rowHeight
        }
    }

    companion object {
        /** The Home tab's id among the stat tabs */
        const val TAB = "home"

        private const val HOUR_MS = 3_600_000f
        private const val LONG_SCREEN_MS = 4 * 60 * 60 * 1000L
        private const val RECENT_NOTES = 5
        private const val MIN_BEST_DAYS = 5
        private const val MIN_FLOWS = 14
        private const val MIN_SPLIT_DAYS = 7
        private const val MIN_LINK_DAYS = 14
        private const val MIN_LINK = 0.2f
        private const val COST_DAYS = 120
        private const val MIN_WEATHER_DAYS = 3
        private const val MIN_BOUNCES = 3
        private const val MAX_AXES = 6
        private const val MIN_WORD_DAYS = 2
        private const val MAX_WORDS = 28
        private const val MIN_GALAXY = 10
        private val WORD = Regex("[\\p{L}']+")
        private const val GOLDEN_ANGLE = 2.39996

        private const val SUN_GOLD = 0xFFFFD54F.toInt()
        private const val CLOUD_LIGHT = 0xFFCFD8DC.toInt()
        private const val CLOUD_DARK = 0xFF90A4AE.toInt()
        private const val CLOUD_STORM = 0xFF607D8B.toInt()
        private const val RAIN_BLUE = 0xFF4FC3F7.toInt()
    }
}
