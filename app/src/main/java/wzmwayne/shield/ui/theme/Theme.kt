package wzmwayne.shield.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ShieldColorScheme = darkColorScheme(
    primary = ShieldWhite,
    onPrimary = ShieldBlack,
    primaryContainer = ShieldSurfaceVariant,
    onPrimaryContainer = ShieldWhite,
    secondary = ShieldTextSecondary,
    onSecondary = ShieldBlack,
    background = ShieldBlack,
    onBackground = ShieldWhite,
    surface = ShieldSurface,
    onSurface = ShieldWhite,
    surfaceVariant = ShieldSurfaceVariant,
    onSurfaceVariant = ShieldTextSecondary,
    outline = ShieldBorder,
    outlineVariant = ShieldBorder,
    error = ShieldWhite,
    onError = ShieldBlack,
)

@Composable
fun ShieldTheme(content: @Composable () -> Unit) {
    // 无论系统是深色还是浅色，锁盾始终使用暗色黑白主题
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = ShieldColorScheme,
        typography = ShieldTypography,
        content = content,
    )
}
