package ru.pcinventory.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF15803D),
    secondary = Color(0xFF16A34A),
    background = Color(0xFFF6F8F6)
)

/** Единая зелёная светлая тема приложения. */
@Composable
fun InventoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content
    )
}
