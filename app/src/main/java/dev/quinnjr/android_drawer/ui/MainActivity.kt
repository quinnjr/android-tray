package dev.quinnjr.android_drawer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quinnjr.android_drawer.apps.RecentApps
import dev.quinnjr.android_drawer.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: SettingsViewModel by viewModels()
    private val recents by lazy { RecentApps(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val settings by vm.settings.collectAsStateWithLifecycle()
                val allApps by vm.allApps.collectAsStateWithLifecycle()
                var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
                var notificationsGranted by remember { mutableStateOf(notificationsGranted()) }
                var usageAccessGranted by remember { mutableStateOf(recents.hasAccess()) }
                var picking by rememberSaveable { mutableStateOf(false) }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    canDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
                    notificationsGranted = notificationsGranted()
                    usageAccessGranted = recents.hasAccess()
                    vm.syncService()
                }
                val requestNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { notificationsGranted = it }

                val s = settings ?: return@AppTheme
                if (picking) {
                    BackHandler { picking = false }
                    AppPickerScreen(
                        apps = allApps,
                        chosen = s.apps,
                        onToggle = vm::toggleApp,
                        onBack = { picking = false },
                    )
                } else {
                    val byKey = remember(allApps) { allApps.orEmpty().associateBy { it.key } }
                    val chosenApps = s.apps.mapNotNull { byKey[it] }
                    SettingsScreen(
                        settings = s,
                        chosenApps = chosenApps,
                        canDrawOverlays = canDrawOverlays,
                        notificationsGranted = notificationsGranted,
                        usageAccessGranted = usageAccessGranted,
                        onGrantOverlay = {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
                            )
                        },
                        onGrantNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onGrantUsageAccess = ::openUsageAccessSettings,
                        onEnabledChange = vm::setEnabled,
                        onEditApps = { picking = true },
                        onMoveApp = { from, to -> vm.moveApp(chosenApps.map { it.key }, from, to) },
                        onIconsLockedChange = vm::setIconsLocked,
                        onHeightChange = vm::setHeight,
                        onAlphaChange = vm::setAlpha,
                        onColorChange = vm::setColor,
                    )
                }
            }
        }
    }

    /** Jumps straight to this app's Usage access switch where supported, else to the list. */
    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri()))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
