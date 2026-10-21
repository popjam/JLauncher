package app.olauncher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentPauseBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wellness pause shown before opening a paused app: breathe for a few seconds, then open or cancel. */
class PauseFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentPauseBinding? = null
    private val binding get() = _binding!!
    private var breathing: ObjectAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPauseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = viewModel.pausedApp
        if (app == null) {
            // Nothing to open, e.g. the launcher was restarted while this was showing
            findNavController().popBackStack()
            return
        }
        binding.pauseAppName.text = app.appLabel
        binding.pauseCancel.setOnClickListener { findNavController().popBackStack() }
        binding.pauseOpen.setOnClickListener {
            viewModel.openPausedApp()
            findNavController().popBackStack()
        }
        startBreathing()
        countDown(Prefs(requireContext()).pauseSeconds)
    }

    private fun countDown(seconds: Int) {
        binding.pauseOpen.isEnabled = false
        binding.pauseOpen.alpha = 0.3f
        viewLifecycleOwner.lifecycleScope.launch {
            for (left in seconds downTo 1) {
                binding.pauseCountdown.text = left.toString()
                binding.pauseOpen.text = getString(R.string.open_app_in, left)
                delay(1000)
            }
            binding.pauseCountdown.text = ""
            binding.pauseOpen.text = getString(R.string.open_app)
            binding.pauseOpen.isEnabled = true
            binding.pauseOpen.alpha = 1f
        }
    }

    /** The circle grows for a slow breath in and shrinks for the breath out. */
    private fun startBreathing() {
        var breathingIn = true
        binding.pauseMessage.text = getString(R.string.breathe_in)
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
                    _binding?.pauseMessage?.text =
                        getString(if (breathingIn) R.string.breathe_in else R.string.breathe_out)
                }
            })
            start()
        }
    }

    override fun onDestroyView() {
        breathing?.cancel()
        breathing = null
        // Leaving any way other than Open (Cancel, back, home) cancels
        viewModel.cancelPausedApp()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val BREATH_MS = 4_000L
    }
}
