package io.legado.app.theme.persistence

/**
 * Key-value persistence the theme module writes through, injected by the app
 * (backed by defaultSharedPreferences). Keeps modules/theme free of Android
 * context dependencies while owning the key structure.
 */
interface ThemePersistence {

    fun getString(key: String): String?

    /** Mirrors SharedPreferences.putString(key, null) semantics used by legacy writes. */
    fun putString(key: String, value: String?)

    fun getInt(key: String, defaultValue: Int): Int

    fun putInt(key: String, value: Int)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun remove(key: String)

    fun contains(key: String): Boolean

    fun all(): Map<String, Any?>

    /** Synchronous flush; used only where the legacy code committed explicitly. */
    fun commit(): Boolean = true

    /** Legacy migration may encounter float/long values from very old installs. */
    fun putFloat(key: String, value: Float) = putString(key, value.toString())

    fun putLong(key: String, value: Long) = putString(key, value.toString())
}
