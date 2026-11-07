package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.Sleep
import app.olauncher.helper.StatInfo
import app.olauncher.helper.StatKind
import app.olauncher.helper.allStats
import app.olauncher.helper.formattedTimeSpent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A plain report of the last stretch of days, to bring to an appointment: mood over the period against
 * the one before, each medicine's doses day by day with when they changed, how every other stat went,
 * sleep and screen time, and the lowest and highest days with their notes. Written as a PDF to [uri].
 */
object Report {

    private const val W = 595
    private const val H = 842
    private const val M = 44f

    fun write(context: Context, uri: Uri, days: Int) {
        val data = MoodStore(context).load()
        val stats = context.allStats(data)
        val today = LocalDate.now()
        val start = today.minusDays(days - 1L)
        val dates = (0 until days).map { start.plusDays(it.toLong()) }
        val before = (1..days).map { start.minusDays(it.toLong()) }
        fun face(stat: StatInfo, date: LocalDate) = data.days[date.toString()]?.face(stat.id)
        fun score(stat: StatInfo, date: LocalDate) = face(stat, date)?.let { MoodStore.score(it, stat.inverted).toFloat() }
        fun average(stat: StatInfo, range: List<LocalDate>) = range.mapNotNull { score(stat, it) }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

        val doc = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNumber).create())
            canvas = page!!.canvas
            y = M
        }
        fun need(height: Float) { if (y + height > H - M) newPage() }
        val ink = Color.rgb(25, 25, 28)
        val grey = Color.rgb(120, 120, 128)
        val line = Color.rgb(225, 225, 230)
        fun paint(size: Float, color: Int = ink, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        fun text(s: String, size: Float, color: Int = ink, bold: Boolean = false, x: Float = M, width: Float = W - 2 * M) {
            val p = paint(size, color, bold)
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            need(layout.height.toFloat())
            canvas!!.save()
            canvas!!.translate(x, y)
            layout.draw(canvas!!)
            canvas!!.restore()
            y += layout.height
        }
        fun heading(s: String) {
            need(40f)
            y += 18f
            text(s, 14f, bold = true)
            y += 4f
            canvas!!.drawRect(M, y, W - M, y + 0.8f, Paint().apply { color = line })
            y += 8f
        }
        val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

        newPage()
        text(context.getString(R.string.report_title), 22f, bold = true)
        y += 4f
        text(start.format(dateFormat) + " – " + today.format(dateFormat) + " · " + context.getString(R.string.last_days, days), 11f, grey)

        // Mood: the line, its average, against the period before
        val mood = stats.firstOrNull { it.id == MoodStore.MOOD }
        if (mood != null) {
            heading(mood.name)
            val now = average(mood, dates)
            val then = average(mood, before)
            val logged = dates.count { score(mood, it) != null }
            text(listOfNotNull(
                now?.let { context.getString(R.string.report_average, String.format(Locale.getDefault(), "%.1f", it)) },
                if (now != null && then != null) context.getString(R.string.report_before, String.format(Locale.getDefault(), "%.1f", then)) else null,
                context.getString(R.string.report_logged, logged, days),
            ).joinToString(" · "), 11f)
            y += 8f
            need(110f)
            val c = canvas!!
            val top = y
            val height = 90f
            val left = M + 14f
            val width = W - M - left
            listOf(1, 3, 5).forEach { g ->
                val gy = top + height - (g - 1) / 4f * height
                c.drawLine(left, gy, W - M, gy, Paint().apply { color = line; strokeWidth = 0.6f })
                c.drawText(g.toString(), M, gy + 3f, paint(8f, grey))
            }
            val pts = dates.mapIndexedNotNull { i, d -> score(mood, d)?.let { left + i * width / (days - 1).coerceAtLeast(1) to top + height - (it - 1) / 4f * height } }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mood.color; strokeWidth = 1.6f; style = Paint.Style.STROKE }
            pts.zipWithNext().forEach { (a, b) -> c.drawLine(a.first, a.second, b.first, b.second, stroke) }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mood.color }
            pts.forEach { (x, py) -> c.drawCircle(x, py, 1.8f, dot) }
            c.drawText(start.format(shortDate), left, top + height + 12f, paint(8f, grey))
            val end = today.format(shortDate)
            c.drawText(end, W - M - paint(8f).measureText(end), top + height + 12f, paint(8f, grey))
            y = top + height + 20f
        }

        // Medicines: a strip of the dose each day, how often taken, and when the dose changed
        val medicines = stats.filter { med -> med.isMedicine && dates.any { face(med, it) != null } }
        if (medicines.isNotEmpty()) {
            heading(context.getString(R.string.report_medicines))
            text(context.getString(R.string.report_doses_key), 9.5f, grey)
            y += 4f
            medicines.forEach { med ->
                val label = { f: Int -> med.labels.getOrNull(med.faces.indexOf(f))?.takeIf { it.isNotEmpty() } ?: "" }
                val taken = dates.count { d -> face(med, d)?.let { !med.isBlank(it) } == true }
                val logged = dates.count { face(med, it) != null }
                val usual = dates.mapNotNull { d -> face(med, d)?.takeIf { !med.isBlank(it) } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                text(med.name, 12f, bold = true)
                text(listOfNotNull(
                    context.getString(R.string.report_taken, taken, logged),
                    usual?.let { context.getString(R.string.report_usual_dose, label(it)) },
                ).joinToString(" · "), 10.5f, grey)
                // Changes of dose
                var last: Int? = null
                val changes = mutableListOf<String>()
                dates.forEach { d ->
                    val f = face(med, d)?.takeIf { !med.isBlank(it) } ?: return@forEach
                    if (last != null && f != last) changes += d.format(shortDate) + ": " + label(last!!) + " → " + label(f)
                    last = f
                }
                if (changes.isNotEmpty()) text(context.getString(R.string.report_changes) + " " + changes.joinToString(", "), 10.5f)
                y += 4f
                need(22f)
                val c = canvas!!
                val cell = (W - 2 * M) / days
                dates.forEachIndexed { i, d ->
                    val f = face(med, d)
                    val color = when {
                        f == null -> Color.rgb(240, 240, 243)
                        med.isBlank(f) -> Color.rgb(214, 214, 220)
                        else -> med.faceColor(context, f)
                    }
                    c.drawRect(RectF(M + i * cell + 0.5f, y, M + (i + 1) * cell - 0.5f, y + 14f), Paint().apply { this.color = color })
                }
                y += 24f
            }
        }

        // Every other stat: its average and which way it went
        // Stats rated on faces; ones counted (coffee, hours, money) don't average out of 5
        val others = stats.filter { !it.medicine && it.id != MoodStore.MOOD && it.id != MoodStore.USAGE &&
            it.kind !in setOf(StatKind.MONEY, StatKind.SLEEP, StatKind.SOCIAL, StatKind.VIBECODING, StatKind.MUSIC, StatKind.WORK, StatKind.SCREEN) }
            .mapNotNull { s -> average(s, dates)?.let { Triple(s, it, average(s, before)) } }
        if (others.isNotEmpty()) {
            heading(context.getString(R.string.report_stats))
            text(context.getString(R.string.report_scale), 9.5f, grey)
            y += 4f
            others.forEach { (s, now, then) ->
                need(16f)
                val c = canvas!!
                c.drawText(s.name, M, y + 11f, paint(11f))
                val value = String.format(Locale.getDefault(), "%.1f", now)
                c.drawText(value, M + 250f, y + 11f, paint(11f, bold = true))
                if (then != null) {
                    val diff = now - then
                    val arrow = when {
                        diff > 0.15f -> "▲ " + String.format(Locale.getDefault(), "%.1f", diff)
                        diff < -0.15f -> "▼ " + String.format(Locale.getDefault(), "%.1f", -diff)
                        else -> "="
                    }
                    c.drawText(arrow + "  " + context.getString(R.string.report_vs_before), M + 300f, y + 11f, paint(10f, grey))
                }
                y += 17f
            }
        }

        // Sleep and screen time
        val nights = Sleep.nights(context).filterKeys { it in dates }
        val usage = dates.mapNotNull { data.days[it.toString()]?.totalUsageMs }
        if (nights.isNotEmpty() || usage.isNotEmpty()) {
            heading(context.getString(R.string.report_daily_life))
            if (nights.isNotEmpty()) {
                val asleep = nights.values.map { it.asleepMs }.average().toLong()
                text(context.getString(R.string.report_sleep, context.formattedTimeSpent(asleep), nights.size), 11f)
            }
            if (usage.isNotEmpty()) text(context.getString(R.string.report_screen, context.formattedTimeSpent(usage.average().toLong())), 11f)
        }

        // The lowest and highest days, with their notes
        if (mood != null) {
            val scored = dates.mapNotNull { d -> score(mood, d)?.let { d to it } }
            val low = scored.sortedBy { it.second }.take(5).sortedBy { it.first }
            val high = scored.sortedByDescending { it.second }.take(3).sortedBy { it.first }
            fun list(title: String, days: List<Pair<LocalDate, Float>>) {
                if (days.isEmpty()) return
                heading(title)
                days.forEach { (d, s) ->
                    val note = data.days[d.toString()]?.note?.takeIf { it.isNotBlank() }
                    val moodName = data.days[d.toString()]?.mood?.let { context.resources.getStringArray(R.array.mood_names)[it] } ?: s.roundToInt().toString()
                    text(d.format(shortDate) + " · " + moodName, 11f, bold = true)
                    if (note != null) text(note, 10.5f, grey)
                    y += 6f
                }
            }
            list(context.getString(R.string.report_low_days), low)
            list(context.getString(R.string.report_high_days), high)
        }

        y += 10f
        text(context.getString(R.string.report_footer, LocalDate.now().format(dateFormat)), 8.5f, grey)
        page?.let { doc.finishPage(it) }
        // Made in memory first, so a failure part way leaves nothing half-written
        val bytes = java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        doc.close()
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the file")
        out.use { it.write(bytes) }
        android.util.Log.d("Report", "Wrote ${bytes.size} bytes, $pageNumber pages")
    }
}
