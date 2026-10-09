@file:Suppress("unused")

package io.legado.app.utils

import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.legado.app.uikit.facade.AppToast

/**
 * Snackbars without an action delegate to [AppToast] so all passive
 * notifications share one themed visual style (docs/ui-rewrite-plan.md 6.4).
 * Variants with an action keep using Snackbar because they require user
 * interaction; they will be replaced by the dialog/sheet facade in P4.
 */

@JvmName("snackbar2")
fun View.snackbar(
    @StringRes message: Int
) = snackbar(context.getString(message))

@JvmName("longSnackbar2")
fun View.longSnackbar(
    @StringRes message: Int
) = longSnackbar(context.getString(message))

@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    @StringRes message: Int
) = longSnackbar(context.getString(message))

@JvmName("snackbar2")
fun View.snackbar(
    message: CharSequence
) {
    AppToast.show(message)
}

@JvmName("longSnackbar2")
fun View.longSnackbar(
    message: CharSequence
) {
    AppToast.show(message, long = true)
}

@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    message: CharSequence
) {
    AppToast.show(message, long = true)
}

@JvmName("snackbar2")
fun View.snackbar(
    message: Int,
    @StringRes actionText:
    Int, action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_SHORT)
    .setAction(actionText, action)
    .apply { show() }

@JvmName("longSnackbar2")
fun View.longSnackbar(
    @StringRes message: Int,
    @StringRes actionText: Int,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_LONG)
    .setAction(actionText, action)
    .apply { show() }

@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    @StringRes message: Int,
    @StringRes actionText: Int,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_INDEFINITE)
    .setAction(actionText, action)
    .apply { show() }

@JvmName("snackbar2")
fun View.snackbar(
    message: CharSequence,
    actionText: CharSequence,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_SHORT)
    .setAction(actionText, action)
    .apply { show() }

@JvmName("longSnackbar2")
fun View.longSnackbar(
    message: CharSequence,
    actionText: CharSequence,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_LONG)
    .setAction(actionText, action)
    .apply { show() }

@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    message: CharSequence,
    actionText: CharSequence,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_INDEFINITE)
    .setAction(actionText, action)
    .apply { show() }
