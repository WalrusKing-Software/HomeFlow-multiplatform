package org.homeflow.app.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * A ‹ date › stepper that moves [date] one day at a time and reports changes via [onDateChange].
 * The arrows disable at the optional [minDate]/[maxDate] bounds — e.g. a cycle date can't be
 * stepped into the future. Shared by the Day view's navigation and the cycle start/close forms.
 */
@Composable
fun DateStepperField(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onDateChange(date.minus(1, DateTimeUnit.DAY)) },
            enabled = minDate == null || date > minDate,
        ) { Text("‹ Prev") }
        Text(
            date.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = { onDateChange(date.plus(1, DateTimeUnit.DAY)) },
            enabled = maxDate == null || date < maxDate,
        ) { Text("Next ›") }
    }
}
