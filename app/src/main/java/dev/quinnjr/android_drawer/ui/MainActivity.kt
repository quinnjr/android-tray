package dev.quinnjr.android_drawer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quinnjr.android_drawer.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val settings by vm.settings.collectAsStateWithLifecycle()
                val allApps by vm.allApps.collectAsStateWithLifecycle()
                var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
                var notificationsGranted by remember { mutableStateOf(notificationsGranted()) }
                var picking by rememberSaveable { mutableStateOf(false) }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    canDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
                    notificationsGranted = notificationsGranted()
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
                    SettingsScreen(
                        settings = s,
                        chosenApps = s.apps.mapNotNull { byKey[it] },
                        canDrawOverlays = canDrawOverlays,
                        notificationsGranted = notificationsGranted,
                        onGrantOverlay = {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                            )
                        },
                        onGrantNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onEnabledChange = vm::setEnabled,
                        onEditApps = { picking = true },
                        onMoveApp = vm::moveApp,
                        onHeightChange = vm::setHeight,
                        onAlphaChange = vm::setAlpha,
                        onColorChange = vm::setColor,
                    )
                }
            }
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
