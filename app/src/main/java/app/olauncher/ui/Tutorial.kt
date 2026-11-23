package app.olauncher.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * A three-page look round on first open: home, the stats, and settings. Skippable at any point, and
 * shown again from Settings. An [AppDialog] like every other popup.
 */
object Tutorial {

    private val PAGES = listOf(
        R.string.tutorial_home_title to R.string.tutorial_home,
        R.string.tutorial_stats_title to R.string.tutorial_stats,
        R.string.tutorial_settings_title to R.string.tutorial_settings,
    )

    fun show(activity: Activity) {
        val ink = activity.getColorFromAttr(R.attr.primaryColor)
        var page = 0
        val step = TextView(activity, null, 0, R.style.TextSmall).apply {
            textSize = Style.TEXT_SMALL
            setTextColor(ink)
            alpha = 0.5f
        }
        val body = TextView(activity, null, 0, R.style.TextSmall).apply {
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            alpha = 0.85f
            setLineSpacing(0f, 1.25f)
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 4.dpToPx(), 24.dpToPx(), 4.dpToPx())
            addView(step)
            addView(body)
        }
        val dialog = AppDialog(activity)
            .setTitle(PAGES[0].first)
            .setView(content)
            .setNegativeButton(R.string.tutorial_skip, null)
            .setPositiveButton(R.string.tutorial_next, null)
            .create()
        dialog.setOnShowListener {
            val next = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val skip = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            fun draw() {
                step.text = activity.getString(R.string.tutorial_step, page + 1, PAGES.size)
                dialog.setTitle(PAGES[page].first)
                body.setText(PAGES[page].second)
                next.setText(if (page == PAGES.lastIndex) R.string.tutorial_done else R.string.tutorial_next)
                skip.visibility = if (page == PAGES.lastIndex) View.INVISIBLE else View.VISIBLE
            }
            // Next turns the page rather than closing, until the last
            next.setOnClickListener {
                if (page == PAGES.lastIndex) dialog.dismiss() else {
                    page++
                    draw()
                }
            }
            draw()
        }
        dialog.show()
    }
}
