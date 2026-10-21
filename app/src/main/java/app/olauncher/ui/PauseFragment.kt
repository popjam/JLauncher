package app.olauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentPauseBinding
import app.olauncher.helper.AppPause

/** Wellness screen shown before opening a chosen app from the launcher. See [PauseScreen]. */
class PauseFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentPauseBinding? = null
    private val binding get() = _binding!!
    private var screen: PauseScreen? = null

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
        val prefs = Prefs(requireContext())
        screen = PauseScreen(
            binding,
            app.appLabel,
            pauseSeconds = if (prefs.pauseAppsOn) prefs.pauseSeconds else null,
            askUseFor = AppPause.useForActive(prefs),
            onOpen = { minutes ->
                viewModel.openPausedApp(minutes)
                findNavController().popBackStack()
            },
            onCancel = { findNavController().popBackStack() },
        ).also { it.start() }
    }

    override fun onDestroyView() {
        screen?.stop()
        screen = null
        // Leaving any way other than Open (Cancel, back, home) cancels
        viewModel.cancelPausedApp()
        super.onDestroyView()
        _binding = null
    }
}
