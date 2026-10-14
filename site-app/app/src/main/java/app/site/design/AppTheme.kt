package app.site.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * SiteTheme (generated from tokens.json) plus a Material 3 scheme mapped from the same roles,
 * so stock Material widgets (date picker, sheets, checkboxes) wear our tokens too.
 *
 * Font: the system sans for now. Noto Sans ships as res/font once TTFs are added (the design
 * folder only has web fonts); Android already renders Devanagari with Noto.
 */
@Composable
fun SiteAppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkSiteColors else LightSiteColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.accentTint, onPrimaryContainer = c.onAccentTint,
        secondary = c.accent, onSecondary = c.onAccent,
        secondaryContainer = c.accentTint, onSecondaryContainer = c.onAccentTint,
        background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.textMuted,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surfaceRaised,
        surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceTrack,
        outline = c.borderInput, outlineVariant = c.border,
        error = c.danger, onError = c.onAccent, errorContainer = c.dangerTint, onErrorContainer = c.danger,
        inverseSurface = c.text, inverseOnSurface = c.bg, inversePrimary = c.accentTint,
    )
    SiteTheme(fontFamily = FontFamily.Default, dark = dark) {
        val t = SiteTheme.type
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                bodyLarge = t.base, bodyMedium = t.sm, bodySmall = t.xs,
                labelLarge = t.sm, labelMedium = t.xs, labelSmall = t.xs,
                titleLarge = t.lg, titleMedium = t.base, titleSmall = t.sm,
                headlineSmall = t.xl, headlineMedium = t.display,
            ),
            content = content,
        )
    }
}
