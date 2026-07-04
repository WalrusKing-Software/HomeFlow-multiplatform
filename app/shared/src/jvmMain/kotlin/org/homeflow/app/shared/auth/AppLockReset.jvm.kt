package org.homeflow.app.shared.auth

import com.github.javakeyring.Keyring

actual fun resetAppLockGateEnrollment() {
    runCatching {
        Keyring.create().deletePassword("org.homeflow.desktop", "app_lock_passphrase")
    }
}
