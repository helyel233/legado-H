package io.legado.app.uikit.state

/**
 * Unified list state contract (docs/ui-rewrite-plan.md 6.3).
 * Replaces the 43 layouts that embed their own empty/loading/error views.
 */
sealed interface AppListState<out T> {

    data object Idle : AppListState<Nothing>

    data object Loading : AppListState<Nothing>

    data class Content<T>(
        val data: T,
        val hasMore: Boolean = false,
    ) : AppListState<T>

    data class Empty(
        val title: String,
        val desc: String? = null,
        val actionLabel: String? = null,
    ) : AppListState<Nothing>

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val retryLabel: String? = null,
    ) : AppListState<Nothing>
}
