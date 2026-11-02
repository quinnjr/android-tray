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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.quinnjr.android_drawer.R
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.settings.MAX_HANDLE_HEIGHT_DP
import dev.quinnjr.android_drawer.settings.MIN_HANDLE_HEIGHT_DP
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
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!canDrawOverlays) {
                item {
                    PermissionCard(
                        stringResource(R.string.grant_overlay_title),
                        stringResource(R.string.grant_overlay_text),
                        onGrantOverlay,
                    )
                }
            }
            if (!notificationsGranted) {
                item {
                    PermissionCard(
                        stringResource(R.string.grant_notifications_title),
                        stringResource(R.string.grant_notifications_text),
                        onGrantNotifications,
                    )
                }
            }
            if (!usageAccessGranted) {
                item {
                    PermissionCard(
                        stringResource(R.string.grant_usage_title),
                        pluralStringResource(R.plurals.grant_usage_text, AppCatalog.RECENT_LIMIT, AppCatalog.RECENT_LIMIT),
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
                        Text(stringResource(R.string.show_handle), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.show_handle_text),
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
            item { SectionTitle(stringResource(R.string.section_drawer_apps)) }
            if (chosenApps == null) {
                item { CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp)) }
            } else if (chosenApps.keys.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_apps_chosen),
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
                        if (shown.isEmpty()) {
                            stringResource(R.string.settings_pinned_load_failed)
                        } else {
                            stringResource(R.string.settings_pinned_load_partly_failed)
                        },
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
                            pluralStringResource(R.plurals.pinned_unavailable_count, unavailable.size, unavailable.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { onUnpinUnavailable(unavailable) }) { Text(stringResource(R.string.unpin_unavailable)) }
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
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { onMoveApp(app.key, +1) }, enabled = index < shown.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                    }
                }
            }
            item {
                OutlinedButton(onClick = onEditApps, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.edit_apps))
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
                        Text(stringResource(R.string.lock_icons), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.lock_icons_text),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = settings.iconsLocked, onCheckedChange = onIconsLockedChange)
                }
            }
            item { SectionTitle(stringResource(R.string.section_handle)) }
            item {
                LabeledSlider(
                    stringResource(R.string.handle_height),
                    value = settings.handleHeightDp.toFloat(),
                    range = MIN_HANDLE_HEIGHT_DP.toFloat()..MAX_HANDLE_HEIGHT_DP.toFloat(),
                    onChange = { onHeightChange(it.toInt()) },
                )
            }
            item {
                LabeledSlider(
                    stringResource(R.string.handle_opacity),
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
            Button(onClick = onGrant) { Text(stringResource(R.string.allow)) }
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
    // While dragged the thumb follows the finger locally; the setting is saved once, on release.
    // Otherwise it shows the setting, so an undone change (it couldn't be saved) shows too.
    var dragged by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = (dragged ?: value).coerceIn(range),
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let(onChange)
                dragged = null
            },
            valueRange = range,
        )
    }
}
