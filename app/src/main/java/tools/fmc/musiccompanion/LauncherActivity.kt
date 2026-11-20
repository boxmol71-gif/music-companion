package tools.fmc.musiccompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** The explicit launcher entry point. It opens the real UI and leaves no extra task behind. */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
