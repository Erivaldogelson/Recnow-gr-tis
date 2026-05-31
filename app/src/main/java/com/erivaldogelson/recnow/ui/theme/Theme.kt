package com.erivaldogelson.recnow.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFFB00000),
    secondary = Color(0xFF775651),
    tertiary = Color(0xFF725B2E),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    error = Color(0xFFB00000)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    secondary = Color(0xFFE7BDB6),
    tertiary = Color(0xFFE0C28C),
    background = Color(0xFF1D1B20),
    surface = Color(0xFF1D1B20),
    error = Color(0xFFFFB4AB)
)

@Composable
fun RecnowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (Build.VERSION.SDK_INT >= 31) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
