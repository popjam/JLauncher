package app.olauncher.helper

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import app.olauncher.R
import app.olauncher.ui.PixelFaceView

object MoodPrompt {

    /**
     * Fills a view_mood_prompt layout with the five faces. Tapping a face only selects it, so the
     * thoughts box isn't skipped by accident; Confirm (enabled once a face is picked) passes the
     * mood and note to [onResult]. Closing the prompt passes null for both.
     */
    fun bind(root: View, onResult: (mood: Int?, note: String?) -> Unit) {
        val context = root.context
        val names = context.resources.getStringArray(R.array.mood_names)
        val row = root.findViewById<LinearLayout>(R.id.moodPromptFaces)
        val note = root.findViewById<EditText>(R.id.moodPromptNote)
        val confirm = root.findViewById<View>(R.id.moodPromptConfirm)
        var selected: Int? = null

        fun render() {
            for (i in 0 until row.childCount) {
                row.getChildAt(i).alpha = if (selected == null || selected == i) 1f else 0.3f
            }
            confirm.isEnabled = selected != null
            confirm.alpha = if (selected != null) 1f else 0.3f
        }

        fun finish(mood: Int?, text: String?) {
            note.clearFocus()
            note.hideKeyboard()
            onResult(mood, text)
        }

        row.removeAllViews()
        for (mood in 0..4) {
            row.addView(PixelFaceView(context).apply {
                this.mood = mood
                contentDescription = names[mood]
                setPadding(6.dpToPx())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selected = mood
                    render()
                }
            }, LinearLayout.LayoutParams(0, 52.dpToPx(), 1f))
        }
        note.setText("")
        confirm.setOnClickListener { selected?.let { finish(it, note.text?.toString()) } }
        root.findViewById<View>(R.id.moodPromptClose).setOnClickListener { finish(null, null) }
        render()
    }

    private fun View.setPadding(px: Int) = setPadding(px, px, px, px)
}
