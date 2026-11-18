package app.olauncher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * A popup in the app's style (the AppDialog theme does the frame, title and buttons): text boxes in
 * what it shows are outlined boxes like the check-in's, not underlined.
 */
class AppDialog(context: Context) : AlertDialog.Builder(context) {

    override fun setView(view: View?): AlertDialog.Builder {
        view?.let { boxFields(it) }
        return super.setView(view)
    }

    companion object {
        /** Outlines the text boxes in [view] that still have the phone's underline; ones styled already are left */
        fun boxFields(view: View) {
            when (view) {
                is EditText -> if (view.background !is GradientDrawable) box(view)
                is ViewGroup -> (0 until view.childCount).forEach { boxFields(view.getChildAt(it)) }
            }
        }

        /** [view] as an outlined box, like the check-in's thoughts */
        fun box(view: android.widget.TextView) {
            val context = view.context
            view.setBackgroundResource(R.drawable.rounded_box)
            view.setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            view.setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            view.setHintTextColor(context.getColorFromAttr(R.attr.primaryColorTrans50))
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = maxOf(it.topMargin, 4.dpToPx()) }
        }
    }
}
