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

        /**
         * A button like the popups' own, for a popup that lays out its buttons itself: [boxed] for
         * the main one (Save, Next), plain for the rest; [color] for one that deletes.
         */
        fun button(context: Context, text: Int, boxed: Boolean = false, color: Int? = null, onTap: () -> Unit): android.widget.Button =
            android.widget.Button(context, null, 0, if (boxed) R.style.AppDialogButton_Boxed else R.style.AppDialogButton).apply {
                setText(text)
                stateListAnimator = null
                if (color != null) setTextColor(color)
                setOnClickListener { onTap() }
            }

        /**
         * A popup in the same frame as the others showing [content] instead of a title, message and
         * buttons: centred, the page dimmed behind it.
         */
        fun custom(context: Context, content: View, cancelable: Boolean = true) = android.app.Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCancelable(cancelable)
            setCanceledOnTouchOutside(cancelable)
            window?.apply {
                setBackgroundDrawableResource(R.drawable.dialog_window)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(android.view.Gravity.CENTER)
                setDimAmount(0.7f)
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
