@file:Suppress("unused")

package io.legado.app.utils

import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar

/** 撤销条停留时长（毫秒），比 LENGTH_LONG 更宽裕，到时自动消失 */
const val UNDO_SNACKBAR_DURATION_MS = 8000

/**
 * Display the Snackbar with the [Snackbar.LENGTH_SHORT] duration.
 *
 * @param message the message text resource.
 */
@JvmName("snackbar2")
fun View.snackbar(
    @StringRes message: Int
) = Snackbar
    .make(this, message, Snackbar.LENGTH_SHORT)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_LONG] duration.
 *
 * @param message the message text resource.
 */
@JvmName("longSnackbar2")
fun View.longSnackbar(
    @StringRes message: Int
) = Snackbar
    .make(this, message, Snackbar.LENGTH_LONG)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_INDEFINITE] duration.
 *
 * @param message the message text resource.
 */
@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    @StringRes message: Int
) = Snackbar
    .make(this, message, Snackbar.LENGTH_INDEFINITE)
    .apply { show() }

/**
 * Display the Snackbar with the [Snackbar.LENGTH_SHORT] duration.
 *
 * @param message the message text.
 */
@JvmName("snackbar2")
fun View.snackbar(
    message: CharSequence
) = Snackbar
    .make(this, message, Snackbar.LENGTH_SHORT)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_LONG] duration.
 *
 * @param message the message text.
 */
@JvmName("longSnackbar2")
fun View.longSnackbar(
    message: CharSequence
) = Snackbar
    .make(this, message, Snackbar.LENGTH_LONG)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_INDEFINITE] duration.
 *
 * @param message the message text.
 */
@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    message: CharSequence
) = Snackbar
    .make(this, message, Snackbar.LENGTH_INDEFINITE)
    .apply { show() }

/**
 * Display the Snackbar with the [Snackbar.LENGTH_SHORT] duration.
 *
 * @param message the message text resource.
 */
@JvmName("snackbar2")
fun View.snackbar(
    message: Int,
    @StringRes actionText:
    Int, action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_SHORT)
    .setAction(actionText, action)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_LONG] duration.
 *
 * @param message the message text resource.
 */
@JvmName("longSnackbar2")
fun View.longSnackbar(
    @StringRes message: Int,
    @StringRes actionText: Int,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_LONG)
    .setAction(actionText, action)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_INDEFINITE] duration.
 *
 * @param message the message text resource.
 */
@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    @StringRes message: Int,
    @StringRes actionText: Int,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_INDEFINITE)
    .setAction(actionText, action)
    .apply { show() }

/**
 * Display the Snackbar with the [Snackbar.LENGTH_SHORT] duration.
 *
 * @param message the message text.
 */
@JvmName("snackbar2")
fun View.snackbar(
    message: CharSequence,
    actionText: CharSequence,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_SHORT)
    .setAction(actionText, action)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_LONG] duration.
 *
 * @param message the message text.
 */
@JvmName("longSnackbar2")
fun View.longSnackbar(
    message: CharSequence,
    actionText: CharSequence,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_LONG)
    .setAction(actionText, action)
    .apply { show() }

/**
 * Display Snackbar with the [Snackbar.LENGTH_INDEFINITE] duration.
 *
 * @param message the message text.
 */
@JvmName("indefiniteSnackbar2")
fun View.indefiniteSnackbar(
    message: CharSequence,
    actionText: CharSequence,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, Snackbar.LENGTH_INDEFINITE)
    .setAction(actionText, action)
    .apply { show() }

/**
 * 撤销用的 Snackbar：停留时间比 LENGTH_SHORT/LENGTH_LONG 更久，
 * 给用户足够时间点击"撤销"，到时自动消失（不会像 INDEFINITE 那样一直挂着）。
 */
fun View.undoSnackbar(
    message: CharSequence,
    actionText: CharSequence,
    action: (View) -> Unit
) = Snackbar
    .make(this, message, UNDO_SNACKBAR_DURATION_MS)
    .setAction(actionText, action)
    .apply { show() }