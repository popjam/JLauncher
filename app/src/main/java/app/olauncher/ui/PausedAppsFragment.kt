package app.olauncher.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentPausedAppsBinding
import app.olauncher.helper.AppPause
import app.olauncher.helper.Lockdown
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getAppsList
import app.olauncher.helper.getColorFromAttr
import kotlinx.coroutines.launch

/**
 * Every app with an On/Off switch: for the wellness pause, or, with [MODE] [BLOCKED], for being
 * blocked during lockdown. The apps that are on are listed first.
 */
class PausedAppsFragment : Fragment() {

    companion object {
        const val MODE = "mode"
        const val BLOCKED = "blocked"
    }

    private var _binding: FragmentPausedAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private val blocking get() = arguments?.getString(MODE) == BLOCKED
    private val gate by lazy { LockdownGate(this) }

    /** Apps blocked since this page opened, which can be unblocked again freely */
    private val blockedHere = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPausedAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        if (blocking) {
            binding.pausedAppsTitle.setText(R.string.blocked_apps)
            binding.pausedAppsDescription.setText(R.string.blocked_apps_description)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val chosen = chosen()
            val apps = getAppsList(requireContext(), prefs, includeRegularApps = true, includeHiddenApps = true)
                .filterIsInstance<AppModel.App>()
                // Both work per app, so an app in two profiles is listed once
                .distinctBy { it.appPackage }
                .filter { !blocking || Lockdown.canBlock(requireContext(), it.appPackage) }
                // Sorting is stable, so each group stays alphabetical
                .sortedBy { it.appPackage !in chosen }
            apps.forEach { addRow(it) }
        }
    }

    private fun chosen() = if (blocking) prefs.lockdownBlockedApps else prefs.pausedApps

    private fun addRow(app: AppModel.App) {
        val context = requireContext()
        val row = FrameLayout(context)
        val label = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextSmall)
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            text = app.appLabel
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(8.dpToPx(), 8.dpToPx(), 0, 8.dpToPx())
        }
        val status = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextSmallBold)
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            setPadding(24.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        fun render() {
            status.text = getString(if (app.appPackage in chosen()) R.string.on else R.string.off)
        }
        render()
        row.setOnClickListener {
            val on = app.appPackage in chosen()
            if (!blocking) {
                AppPause.setPaused(prefs, app.appPackage, !on)
                return@setOnClickListener render()
            }
            // Blocking more is always allowed; unblocking loosens lockdown, unless it was blocked by mistake just now
            val unblock = {
                prefs.lockdownBlockedApps = prefs.lockdownBlockedApps - app.appPackage
                blockedHere -= app.appPackage
                if (isAdded) render()
            }
            when {
                !on -> {
                    prefs.lockdownBlockedApps = prefs.lockdownBlockedApps + app.appPackage
                    blockedHere += app.appPackage
                    render()
                }
                app.appPackage in blockedHere -> unblock()
                else -> gate.loosen(unblock)
            }
        }
        row.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 72.dpToPx() })
        row.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = 4.dpToPx() })
        binding.pausedAppsList.addView(row)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
