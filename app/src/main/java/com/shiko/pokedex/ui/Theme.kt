package com.shiko.pokedex.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PokedexRed = Color(0xFFE3350D)
val PokedexRedDark = Color(0xFFB22A0A)
val PokedexBlue = Color(0xFF2A75BB)
val PokedexYellow = Color(0xFFFFCB05)

private val PokedexColorScheme = darkColorScheme(
    primary = PokedexRed,
    onPrimary = Color.White,
    secondary = PokedexBlue,
    onSecondary = Color.White,
    tertiary = PokedexYellow,
    background = Color(0xFF141414),
    surface = Color(0xFF1E1E1E)
)

@Composable
fun ShikosPokedexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PokedexColorScheme,
        content = content
    )
}
