package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kerybotu.derpibooru.mirror.ui.GlassSwitch
import com.kerybotu.derpibooru.mirror.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassPerformanceFallbackRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun settingsExposeGlassFallbackAndPersistToggle() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("app_settings_prefs", 0)
        val hadValue = prefs.contains("glass_performance_fallback")
        val savedValue = prefs.getBoolean("glass_performance_fallback", false)
        var expectedValue = AppSettings.isGlassPerformanceFallbackEnabled(context)
        var page: SettingsActivity? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                prefs.edit().remove("glass_performance_fallback").commit()
                expectedValue = false
                assertEquals(expectedValue, AppSettings.isGlassPerformanceFallbackEnabled(context))
            }
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (page == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    page = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SettingsActivity>().firstOrNull()
                }
                if (page == null) Thread.sleep(50)
            }
            val activity = page ?: error("SettingsActivity did not resume")
            val field = SettingsActivity::class.java.getDeclaredField("switchGlassPerformanceFallback").apply { isAccessible = true }
            val toggle = field.get(activity) as GlassSwitch
            assertEquals(expectedValue, toggle.isChecked)
            instrumentation.runOnMainSync { toggle.performClick() }
            expectedValue = !expectedValue
            assertEquals(expectedValue, AppSettings.isGlassPerformanceFallbackEnabled(context))
            instrumentation.runOnMainSync { toggle.performClick() }
            expectedValue = !expectedValue
            assertEquals(expectedValue, AppSettings.isGlassPerformanceFallbackEnabled(context))
        } finally {
            instrumentation.runOnMainSync { page?.finish() }
            val edit = prefs.edit()
            if (hadValue) edit.putBoolean("glass_performance_fallback", savedValue) else edit.remove("glass_performance_fallback")
            edit.commit()
        }
    }
}
