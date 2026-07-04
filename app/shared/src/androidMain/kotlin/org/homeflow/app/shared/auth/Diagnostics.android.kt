package org.homeflow.app.shared.auth

import android.util.Log

internal actual fun authDebugLog(message: String) {
    Log.d("HomeFlowAuth", message)
}
