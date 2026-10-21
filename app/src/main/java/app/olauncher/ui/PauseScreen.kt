package app.olauncher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.FragmentPauseBinding

/**
 * The wellness screen in [binding]: a breathing pause of [pauseSeconds] (skipped when null), then,
 * if [askUseFor], how long to use the app for. Shared by [PauseFragment] in the launcher and the
 * overlay that covers apps opened elsewhere.
 *
 * [onOpen] gets the chosen minutes, or null when only the pause was shown.
 */
class PauseScreen(
    private val binding: FragmentPauseBinding,
    private val appLabel: String,
    private val pauseSeconds: Int?,
    private val askUseFor: Boolean,
    private val onOpen: (minutes: Int?) -> Unit,
    onCancel: () -> Unit,
) {
    private val context = binding.root.context
    private var breathing: ObjectAnimator? = null
    private var secondsLeft = 0

    private val tick = object : Runnable {
        override fun run() {
            if (secondsLeft == 0) {
                pauseDone()
                return
            }
            binding.pauseCountdown.text = secondsLeft.toString()
            binding.pauseOpen.text = context.getString(R.string.open_app_in, secondsLeft)
            secondsLeft--
            binding.root.postDelayed(this, 1000)
        }
    }

    init {
        binding.pauseCancel.setOnClickListener { onCancel() }
        binding.pauseOpen.setOnClickListener { onOpen(null) }
        listOf(
            binding.useFor2 to 2,
            binding.useFor5 to 5,
            binding.useFor10 to 10,
            binding.useFor30 to 30,
        ).forEach { (box, minutes) ->
            box.text = context.getString(R.string.minutes_short, minutes)
            box.setOnClickListener { onOpen(minutes) }
        }
    }

    fun start() {
        if (pauseSeconds == null) {
            showUseFor()
            return
        }
        binding.pauseAppName.text = appLabel
        binding.pauseOpen.isEnabled = false
        binding.pauseOpen.alpha = 0.3f
        startBreathing()
        secondsLeft = pauseSeconds
        tick.run()
    }

    fun stop() {
        binding.root.removeCallbacks(tick)
        breathing?.cancel()
        breathing = null
    }

    private fun pauseDone() {
        if (askUseFor) {
            showUseFor()
            return
        }
        binding.pauseCountdown.text = ""
        binding.pauseOpen.text = context.getString(R.string.open_app)
        binding.pauseOpen.isEnabled = true
        binding.pauseOpen.alpha = 1f
    }

    /** Swaps the breathing pause for the time choice, on the same card. */
    private fun showUseFor() {
        breathing?.cancel()
        binding.useForTitle.text = context.getString(R.string.use_for_question, appLabel)
        binding.pauseBreathing.isVisible = false
        binding.pauseOpen.isVisible = false
        binding.useForLayout.isVisible = true
    }

    /** The circle grows for a slow breath in and shrinks for the breath out. */
    private fun startBreathing() {
        var breathingIn = true
        binding.pauseMessage.text = context.getString(R.string.breathe_in)
        breathing = ObjectAnimator.ofPropertyValuesHolder(
            binding.breathCircle,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f),
        ).apply {
            duration = BREATH_MS
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    breathingIn = !breathingIn
                    binding.pauseMessage.text =
                        context.getString(if (breathingIn) R.string.breathe_in else R.string.breathe_out)
                }
            })
            start()
        }
    }

    companion object {
        private const val BREATH_MS = 4_000L
    }
}
