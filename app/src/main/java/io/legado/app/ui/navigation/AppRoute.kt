package io.legado.app.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.config.ConfigActivity

/**
 * Central route table for the main frame (docs/ui-rewrite-plan.md 4.3/8.2).
 * P1 registers the destinations reachable from the main tabs; P4 will
 * migrate the remaining activity entries here so navigation has one
 * discoverable source of truth.
 */
sealed class AppRoute(private val clazz: Class<out AppCompatActivity>) {

    fun intent(context: Context): Intent = Intent(context, clazz)

    fun start(context: Context) {
        context.startActivity(intent(context))
    }

    /** Route for the read record page (standalone host of the old tab). */
    data object ReadRecord : AppRoute(ReadRecordActivity::class.java)

    /** Global search. */
    data object Search : AppRoute(SearchActivity::class.java)

    /** Config center. */
    data object Config : AppRoute(ConfigActivity::class.java)
}
