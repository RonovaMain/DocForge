package dev.ronova.docforge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import dev.ronova.docforge.databinding.ActivitySettingsBinding

/** Настройки отображения и раздел «О приложении». */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        val settings = AppSettings(this)

        binding.toggleNight.check(
            when (settings.nightMode) {
                1 -> R.id.btnNightLight
                2 -> R.id.btnNightDark
                else -> R.id.btnNightAuto
            },
        )
        binding.toggleNight.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                settings.nightMode = when (checkedId) {
                    R.id.btnNightLight -> 1
                    R.id.btnNightDark -> 2
                    else -> 0
                }
            }
        }

        val zoom = (Math.round(settings.textZoom / 10f) * 10).coerceIn(80, 200)
        binding.zoomSlider.value = zoom.toFloat()
        binding.zoomLabel.text = getString(R.string.settings_zoom, zoom)
        binding.zoomSlider.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            settings.textZoom = percent
            binding.zoomLabel.text = getString(R.string.settings_zoom, percent)
        }

        binding.switchKeepOn.isChecked = settings.keepScreenOn
        binding.switchKeepOn.setOnCheckedChangeListener { _, checked -> settings.keepScreenOn = checked }

        binding.aboutVersion.text = getString(R.string.about_version, versionName())
        binding.btnGithub.setOnClickListener { openGithub() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

    private fun openGithub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, getString(R.string.err_no_browser), Snackbar.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** Профиль автора на GitHub. */
        const val GITHUB_URL = "https://github.com/RonovaMain"
    }
}
