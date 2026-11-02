package dev.quinnjr.android_drawer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.quinnjr.android_drawer.apps.LaunchableApp

/** Icon size in the settings screen and app picker. */
val SETTINGS_ICON_SIZE = 40.dp

/**
 * An app's icon at [size], from its pre-rendered bitmap (icons are drawn off the main thread,
 * when the app is loaded: see withBitmap()); nothing if it has none.
 */
@Composable
fun AppIcon(app: LaunchableApp, modifier: Modifier = Modifier, size: Dp = SETTINGS_ICON_SIZE) {
    Box(modifier.size(size)) {
        app.bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
