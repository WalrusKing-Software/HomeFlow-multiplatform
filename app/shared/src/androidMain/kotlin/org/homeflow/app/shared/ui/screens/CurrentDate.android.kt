package org.homeflow.app.shared.ui.screens

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate

/** Android (minSdk 36 → java.time is native): system clock in the device's default zone. */
actual fun systemToday(): LocalDate =
    java.time.LocalDate
        .now()
        .toKotlinLocalDate()
