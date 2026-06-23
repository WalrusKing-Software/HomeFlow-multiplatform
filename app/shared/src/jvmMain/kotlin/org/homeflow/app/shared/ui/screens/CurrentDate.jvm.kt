package org.homeflow.app.shared.ui.screens

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate

/** Desktop: `java.time.LocalDate.now()` uses the system clock in the default zone. */
actual fun systemToday(): LocalDate =
    java.time.LocalDate
        .now()
        .toKotlinLocalDate()
