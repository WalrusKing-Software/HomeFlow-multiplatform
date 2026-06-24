package org.homeflow.core.service

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * When a new cycle starts on [newCycleStart], any currently open cycle is auto-closed
 * with end_date = the day before. Shared by the server (CyclesService) and, in Phase 13,
 * LocalDataSource — so the auto-close rule has exactly one definition.
 */
fun autoCloseEndDate(newCycleStart: LocalDate): LocalDate = newCycleStart.minus(DatePeriod(days = 1))
