package io.legado.app.help.config.pkg

import android.content.Context

/**
 * Isolated crash/process-death draft channel (`draft.param.*` inside a dedicated
 * SharedPreferences file) so unsaved editor changes never pollute runtime prefs or backups.
 */
internal object UnifiedPackageDraftStore {

    private const val PREF_NAME = "unified_package_draft"
    private const val KEY_PACKAGE_ID = "draft.param.packageId"
    private const val KEY_PACKAGE_NAME = "draft.param.packageName"
    private const val KEY_IS_NIGHT = "draft.param.isNight"
    private const val KEY_PAYLOAD_JSON = "draft.param.payloadJson"
    private const val KEY_UPDATED_AT = "draft.param.updatedAt"
    private const val MAX_DRAFT_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    data class ParamDraftRecord(
        val packageId: String,
        val packageName: String,
        val isNight: Boolean,
        val payload: ParamPackagePayload,
        val updatedAt: Long
    )

    fun saveParamDraft(
        context: Context,
        packageId: String,
        packageName: String,
        isNight: Boolean,
        payload: ParamPackagePayload
    ) {
        if (packageId.isBlank()) return
        val encoded = UnifiedPackageCodec.encodeParamPayload(payload)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE_ID, packageId)
            .putString(KEY_PACKAGE_NAME, packageName)
            .putBoolean(KEY_IS_NIGHT, isNight)
            .putString(KEY_PAYLOAD_JSON, encoded)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun loadParamDraft(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): ParamDraftRecord? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val packageId = prefs.getString(KEY_PACKAGE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (updatedAt <= 0L || nowMs - updatedAt > MAX_DRAFT_AGE_MS) {
            clearParamDraft(context)
            return null
        }
        val payloadJson = prefs.getString(KEY_PAYLOAD_JSON, null) ?: return null
        val payload = UnifiedPackageCodec.decodeParamPayload(payloadJson) ?: return null
        val packageName = prefs.getString(KEY_PACKAGE_NAME, null).orEmpty()
        val isNight = prefs.getBoolean(KEY_IS_NIGHT, false)
        return ParamDraftRecord(
            packageId = packageId,
            packageName = packageName,
            isNight = isNight,
            payload = payload,
            updatedAt = updatedAt
        )
    }

    fun clearParamDraft(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PACKAGE_ID)
            .remove(KEY_PACKAGE_NAME)
            .remove(KEY_IS_NIGHT)
            .remove(KEY_PAYLOAD_JSON)
            .remove(KEY_UPDATED_AT)
            .apply()
    }
}
