package org.homeflow.app.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.core.ErrorCode

/**
 * The render state of one screen's data: in flight, loaded, or a typed failure. Screens
 * map an [ApiResult] into this via [toLoadable] and hand it to the [Loadable] composable so
 * Loading / Loaded / Error chrome is uniform everywhere (`ARCHITECTURE-client.md`).
 */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Loaded<T>(
        val value: T,
    ) : Loadable<T>

    data class Error(
        val code: ErrorCode,
        val message: String,
    ) : Loadable<Nothing>
}

fun <T> ApiResult<T>.toLoadable(): Loadable<T> =
    when (this) {
        is ApiResult.Success -> Loadable.Loaded(value)
        is ApiResult.Failure -> Loadable.Error(code, message)
    }

/** Renders [state]: a centered spinner while loading, a retryable message on error, else [content]. */
@Composable
fun <T> Loadable(
    state: Loadable<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is Loadable.Loading ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        is Loadable.Error ->
            Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Couldn't load this", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onRetry != null) Button(onClick = onRetry) { Text("Try again") }
                }
            }

        is Loadable.Loaded -> content(state.value)
    }
}
