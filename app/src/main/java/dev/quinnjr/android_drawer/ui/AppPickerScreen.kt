package dev.quinnjr.android_drawer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.quinnjr.android_drawer.R
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.AppKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    apps: List<LaunchableApp>?,
    chosen: List<AppKey>,
    onToggle: (AppKey) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val chosenSet = remember(chosen) { chosen.toSet() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.choose_apps)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (apps == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val filtered = remember(apps, query) {
            if (query.isBlank()) { apps } else { apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
        }
        LazyColumn(contentPadding = padding) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(filtered, key = { it.key.encode() }) { app ->
                val checked = app.key in chosenSet
                ListItem(
                    headlineContent = { Text(app.label) },
                    leadingContent = { AppIcon(app) },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle(app.key) }) },
                    modifier = Modifier.clickable { onToggle(app.key) },
                )
            }
        }
    }
}
