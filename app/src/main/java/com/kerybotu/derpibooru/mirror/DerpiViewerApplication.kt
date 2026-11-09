package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.kerybotu.derpibooru.mirror.ui.RenderCrashExperiment
import java.util.Collections
import java.util.WeakHashMap

class DerpiViewerApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val fragmentManagers = Collections.newSetFromMap(WeakHashMap<FragmentManager, Boolean>())

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.initialize(this)
        CrashDiagnostics.installUncaughtExceptionHandler()
        CrashDiagnostics.recordEvent("Application", "render_experiment=${RenderCrashExperiment.from(this).id}")
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        CrashDiagnostics.activityEvent(activity, "resumed")
        CrashDiagnostics.recordEvent(activity.javaClass.simpleName, "global_palette_apply_started")
        PaletteManager.apply(activity)
        CrashDiagnostics.recordEvent(activity.javaClass.simpleName, "global_palette_apply_completed")
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        CrashDiagnostics.activityEvent(activity, if (state == null) "created" else "restored")
        (activity as? FragmentActivity)?.supportFragmentManager?.let { manager ->
            if (fragmentManagers.add(manager)) manager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }
    override fun onActivityStarted(activity: Activity) = CrashDiagnostics.activityEvent(activity, "started")
    override fun onActivityPaused(activity: Activity) = CrashDiagnostics.activityEvent(activity, "paused")
    override fun onActivityStopped(activity: Activity) = CrashDiagnostics.activityEvent(activity, "stopped")
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) =
        CrashDiagnostics.activityEvent(activity, "saving_state")
    override fun onActivityDestroyed(activity: Activity) = CrashDiagnostics.activityEvent(activity, "destroyed")

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, state: Bundle?) =
            CrashDiagnostics.fragmentEvent(f.requireActivity(), f.javaClass.simpleName, if (state == null) "created" else "restored")

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) =
            CrashDiagnostics.fragmentEvent(f.requireActivity(), f.javaClass.simpleName, "resumed")

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) =
            CrashDiagnostics.fragmentEvent(f.requireActivity(), f.javaClass.simpleName, "paused")

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) =
            f.activity?.let { CrashDiagnostics.fragmentEvent(it, f.javaClass.simpleName, "destroyed") } ?: Unit
    }
}
