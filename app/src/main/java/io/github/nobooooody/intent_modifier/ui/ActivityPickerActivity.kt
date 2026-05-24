package io.github.nobooooody.intent_modifier.ui

import android.content.ComponentName
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import io.github.nobooooody.intent_modifier.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ActivityPickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_SELECTED_ACTIVITY = "activity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return finish()

        setContent {
            IntentModifierTheme {
                ActivityPickerScreen(
                    packageName = pkg,
                    onSelect = { className ->
                        val result = Intent().putExtra(EXTRA_SELECTED_ACTIVITY, className)
                        setResult(RESULT_OK, result)
                        finish()
                    }
                )
            }
        }
    }
}

data class ActivityInfo(val className: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityPickerScreen(
    packageName: String,
    onSelect: (String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val activities = remember { mutableStateListOf<ActivityInfo>() }
    val ctx = LocalContext.current

    LaunchedEffect(packageName) {
        val list = withContext(Dispatchers.IO) {
            try {
                val pm = ctx.packageManager
                val intent = Intent().setPackage(packageName)
                val resolved = pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
                val seen = mutableSetOf<String>()
                resolved
                    .filter { it.activityInfo.exported }
                    .mapNotNull {
                        val cn = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                        val className = cn.flattenToShortString()
                        if (className in seen) null else {
                            seen.add(className)
                            val label = it.activityInfo.loadLabel(pm).toString()
                            ActivityInfo(className, label)
                        }
                    }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
        }
        activities.clear()
        activities.addAll(list)
        isLoading = false
    }

    val filtered = if (query.isBlank()) activities.toList()
        else activities.filter { it.className.contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_activity, packageName)) },
                navigationIcon = {
                    IconButton(onClick = { (ctx as? ComponentActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                    Text(stringResource(R.string.loading_activities))
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    placeholder = { Text(stringResource(R.string.search_activities)) },
                    singleLine = true
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(filtered, key = { it.className }) { activity ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(activity.className) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(activity.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    activity.className,
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
