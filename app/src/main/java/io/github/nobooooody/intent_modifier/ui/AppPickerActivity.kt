package io.github.nobooooody.intent_modifier.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nobooooody.intent_modifier.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IntentModifierTheme {
                AppPickerScreen(
                    onAppSelected = { pkg ->
                        val result = Intent().putExtra("package", pkg)
                        setResult(RESULT_OK, result)
                        finish()
                    }
                )
            }
        }
    }
}

data class AppInfo(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(onAppSelected: (String) -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val allApps = remember { mutableStateListOf<AppInfo>() }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != ctx.packageName }
                .map { AppInfo(it.packageName, it.loadLabel(ctx.packageManager).toString()) }
                .sortedBy { it.label.lowercase() }
        }
        allApps.clear()
        allApps.addAll(apps)
        isLoading = false
    }

    val filtered = remember(allApps, query) {
        if (query.isBlank()) allApps.toList()
        else allApps.filter { it.packageName.contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_app)) },
                navigationIcon = {
                    IconButton(onClick = { (ctx as? ComponentActivity)?.finish() }) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        isLoading = true
                        (ctx as? ComponentActivity)?.recreate()
                    }) {
                        Icon(androidx.compose.material.icons.Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.loading_apps))
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    singleLine = true
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onAppSelected(app.packageName) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
