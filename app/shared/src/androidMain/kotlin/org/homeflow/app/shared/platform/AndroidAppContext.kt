package org.homeflow.app.shared.platform

import android.app.Application
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference

/**
 * Bridges the Android-specific bits the auth actuals need (application [Context], the
 * current [FragmentActivity] for biometrics, and Activity-Result launchers for the
 * AppAuth login intent and the SAF export document picker) without leaking Android types
 * into `commonMain`. Wired by `MainActivity` in `:app:androidApp`.
 */
object AndroidAppContext {
    lateinit var application: Application
        private set

    private var activityRef: WeakReference<FragmentActivity>? = null

    /** Launches [intent] for result, suspending until the result Intent (or null on cancel). */
    var authLauncher: (suspend (Intent) -> Intent?)? = null

    /**
     * Launches an [Intent.ACTION_CREATE_DOCUMENT] intent and returns the chosen [android.net.Uri],
     * or null if the user cancelled. Registered by [MainActivity] alongside [authLauncher].
     */
    var exportLauncher: (suspend (Intent) -> android.net.Uri?)? = null

    val activity: FragmentActivity? get() = activityRef?.get()

    fun initApplication(app: Application) {
        application = app
    }

    fun setActivity(activity: FragmentActivity?) {
        activityRef = activity?.let { WeakReference(it) }
    }
}
