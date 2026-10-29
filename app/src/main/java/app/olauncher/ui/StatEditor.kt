package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.databinding.DialogStatEditorBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard

/**
 * The form for adding a stat (from +) or editing one (tapping its tab when it's already open). Mood
 * gets the colour picker and display text, Screen time just the colour, and a custom stat's scale
 * can't change once added.
 */
object StatEditor {

    /** Darker than this makes lines hard to see on the dark theme */
    private const val MIN_BRIGHTNESS = 0.2f

    /**
     * [statId] is null for a new stat. [onDone] gets the stat to show afterwards: the new or edited one,
     * or null when it was deleted.
     */
    fun show(context: Context, moodStore: MoodStore, statId: String?, onDone: (statId: String?) -> Unit) {
        val data = moodStore.load()
        val builtIn = statId == MoodStore.MOOD || statId == MoodStore.USAGE
        val stat = statId?.let { data.stat(it) }
        if (statId != null && !builtIn && stat == null) return

        val binding = DialogStatEditorBinding.inflate(LayoutInflater.from(context))
        binding.statNameSection.isVisible = !builtIn
        binding.statOptionsSection.isVisible = !builtIn

        // Display text: empty keeps the daily check's usual title, shown as the hint
        binding.statQuestionSection.isVisible = statId != MoodStore.USAGE
        if (builtIn) binding.statQuestionLabel.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = 0 }
        binding.statQuestion.setText(if (statId == MoodStore.MOOD) data.moodQuestion else stat?.question.orEmpty())
        binding.statQuestion.hint = context.getString(
            if (statId == MoodStore.MOOD && data.dailyStats.isEmpty()) R.string.how_are_you_feeling else R.string.how_was_today
        )

        // Colour: hue and saturation on the wheel, brightness on the slider
        val hsv = FloatArray(3)
        Color.colorToHSV(
            if (statId == null) MoodStore.NEW_STAT_COLORS[data.stats.size % MoodStore.NEW_STAT_COLORS.size]
            else data.colorOf(statId),
            hsv
        )
        hsv[2] = hsv[2].coerceAtLeast(MIN_BRIGHTNESS)
        val swatch = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(2.dpToPx(), context.getColorFromAttr(R.attr.primaryColorTrans50))
        }
        binding.statColorSwatch.background = swatch
        fun color() = Color.HSVToColor(hsv)
        fun renderColor() {
            val color = color()
            swatch.setColor(color)
            binding.statColorWheel.knobColor = color
            binding.statColorHex.text = String.format("#%06X", color and 0xFFFFFF)
        }
        binding.statColorWheel.setColor(hsv[0], hsv[1])
        binding.statColorWheel.onChange = { hue, saturation ->
            hsv[0] = hue
            hsv[1] = saturation
            renderColor()
        }
        binding.statBrightness.progress = ((hsv[2] - MIN_BRIGHTNESS) / (1 - MIN_BRIGHTNESS) * 100).toInt()
        binding.statBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                hsv[2] = MIN_BRIGHTNESS + (1 - MIN_BRIGHTNESS) * progress / 100f
                renderColor()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        renderColor()

        // Name, daily check and scale
        binding.statName.setText(stat?.name.orEmpty())
        binding.statDaily.isChecked = stat?.daily ?: true
        var scale = stat?.scale ?: 5
        if (stat != null) binding.statScaleLabel.text = context.getString(R.string.stat_scale_locked)
        val scaleViews = MoodStore.SCALES.associateWith { option -> scaleOption(context, option) }
        // Face labels, kept by face so switching scale while adding keeps what's typed
        val labels = mutableMapOf<Int, String>()
        stat?.labels?.forEachIndexed { i, label -> labels[MoodStore.facesFor(stat.scale)[i]] = label }
        fun renderLabels() {
            binding.statFaceLabels.removeAllViews()
            MoodStore.facesFor(scale).forEachIndexed { i, face ->
                binding.statFaceLabels.addView(faceLabelInput(context, face, labels[face].orEmpty()) { labels[face] = it },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = 4.dpToPx()
                    })
            }
        }
        fun faceLabels() = MoodStore.facesFor(scale).map { labels[it].orEmpty() }
        binding.statClearLabels.setOnClickListener {
            labels.clear()
            binding.statFaceLabels.findFocus()?.hideKeyboard()
            renderLabels()
        }

        fun renderScales() {
            scaleViews.forEach { (option, view) ->
                view.setBackgroundResource(if (option == scale) R.drawable.bg_day_selected else R.drawable.rounded_box)
                view.alpha = if (stat == null || option == scale) 1f else 0.3f
            }
            if (binding.statFaceLabels.childCount != scale) renderLabels()
        }
        scaleViews.forEach { (option, view) ->
            binding.statScales.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (option == MoodStore.SCALES.last()) 0 else 6.dpToPx()
            })
            if (stat == null) view.setOnClickListener {
                scale = option
                renderScales()
            }
        }
        renderScales()

        val title = when {
            statId == MoodStore.MOOD -> context.getString(R.string.mood)
            builtIn -> context.getString(R.string.screen_time)
            stat != null -> context.getString(R.string.edit_stat)
            else -> context.getString(R.string.new_stat)
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (statId == null) R.string.add else R.string.save) { _, _ ->
                val name = binding.statName.text?.toString().orEmpty()
                val question = binding.statQuestion.text?.toString().orEmpty()
                when {
                    builtIn -> moodStore.editBuiltIn(statId!!, color(), question)
                    stat != null -> moodStore.editStat(stat.id, name, color(), binding.statDaily.isChecked, question, faceLabels())
                    else -> {
                        val added = moodStore.addStat(name, color(), binding.statDaily.isChecked, scale, question, faceLabels())
                        return@setPositiveButton onDone(added.id)
                    }
                }
                onDone(statId)
            }
        if (stat != null) builder.setNeutralButton(R.string.delete_stat) { _, _ -> confirmDelete(context, moodStore, stat, onDone) }
        val dialog = builder.show()

        if (!builtIn) {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun renderPositive() {
                positive.isEnabled = MoodStore.sanitizeName(binding.statName.text?.toString()).isNotEmpty()
            }
            binding.statName.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = renderPositive()
            })
            renderPositive()
        }
    }

    private fun confirmDelete(context: Context, moodStore: MoodStore, stat: MoodStore.Stat, onDone: (String?) -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.delete_stat_message, stat.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_stat) { _, _ ->
                moodStore.deleteStat(stat.id)
                onDone(null)
            }
            .show()
    }

    /** A face with a box below for its label. */
    private fun faceLabelInput(context: Context, face: Int, text: String, onChange: (String) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(PixelFaceView(context).apply { mood = face }, LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()))
        // The default EditText style, which makes it focusable and brings up the keyboard
        addView(EditText(context).apply {
            setBackgroundResource(R.drawable.rounded_box)
            setPadding(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
            gravity = Gravity.CENTER
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MoodStore.MAX_LABEL_LENGTH))
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(text)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dpToPx()
        })
    }

    /** The scale's faces with "Out of N" below. */
    private fun scaleOption(context: Context, scale: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            MoodStore.facesFor(scale).forEach { face ->
                addView(PixelFaceView(context).apply { mood = face }, LinearLayout.LayoutParams(13.dpToPx(), 13.dpToPx()).apply {
                    marginStart = 1.dpToPx()
                    marginEnd = 1.dpToPx()
                })
            }
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.out_of, scale)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dpToPx()
        })
    }
}
