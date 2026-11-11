package com.kerybotu.derpibooru.mirror.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.kerybotu.derpibooru.mirror.PaletteManager

/** Standalone host for the same UI 2.0 profile used by the My navigation tab. */
class ProfileActivity : AppCompatActivity() {
    companion object { const val EXTRA_USER_ID = "user_id" }
    private lateinit var profile: EmbeddedProfileView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val userId = intent.getLongExtra(EXTRA_USER_ID, -1).takeIf { it > 0 }
        // An invalid explicit target must never open the signed-in user's profile.
        if (intent.hasExtra(EXTRA_USER_ID) && userId == null) { finish(); return }
        profile = EmbeddedProfileView(this, requestedUserId = userId, onBack = { finish() })
        setContentView(profile)
        AdaptiveNavigationShell.install(this, profile, AdaptiveNavigationShell.PROFILE)
        ViewCompat.requestApplyInsets(profile)
    }

    override fun onResume() {
        super.onResume()
        if (!::profile.isInitialized) return
        PaletteManager.apply(this)
        profile.applyPalette()
        profile.setActive(true)
    }

    override fun onPause() {
        if (::profile.isInitialized) profile.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (::profile.isInitialized) profile.dispose()
        super.onDestroy()
    }
}
