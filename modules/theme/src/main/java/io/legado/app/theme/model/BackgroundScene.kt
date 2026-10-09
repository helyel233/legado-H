package io.legado.app.theme.model

/**
 * Background image scenes (docs/ui-rewrite-plan.md 5.3).
 * Unifies the 3 legacy slots + reader bgType under one enum.
 */
enum class BackgroundScene {
    MAIN,
    BOOK_INFO,
    PANEL,
    READER,
}

/**
 * Where the background value points to.
 */
enum class BackgroundSource {
    /** Solid color stored in [BackgroundSpec.path] (reader bgType 0). */
    COLOR,

    /** Relative path inside app assets (reader bgType 1). */
    ASSET,

    /** Absolute file path (legacy slots and reader bgType 2). */
    FILE,
}

/**
 * A resolved background spec (read view over legacy keys).
 * [path] is non-blank when a background is actually configured;
 * its meaning depends on [source].
 */
data class BackgroundSpec(
    val path: String,
    val source: BackgroundSource = BackgroundSource.FILE,
    val crop: String? = null,
    val blur: Int? = null,
    val scaleType: String? = null,
)
