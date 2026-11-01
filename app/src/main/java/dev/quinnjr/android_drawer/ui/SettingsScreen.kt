package dev.quinnjr.android_drawer.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.overlay.MAX_HANDLE_HEIGHT_DP
import dev.quinnjr.android_drawer.overlay.MIN_HANDLE_HEIGHT_DP
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.HANDLE_ALPHA_RANGE

val HANDLE_COLORS = listOf(0xFFB0B0B0, 0xFFFFFFFF, 0xFF4285F4, 0xFF34A853, 0xFFFBBC04, 0xFFEA4335)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: DrawerSettings,
    /** Null while the app list is still loading. */
    chosenApps: PinnedApps?,
    canDrawOverlays: Boolean,
    notificationsGranted: Boolean,
    usageAccessGranted: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantNotifications: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onEditApps: () -> Unit,
    onUnpinUnavailable: (Set<AppKey>) -> Unit,
    onMoveApp: (key: AppKey, delta: Int) -> Unit,
    onIconsLockedChange: (Boolean) -> Unit,
    onHeightChange: (Int) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onColorChange: (Long) -> Unit,
) {
    val shown = chosenApps?.apps.orEmpty()
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
            if (!usageAccessGranted) {
                item {
                    PermissionCard(
                        "Allow usage access to show recent apps",
                        "Optional. The drawer lists your ${AppCatalog.RECENT_LIMIT} most recently used apps above your pinned ones.",
                        onGrantUsageAccess,
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
            if (chosenApps == null) {
                item { CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp)) }
            } else if (chosenApps.keys.isEmpty()) {
                item {
                    Text(
                        "No apps chosen yet.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // Pinned apps that can't be shown now (e.g. a paused work profile): say so, and let
            // them be unpinned, since the picker can't show them either.
            if (chosenApps?.failed == true) {
                item {
                    Text(
                        "Couldn't load your pinned apps right now.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // From the same load as the list; apps that merely failed to load never count.
            val unavailable = chosenApps?.unavailable.orEmpty()
            if (unavailable.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            if (unavailable.size == 1) {
                                "1 pinned app isn't available right now (e.g. its work profile is paused)."
                            } else {
                                "${unavailable.size} pinned apps aren't available right now (e.g. their work profile is paused)."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { onUnpinUnavailable(unavailable) }) { Text("Unpin unavailable apps") }
                    }
                }
            }
            itemsIndexed(shown, key = { _, app -> app.key.encode() }) { index, app ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app)
                    Text(app.label, Modifier.weight(1f).padding(start = 16.dp))
                    IconButton(onClick = { onMoveApp(app.key, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMoveApp(app.key, +1) }, enabled = index < shown.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
            }
            item {
                OutlinedButton(onClick = onEditApps, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Edit apps")
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onIconsLockedChange(!settings.iconsLocked) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock drawer icons", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Stops long-press and drag from rearranging apps in the open drawer.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = settings.iconsLocked, onCheckedChange = onIconsLockedChange)
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
                    range = HANDLE_ALPHA_RANGE,
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
                                    if (selected) { 3.dp } else { 1.dp },
                                    if (selected) { MaterialTheme.colorScheme.primary } else { MaterialTheme.colorScheme.outline },
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
    // The thumb follows the finger locally; the setting is saved once, on release.
    var current by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = current.coerceIn(range),
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current) },
            valueRange = range,
        )
    }
}
