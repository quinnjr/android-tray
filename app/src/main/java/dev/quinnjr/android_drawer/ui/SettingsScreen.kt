package dev.quinnjr.android_drawer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.overlay.MAX_HANDLE_HEIGHT_DP
import dev.quinnjr.android_drawer.overlay.MIN_HANDLE_HEIGHT_DP
import dev.quinnjr.android_drawer.settings.DrawerSettings

val HANDLE_COLORS = listOf(0xFFB0B0B0, 0xFFFFFFFF, 0xFF4285F4, 0xFF34A853, 0xFFFBBC04, 0xFFEA4335)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: DrawerSettings,
    chosenApps: List<LaunchableApp>,
    canDrawOverlays: Boolean,
    notificationsGranted: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantNotifications: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onEditApps: () -> Unit,
    onMoveApp: (from: Int, to: Int) -> Unit,
    onHeightChange: (Int) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onColorChange: (Long) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Edge Drawer") }) }) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!canDrawOverlays) {
                item {
                    PermissionCard(
                        "Allow display over other apps",
                        "Needed to show the edge handle on top of every app.",
                        onGrantOverlay,
                    )
                }
            }
            if (!notificationsGranted) {
                item {
                    PermissionCard(
                        "Allow notifications",
                        "Android shows a small notification while the handle is running.",
                        onGrantNotifications,
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canDrawOverlays) { onEnabledChange(!settings.enabled) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show edge handle", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Swipe it to open the drawer. Long-press and drag to move it.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = settings.enabled && canDrawOverlays,
                        enabled = canDrawOverlays,
                        onCheckedChange = onEnabledChange,
                    )
                }
            }
            item { SectionTitle("Drawer apps") }
            if (chosenApps.isEmpty()) {
                item {
                    Text(
                        "No apps chosen yet.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            itemsIndexed(chosenApps, key = { _, app -> app.key.encode() }) { index, app ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app)
                    Text(app.label, Modifier.weight(1f).padding(start = 16.dp))
                    IconButton(onClick = { onMoveApp(index, index - 1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMoveApp(index, index + 1) }, enabled = index < chosenApps.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
            }
            item {
                OutlinedButton(onClick = onEditApps, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Edit apps")
                }
            }
            item { SectionTitle("Handle") }
            item {
                LabeledSlider(
                    "Height",
                    value = settings.handleHeightDp.toFloat(),
                    range = MIN_HANDLE_HEIGHT_DP.toFloat()..MAX_HANDLE_HEIGHT_DP.toFloat(),
                    onChange = { onHeightChange(it.toInt()) },
                )
            }
            item {
                LabeledSlider(
                    "Opacity",
                    value = settings.handleAlpha,
                    range = 0.2f..1f,
                    onChange = onAlphaChange,
                )
            }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HANDLE_COLORS.forEach { color ->
                        val selected = color == settings.handleColor
                        Spacer(
                            Modifier
                                .size(36.dp)
                                .background(Color(color), CircleShape)
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                )
                                .clickable { onColorChange(color) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onGrant) { Text("Allow") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun AppIcon(app: LaunchableApp) {
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap = remember(app.key, sizePx) { app.icon?.toBitmap(sizePx, sizePx)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Spacer(Modifier.size(40.dp))
    }
}
