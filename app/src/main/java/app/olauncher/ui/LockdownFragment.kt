package app.olauncher.ui

import android.content.Context
import android.view.View
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSync

/**
 * Lockdown's page: its status and what can be done now (Start, Add time, a break, End early), then
 * its sections under Rules, each a page of its own, then Run setup again and Lockdown stats. With
 * [SECTION] it's one of those sections' pages instead. The rows are made in [LockdownBaseFragment].
 */
class LockdownFragment : LockdownBaseFragment() {

    companion object {
        /** Argument: open straight at "Lock for a set time" (from the Quick Settings tile) */
        const val LOCK_FOR = "lockFor"

        /** Argument: the section to show, one of [LockdownBaseFragment.SECTIONS]; none for the Lockdown page */
        const val SECTION = "section"
    }

    private val section get() = arguments?.getString(SECTION)?.takeIf { it in SECTIONS }

    override fun title() = section?.let { sectionTitle(it) } ?: getString(R.string.lockdown)
    override fun icon() = if (section == null) StatIcons.LOCK else null

    override fun onResume() {
        super.onResume()
        // Shows what the Mac changed as soon as it's read
        LockdownSync.onPulled = { if (isAdded && holdLists.none { it.selecting }) render() }
        LockdownSync.pull(requireContext(), force = true)
        if (section == null) {
            if (arguments?.getBoolean(LOCK_FOR) == true) {
                arguments?.remove(LOCK_FOR)
                askLockFor()
            }
            // The first time, with nothing set up, the setup opens
            if (!prefs.lockdownSetupOffered) {
                prefs.lockdownSetupOffered = true
                if (LockdownSetupFragment.isFresh(prefs)) openSetup()
            }
        }
        // Times, breaks and waits move on while the page is open
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 15_000)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        LockdownSync.onPulled = null
        super.onPause()
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!isAdded) return
            Limits.refresh(requireContext())
            // Not while picking several, so the ticks stay put
            if (holdLists.none { it.selecting }) render()
            main.postDelayed(this, 15_000)
        }
    }

    override fun build() = show(section?.let { sectionRows(it) } ?: lockdownRows())
}

/** "Total over 4h · until 7 am": the day's total screen time across every device */
fun triggerName(context: Context, trigger: Lockdown.Trigger): String {
    val until = Formats.time(context, Formats.minutesOfDay(trigger.until))
    return context.getString(R.string.lockdown_trigger_name, context.getString(R.string.lockdown_source_total),
        Formats.duration(trigger.minutes * 60_000L), until)
}

/** Today's use of an app on a bar, with a mark at its limit ([limits], minutes) */
class LimitBar(context: Context, private val limits: List<Int>, private val usedMs: Long, private val ink: Int) : View(context) {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val set = limits.withIndex().filter { it.value > 0 }
        // The bar runs a little past the last mark, or past today's use if that's more
        val span = maxOf((set.maxOfOrNull { it.value } ?: 60) * 60_000L * 6 / 5, usedMs * 21 / 20).coerceAtLeast(1)
        val left = 4 * d
        val right = width - 4 * d
        val y = height / 2f
        val h = 8 * d
        fun x(ms: Long) = left + (right - left) * (ms.toFloat() / span).coerceIn(0f, 1f)
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 30)
        canvas.drawRoundRect(left, y - h / 2, right, y + h / 2, h / 2, h / 2, paint)
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 215)
        canvas.drawRoundRect(left, y - h / 2, x(usedMs).coerceAtLeast(left + h), y + h / 2, h / 2, h / 2, paint)
        set.forEach { (_, minutes) ->
            val mx = x(minutes * 60_000L)
            paint.color = ink
            canvas.drawRect(mx - d, y - 10 * d, mx + d, y + 10 * d, paint)
        }
    }
}
