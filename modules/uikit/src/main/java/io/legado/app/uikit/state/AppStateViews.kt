package io.legado.app.uikit.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.legado.app.uikit.token.AppSize
import io.legado.app.uikit.token.AppSpacing
import io.legado.app.uikit.token.AppTypeScale

/**
 * Compose rendering of [AppListState] (docs/ui-rewrite-plan.md 6.5.6).
 * Illustrations are injected via slots to keep this module asset-free.
 */
@Composable
fun AppLoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(AppSize.iconLarge))
        if (!message.isNullOrEmpty()) {
            Text(
                text = message,
                style = AppTypeScale.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AppSpacing.s12),
            )
        }
    }
}

@Composable
fun AppEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    illustration: (@Composable () -> Unit)? = null,
) {
    StateColumn(modifier, illustration, title, desc) { innerModifier ->
        if (!actionLabel.isNullOrEmpty() && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = innerModifier,
            ) {
                Text(text = actionLabel, style = AppTypeScale.body)
            }
        }
    }
}

@Composable
fun AppErrorState(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    illustration: (@Composable () -> Unit)? = null,
) {
    StateColumn(modifier, illustration, message, null) { innerModifier ->
        if (!retryLabel.isNullOrEmpty() && onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = innerModifier,
            ) {
                Text(text = retryLabel, style = AppTypeScale.body)
            }
        }
    }
}

@Composable
private fun StateColumn(
    modifier: Modifier,
    illustration: (@Composable () -> Unit)?,
    title: String,
    desc: String?,
    actionContent: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = AppSpacing.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        illustration?.invoke()
        Text(
            text = title,
            style = AppTypeScale.title,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!desc.isNullOrEmpty()) {
            Text(
                text = desc,
                style = AppTypeScale.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = AppSpacing.s8),
            )
        }
        actionContent(Modifier.padding(top = AppSpacing.s16))
    }
}

/**
 * Map an [AppListState] onto content; state views render when not Content.
 */
@Composable
fun <T> AppListStateView(
    state: AppListState<T>,
    modifier: Modifier = Modifier,
    emptyIllustration: (@Composable () -> Unit)? = null,
    errorIllustration: (@Composable () -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (val s = state) {
        is AppListState.Idle -> Unit
        is AppListState.Loading -> AppLoadingState(modifier = modifier)
        is AppListState.Empty -> AppEmptyState(
            title = s.title,
            desc = s.desc,
            actionLabel = s.actionLabel,
            onAction = onRetry,
            illustration = emptyIllustration,
            modifier = modifier,
        )
        is AppListState.Error -> AppErrorState(
            message = s.message,
            retryLabel = s.retryLabel,
            onRetry = onRetry,
            illustration = errorIllustration,
            modifier = modifier,
        )
        is AppListState.Content -> content(s.data)
    }
}
