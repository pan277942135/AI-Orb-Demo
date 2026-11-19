package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OrbDarkColorScheme = darkColorScheme(
  primary = LuminousCyan,
  onPrimary = DeepInkOnAccent,
  primaryContainer = DeepGlassCard,
  onPrimaryContainer = SoftAqua,
  secondary = ChampagneGold,
  onSecondary = DeepInkOnAccent,
  tertiary = JadeEmerald,
  onTertiary = DeepInkOnAccent,
  background = AbyssEmerald,
  onBackground = IceTextPrimary,
  surface = DeepGlassSurface,
  onSurface = IceTextPrimary,
  surfaceVariant = DeepGlassCard,
  onSurfaceVariant = IceTextSecondary,
  outline = GlassStroke,
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = OrbDarkColorScheme,
    typography = Typography,
    content = content,
  )
}
