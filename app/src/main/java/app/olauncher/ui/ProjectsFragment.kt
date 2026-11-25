package app.olauncher.ui

import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * Projects, opened from their row in the stats list: a list like the stats', a row per project with
 * its icon in its colour. Each opens its own page. Pulling down closes it.
 */
class ProjectsFragment : Fragment() {

    /** A project: its name, icon and colour, and where it goes */
    private class Project(val name: Int, val icon: String, val color: Int, val open: Int)

    private val projects = listOf(
        Project(R.string.newsletter, StatIcons.NEWSPAPER, NEWSLETTER_COLOR, R.id.action_projectsFragment_to_newsletterFragment),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val ink = context.getColorFromAttr(R.attr.primaryColor)
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        projects.forEach { project -> rows.addView(row(getString(project.name), project.icon, project.color) { findNavController().navigate(project.open) }) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 48.dpToPx(), 0, 48.dpToPx())
            addView(TextView(context, null, 0, R.style.TextLarge).apply {
                text = getString(R.string.projects)
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.size_large))
                setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(24.dpToPx(), 0, 24.dpToPx(), 16.dpToPx())
            })
            addView(rows)
        }
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(content)
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    /** A row as in the stats list: the icon in its colour and the name */
    private fun row(name: String, icon: String, color: Int, onTap: () -> Unit): View {
        val context = requireContext()
        val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dpToPx(), 13.dpToPx(), 24.dpToPx(), 13.dpToPx())
            setBackgroundResource(ripple)
            addView(View(context).apply { background = StatIcons.drawable(icon, color, 22.dpToPx()) },
                LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()).apply { marginEnd = 18.dpToPx() })
            addView(TextView(context, null, 0, R.style.TextLarge).apply {
                text = name
                textSize = Style.TEXT_TITLE
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onTap() }
        }
    }

    companion object {
        /** The newsletter's newspaper */
        val NEWSLETTER_COLOR = Style.Palette.CORAL
    }
}
