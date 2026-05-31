package com.erivaldogelson.recnow

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.core.view.WindowCompat

fun Activity.applyReadableSystemBars() {
    val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.setDecorFitsSystemWindows(window, true)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !isDarkMode
        isAppearanceLightNavigationBars = !isDarkMode
    }
    if (Build.VERSION.SDK_INT >= 21) {
        val color = if (isDarkMode) 0xFF1D1B20.toInt() else 0xFFFFFBFF.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
    }
}
