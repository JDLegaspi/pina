package dev.shulhi.rex

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

data class PinaColors(
    val dark: Boolean,
    val groupedBackground: Color,
    val secondaryGroupedBackground: Color,
    val tertiaryGroupedBackground: Color,
    val systemBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val secondaryFill: Color,
    val segmentThumb: Color,
    val tint: Color,
    val orange: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
    val gray: Color,
    val claude: Color,
) {
    companion object {
        private val ClaudeAccent = Color(0xffd97757)
        val Light = PinaColors(
            dark = false,
            groupedBackground = Color(0xfff2f2f7),
            secondaryGroupedBackground = Color(0xffffffff),
            tertiaryGroupedBackground = Color(0xfff2f2f7),
            systemBackground = Color(0xffffffff),
            label = Color(0xff000000),
            secondaryLabel = Color(0xff3c3c43).copy(alpha = .6f),
            tertiaryLabel = Color(0xff3c3c43).copy(alpha = .3f),
            separator = Color(0xffc6c6c8),
            fill = Color(0xff787880).copy(alpha = .2f),
            secondaryFill = Color(0xff787880).copy(alpha = .16f),
            segmentThumb = Color(0xffffffff),
            tint = Color(0xff007aff),
            orange = Color(0xffff9500),
            red = Color(0xffff3b30),
            green = Color(0xff34c759),
            yellow = Color(0xffffcc00),
            gray = Color(0xff8e8e93),
            claude = ClaudeAccent,
        )
        val Dark = PinaColors(
            dark = true,
            groupedBackground = Color(0xff000000),
            secondaryGroupedBackground = Color(0xff1c1c1e),
            tertiaryGroupedBackground = Color(0xff2c2c2e),
            systemBackground = Color(0xff000000),
            label = Color(0xffffffff),
            secondaryLabel = Color(0xffebebf5).copy(alpha = .6f),
            tertiaryLabel = Color(0xffebebf5).copy(alpha = .3f),
            separator = Color(0xff38383a),
            fill = Color(0xff787880).copy(alpha = .36f),
            secondaryFill = Color(0xff787880).copy(alpha = .32f),
            segmentThumb = Color(0xff636366),
            tint = Color(0xff0a84ff),
            orange = Color(0xffff9f0a),
            red = Color(0xffff453a),
            green = Color(0xff30d158),
            yellow = Color(0xffffd60a),
            gray = Color(0xff8e8e93),
            claude = ClaudeAccent,
        )
    }
}

internal val LocalPinaColors = staticCompositionLocalOf { PinaColors.Light }

internal object PinaType {
    val family = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )
    private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
        letterSpacing = when(size) { in 15..20 -> (-0.02).em; in 21..40 -> (-0.01).em; else -> 0.em },
    )
    val largeTitle = style(34, 41, FontWeight.Bold)
    val title1 = style(28, 34)
    val title2 = style(22, 28)
    val title3 = style(20, 25, FontWeight.SemiBold)
    val headline = style(17, 22, FontWeight.SemiBold)
    val body = style(17, 22)
    val callout = style(16, 21)
    val subheadline = style(15, 20)
    val footnote = style(13, 18)
    val caption1 = style(12, 16)
    val caption2 = style(11, 13)

    val material = Typography(
        displayLarge = largeTitle, displayMedium = largeTitle, displaySmall = title1,
        headlineLarge = title1, headlineMedium = title2, headlineSmall = title3,
        titleLarge = title3, titleMedium = headline, titleSmall = subheadline.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = body, bodyMedium = subheadline, bodySmall = footnote,
        labelLarge = callout.copy(fontWeight = FontWeight.Medium), labelMedium = footnote.copy(fontWeight = FontWeight.Medium),
        labelSmall = caption1.copy(fontWeight = FontWeight.Medium),
    )
}

internal object PinaShapes {
    val section = RoundedCornerShape(10.dp)
    val sheet = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
}

internal fun PinaColors.materialScheme(): ColorScheme {
    val base = if(dark) darkColorScheme() else lightColorScheme()
    val tintContainer = tint.copy(alpha = .15f).compositeOver(secondaryGroupedBackground)
    val redContainer = red.copy(alpha = .15f).compositeOver(secondaryGroupedBackground)
    val opaqueFill = fill.compositeOver(groupedBackground)
    return base.copy(
        primary = tint, onPrimary = Color.White, primaryContainer = tintContainer, onPrimaryContainer = tint,
        secondary = tint, onSecondary = Color.White, secondaryContainer = tintContainer, onSecondaryContainer = tint,
        tertiary = tint, onTertiary = Color.White, tertiaryContainer = tintContainer, onTertiaryContainer = tint,
        background = groupedBackground, onBackground = label,
        surface = secondaryGroupedBackground, onSurface = label,
        surfaceVariant = opaqueFill, onSurfaceVariant = secondaryLabel,
        surfaceTint = Color.Transparent, surfaceDim = groupedBackground, surfaceBright = secondaryGroupedBackground,
        surfaceContainerLowest = systemBackground, surfaceContainerLow = secondaryGroupedBackground,
        surfaceContainer = secondaryGroupedBackground, surfaceContainerHigh = secondaryGroupedBackground,
        surfaceContainerHighest = tertiaryGroupedBackground,
        outline = separator, outlineVariant = separator,
        error = red, onError = Color.White, errorContainer = redContainer, onErrorContainer = red,
    )
}

@Composable internal fun PinaTheme(
    dark: Boolean,
    terminal: TerminalPalette = if(dark) TerminalPalette.Dark else TerminalPalette.Light,
    content: @Composable () -> Unit,
) {
    val colors = if(dark) PinaColors.Dark else PinaColors.Light
    val scheme = remember(colors) { colors.materialScheme() }
    CompositionLocalProvider(LocalPinaColors provides colors, LocalTerminalPalette provides terminal) {
        MaterialTheme(colorScheme = scheme, typography = PinaType.material, content = content)
    }
}
