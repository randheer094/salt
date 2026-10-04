package salt.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spacing scale (4dp grid). Screens use these, never raw dp values. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Meaning-carrying colors that Material's scheme doesn't have. */
@Immutable
class SaltColors(val success: Color, val warning: Color, val info: Color, val danger: Color, val muted: Color)

/** What a badge or notice is communicating. */
enum class Tone { Neutral, Success, Warning, Danger, Info }

private val LightSalt = SaltColors(Color(0xFF1B7F3B), Color(0xFF8A5A00), Color(0xFF0B63CE), Color(0xFFBA1A1A), Color(0xFF5F5F6A))
private val DarkSalt = SaltColors(Color(0xFF6FD79A), Color(0xFFF0C050), Color(0xFF8AB4FF), Color(0xFFFFB4AB), Color(0xFFC7C5D0))

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4355B9), onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF), onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9), onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D), onTertiary = Color.White,
    background = Color(0xFFFEFBFF), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC), onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC7C5D0),
    error = Color(0xFFBA1A1A), onError = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF1EDF5), surfaceContainerHigh = Color(0xFFEBE7EF), surfaceContainerHighest = Color(0xFFE5E1E9),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFBBC3FF), onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0), onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD), onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559), onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6BAD7), onTertiary = Color(0xFF44263D),
    background = Color(0xFF131318), onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF131318), onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A), outlineVariant = Color(0xFF46464F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    surfaceContainerLowest = Color(0xFF0E0E13), surfaceContainerLow = Color(0xFF1B1B20),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF2A292F), surfaceContainerHighest = Color(0xFF35343A),
)

/** Material 3 type scale, tightened one step for a dense desktop tool. */
private val SaltTypography = Typography().let {
    fun TextStyle.at(size: Int, line: Int, weight: FontWeight? = null) =
        copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight ?: fontWeight)
    it.copy(
        titleLarge = it.titleLarge.at(20, 28),
        titleMedium = it.titleMedium.at(15, 22, FontWeight.Medium),
        titleSmall = it.titleSmall.at(13, 20, FontWeight.Medium),
        bodyMedium = it.bodyMedium.at(13, 20),
        bodySmall = it.bodySmall.at(12, 16),
        labelLarge = it.labelLarge.at(13, 20, FontWeight.Medium),
        labelMedium = it.labelMedium.at(12, 16, FontWeight.Medium),
        labelSmall = it.labelSmall.at(11, 16, FontWeight.Medium),
    )
}

private val SaltShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

private val LocalSaltColors = staticCompositionLocalOf { LightSalt }

object SaltTheme {
    val colors: SaltColors @Composable get() = LocalSaltColors.current
    val scheme: ColorScheme @Composable get() = MaterialTheme.colorScheme

    @Composable
    fun toneColor(tone: Tone): Color = when (tone) {
        Tone.Neutral -> colors.muted
        Tone.Success -> colors.success
        Tone.Warning -> colors.warning
        Tone.Danger -> colors.danger
        Tone.Info -> colors.info
    }
}

/** App theme and full-window background; follows the system light/dark preference. */
@Composable
fun SaltTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSaltColors provides if (dark) DarkSalt else LightSalt) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = SaltTypography, shapes = SaltShapes) {
            Surface(Modifier.fillMaxSize(), content = content)
        }
    }
}
