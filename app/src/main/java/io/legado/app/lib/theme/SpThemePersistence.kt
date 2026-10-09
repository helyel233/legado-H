package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.theme.persistence.ThemePersistence
import io.legado.app.utils.defaultSharedPreferences

/**
 * defaultSharedPreferences-backed [ThemePersistence] injected into the
 * theme module's applier/keys logic.
 */
class SpThemePersistence(context: Context) : ThemePersistence {

    private val prefs = context.applicationContext.defaultSharedPreferences

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun all(): Map<String, Any?> = prefs.all

    override fun commit(): Boolean = prefs.edit().commit()
}
