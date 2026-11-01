package dev.quinnjr.android_drawer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.toImageBitmap

/** Icon size in the settings screen and app picker. */
val SETTINGS_ICON_SIZE = 40.dp

/** An app's icon at [size]; uses the pre-rendered bitmap when there is one. */
@Composable
fun AppIcon(app: LaunchableApp, modifier: Modifier = Modifier, size: Dp = SETTINGS_ICON_SIZE) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(app.key, app.icon, app.bitmap, sizePx) {
        app.bitmap ?: app.icon?.toImageBitmap(sizePx)
    }
    Box(modifier.size(size)) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
