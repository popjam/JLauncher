package app.olauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The newsletter's entry in the app list: opens its page in the launcher and goes. It's in the
 * manifest after the voice note, so opening JLauncher by its package still opens the voice note.
 */
class NewsletterActivity : Activity() {

    companion object {
        const val EXTRA_OPEN = "openNewsletter"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        overridePendingTransition(0, 0)
    }
}
