package org.homeflow.app.shared.ui.screens

import kotlinx.datetime.LocalDate

/**
 * Today's date in the device's local time zone — the anchor for the dashboard and day
 * stepper.
 *
 * Platform-sourced (`expect`/`actual`) deliberately: Compose pulls kotlinx-datetime 0.7.x
 * onto the client runtime, where `kotlinx.datetime.Clock`/`Instant` moved to `kotlin.time`
 * and the old classes are gone — calling `Clock.System.todayIn(...)` `NoClassDefFoundError`s
 * at first render. The actuals read `java.time` (a stable JDK type) instead.
 */
expect fun systemToday(): LocalDate
