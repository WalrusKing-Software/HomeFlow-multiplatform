package org.homeflow.app.shared.platform

import android.app.Application
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference

/**
 * Bridges the Android-specific bits the auth actuals need (application [Context], the
 * current [FragmentActivity] for biometrics, and an Activity-Result launcher for the
 * AppAuth login intent) without leaking Android types into `commonMain`. Wired by
 * `MainActivity` in `:app:androidApp`.
 */
object AndroidAppContext {
    lateinit var application: Application
        private set

    private var activityRef: WeakReference<FragmentActivity>? = null

    /** Launches [intent] for result, suspending until the result Intent (or null on cancel). */
    var authLauncher: (suspend (Intent) -> Intent?)? = null

    val activity: FragmentActivity? get() = activityRef?.get()

    fun initApplication(app: Application) {
        application = app
    }

    fun setActivity(activity: FragmentActivity?) {
        activityRef = activity?.let { WeakReference(it) }
    }
}
