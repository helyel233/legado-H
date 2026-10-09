package io.legado.app.uikit.token

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

/**
 * Type scale tokens, 6 tiers (docs/ui-rewrite-plan.md 6.1).
 * Reading content font size is user-configurable and NOT bounded by this scale.
 * Weights: 400 / 500 / 700 only.
 */
object AppTypeScale {

    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val headline = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val display = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)

    /** Map onto Material3 Typography so all M3 components follow the app scale. */
    fun typography(): Typography = Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = headline,
        titleLarge = headline,
        titleMedium = title,
        titleSmall = bodyLarge,
        bodyLarge = bodyLarge,
        bodyMedium = body,
        bodySmall = caption,
        labelLarge = body,
        labelMedium = caption,
        labelSmall = caption,
    )
}
