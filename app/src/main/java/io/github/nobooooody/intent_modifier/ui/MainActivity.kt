package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.HOOK_INSTRUMENTATION
import io.github.nobooooody.intent_modifier.data.HOOK_LAUNCHER3
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.LauncherHook
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.engine.RuleCompilationManager
import io.github.nobooooody.intent_modifier.engine.RuleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "system") ?: "system"
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val lm = newBase.getSystemService(android.app.LocaleManager::class.java)
                lm.applicationLocales = if (lang == "system") android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(lang)
            } catch (e: Exception) { }
        }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IntentModifierTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun IntentModifierTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// ─── Tab Navigation ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> RulesScreen()
                1 -> LaunchersScreen()
                2 -> SettingsScreen()
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_rules_title)) }
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_launchers_title)) }
            )
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_settings_title)) }
            )
        }
    }
}

// ─── Rules Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RulesScreen() {
    val ctx = LocalContext.current
    val repo = remember { ModifierRepository(ctx) }
    var rules by remember { mutableStateOf(repo.getJavaCodeRules()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateListOf<Int>() }
    var showMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val activity = ctx as? ComponentActivity

    fun refresh() {
        rules = repo.getJavaCodeRules()
        selectedItems.clear()
        isSelectionMode = false
    }

    val editorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refresh()
        }
    }

    val conflictLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refresh()
        }
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            val selected = selectedItems.sorted().map { rules[it] }
            exportRules(ctx, rules, if (selectedItems.isNotEmpty()) selected else null, it)
            selectedItems.clear(); isSelectionMode = false
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: return@let
                handleImportText(ctx, text, repo) { result ->
                    when (result) {
                        is ImportResult.Success -> {
                            rules = result.rules
                            selectedItems.clear()
                            isSelectionMode = false
                        }
                        is ImportResult.Conflict -> {
                            val intent = Intent(ctx, ConflictResolutionActivity::class.java).apply {
                                putExtra(ConflictResolutionActivity.EXTRA_CONFLICT_RULES, rulesToJson(result.conflictRules))
                                putExtra(ConflictResolutionActivity.EXTRA_CURRENT_RULES, rulesToJson(result.currentRules))
                                putExtra(ConflictResolutionActivity.EXTRA_NEW_RULES, rulesToJson(result.newRules))
                            }
                            conflictLauncher.launch(intent)
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleSelection(index: Int) {
        if (index in selectedItems) selectedItems.remove(index) else selectedItems.add(index)
        if (selectedItems.isEmpty()) isSelectionMode = false
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedItems.size)) },
                    navigationIcon = {
                        IconButton(onClick = { isSelectionMode = false; selectedItems.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            if (selectedItems.size == rules.size) {
                                selectedItems.clear()
                            } else {
                                selectedItems.clear()
                                selectedItems.addAll(rules.indices)
                            }
                        }) {
                            Text(
                                if (selectedItems.size == rules.size) stringResource(R.string.deselect_all)
                                else stringResource(R.string.select_all)
                            )
                        }
                        IconButton(onClick = {
                            if (selectedItems.isNotEmpty()) showExportDialog = true
                        }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.export))
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_rules_title)) },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_to_file)) },
                                onClick = { showMenu = false; exportFileLauncher.launch("intent_modifier_rules.json") }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_to_clipboard)) },
                                onClick = {
                                    showMenu = false
                                    copyToClipboard(ctx, repo.getJavaCodeRulesJson())
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_file)) },
                                onClick = { showMenu = false; importFileLauncher.launch(arrayOf("application/json")) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_clipboard)) },
                                onClick = {
                                    showMenu = false
                                    try {
                                        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            handleImportText(ctx, clip.getItemAt(0).text.toString(), repo) { result ->
                                                when (result) {
                                                    is ImportResult.Success -> {
                                                        rules = result.rules
                                                        Toast.makeText(ctx, ctx.getString(R.string.import_success, result.count), Toast.LENGTH_SHORT).show()
                                                    }
                                                    is ImportResult.Conflict -> {
                                                        val intent = Intent(ctx, ConflictResolutionActivity::class.java).apply {
                                                            putExtra(ConflictResolutionActivity.EXTRA_CONFLICT_RULES, rulesToJson(result.conflictRules))
                                                            putExtra(ConflictResolutionActivity.EXTRA_CURRENT_RULES, rulesToJson(result.currentRules))
                                                            putExtra(ConflictResolutionActivity.EXTRA_NEW_RULES, rulesToJson(result.newRules))
                                                        }
                                                        conflictLauncher.launch(intent)
                                                    }
                                                }
                                            }
                                        } else {
                                            Toast.makeText(ctx, R.string.import_failed, Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, R.string.import_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = {
                    editorLauncher.launch(Intent(ctx, JavaCodeRuleEditorActivity::class.java))
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.no_rules_configured), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tap_to_add), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                itemsIndexed(rules, key = { _, r -> r.name }) { index, rule ->
                    val isSelected = index in selectedItems
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .combinedClickable(
                                onClick = { if (isSelectionMode) toggleSelection(index) },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedItems.add(index)
                                    }
                                }
                            )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelectionMode) {
                                    Checkbox(checked = isSelected, onCheckedChange = { toggleSelection(index) })
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (!isSelectionMode) {
                                    Switch(checked = rule.enabled, onCheckedChange = { enabled ->
                                        val updated = rules.toMutableList()
                                        updated[index] = updated[index].copy(enabled = enabled)
                                        repo.saveJavaCodeRules(updated)
                                        rules = updated
                                        scope.launch { recompileAll(ctx, repo) }
                                    })
                                }
                            }
                            Text(
                                "${stringResource(R.string.priority)}: ${rule.priority}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (rule.condition.isNotEmpty()) rule.condition else stringResource(R.string.condition_empty),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                rule.action,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!isSelectionMode) {
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        val intent = Intent(ctx, JavaCodeRuleEditorActivity::class.java)
                                        intent.putExtra(JavaCodeRuleEditorActivity.EXTRA_RULE_INDEX, index)
                                        editorLauncher.launch(intent)
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                        Text(stringResource(R.string.edit))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        val updated = rules.toMutableList()
                                        updated.removeAt(index)
                                        repo.saveJavaCodeRules(updated)
                                        rules = updated
                                        scope.launch { recompileAll(ctx, repo) }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Export dialog
    if (showExportDialog && selectedItems.isNotEmpty()) {
        val selected = selectedItems.sorted().map { rules[it] }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.export_selected_title, selected.size)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showExportDialog = false
                        activity?.let { exportFileLauncher.launch("intent_modifier_rules.json") }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_to_file))
                    }
                    TextButton(onClick = {
                        showExportDialog = false
                        copyToClipboard(ctx, rulesToJson(selected))
                        selectedItems.clear()
                        isSelectionMode = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_to_clipboard))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

}

// ─── Import types ─────────────────────────────────────────────────────────────

private sealed class ImportResult {
    data class Success(val rules: List<JavaCodeRule>, val count: Int) : ImportResult()
    data class Conflict(
        val conflictRules: List<JavaCodeRule>,
        val currentRules: MutableList<JavaCodeRule>,
        val newRules: List<JavaCodeRule>,
        val repo: ModifierRepository
    ) : ImportResult()
}

private fun handleImportText(ctx: Context, jsonStr: String, repo: ModifierRepository, onResult: (ImportResult) -> Unit) {
    try {
        val imported = mutableListOf<JavaCodeRule>()
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "").trim()
            if (name.isNotEmpty()) {
                imported.add(JavaCodeRule(
                    enabled = obj.optBoolean("enabled", true),
                    name = name,
                    imports = obj.optString("imports", ""),
                    members = obj.optString("members", ""),
                    condition = obj.optString("condition", ""),
                    action = obj.optString("action", ""),
                    priority = obj.optInt("priority", 0)
                ))
            }
        }
        if (imported.isEmpty()) {
            Toast.makeText(ctx, R.string.import_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val currentRules = repo.getJavaCodeRules().toMutableList()
        val existingNames = currentRules.map { it.name }.toSet()
        val newRules = imported.filter { it.name !in existingNames }
        val conflictRules = imported.filter { it.name in existingNames }

        if (conflictRules.isNotEmpty()) {
            onResult(ImportResult.Conflict(conflictRules, currentRules, newRules, repo))
        } else {
            currentRules.addAll(imported)
            repo.saveJavaCodeRules(currentRules)
            onResult(ImportResult.Success(currentRules, imported.size))
            Toast.makeText(ctx, ctx.getString(R.string.import_success, imported.size), Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, R.string.import_failed, Toast.LENGTH_SHORT).show()
    }
}


// ─── Launchers Screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchersScreen() {
    val ctx = LocalContext.current
    val repo = remember { ModifierRepository(ctx) }
    var hooks by remember { mutableStateOf(repo.getLauncherHooks()) }
    var showHookDialog by remember { mutableStateOf(false) }
    var hookDialogPkg by remember { mutableStateOf("") }
    var hookDialogExisting by remember { mutableStateOf<LauncherHook?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("package")?.let { pkg ->
                hookDialogPkg = pkg
                hookDialogExisting = null
                showHookDialog = true
            }
        }
    }

    fun refresh() {
        hooks = repo.getLauncherHooks()
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_launchers_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                pickerLauncher.launch(Intent(ctx, AppPickerActivity::class.java))
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) { padding ->
        if (hooks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.no_rules_configured), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tap_to_add), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                itemsIndexed(hooks.entries.toList(), key = { _, (pkg, _) -> pkg }) { _, (pkg, hook) ->
                    val appName = try {
                        ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString()
                    } catch (e: Exception) { pkg }

                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(appName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when (hook.hookType) {
                                    HOOK_LAUNCHER3 -> stringResource(R.string.hook_launcher3)
                                    else -> stringResource(R.string.hook_instrumentation)
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    hookDialogPkg = pkg
                                    hookDialogExisting = hook
                                    showHookDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                    Text(stringResource(R.string.edit))
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    repo.removeLauncherHook(pkg)
                                    refresh()
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showHookDialog) {
            HookTypeDialog(
                pkg = hookDialogPkg,
                existingHook = hookDialogExisting,
                onDismiss = { showHookDialog = false },
                onSave = { type ->
                    repo.setLauncherHook(LauncherHook(hookDialogPkg, type))
                    refresh()
                    showHookDialog = false
                    Toast.makeText(ctx, R.string.saved, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

// ─── Hook Type Dialog ─────────────────────────────────────────────────────────

@Composable
private fun HookTypeDialog(pkg: String, existingHook: LauncherHook?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val options = listOf(
        stringResource(R.string.hook_instrumentation),
        stringResource(R.string.hook_launcher3)
    )
    val currentSelection = when (existingHook?.hookType) {
        HOOK_LAUNCHER3 -> 1; else -> 0
    }
    var selected by remember { mutableIntStateOf(currentSelection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pkg) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = selected == index, onClick = { selected = index })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(if (selected == 1) HOOK_LAUNCHER3 else HOOK_INSTRUMENTATION) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ─── Settings Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    var showLangDialog by remember { mutableStateOf(false) }
    val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var currentLang by remember { mutableStateOf(prefs.getString("language", "system") ?: "system") }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showLangDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (currentLang) {
                                "en" -> stringResource(R.string.language_english)
                                "zh" -> stringResource(R.string.language_chinese)
                                else -> stringResource(R.string.language_system)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showLangDialog) {
            LanguageDialog(
                currentLang = currentLang,
                onDismiss = { showLangDialog = false },
                onSelect = { lang ->
                    currentLang = lang
                    prefs.edit().putString("language", lang).apply()
                    showLangDialog = false
                    (ctx as? ComponentActivity)?.recreate()
                }
            )
        }
    }
}

// ─── Language Dialog ──────────────────────────────────────────────────────────

@Composable
private fun LanguageDialog(currentLang: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val options = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_english),
        stringResource(R.string.language_chinese)
    )
    val values = listOf("system", "en", "zh")
    val currentIndex = when (currentLang) { "en" -> 1; "zh" -> 2; else -> 0 }
    var selected by remember { mutableIntStateOf(currentIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = selected == index, onClick = { selected = index })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(values[selected]) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ─── Recompile helper ─────────────────────────────────────────────────────────

private suspend fun recompileAll(ctx: Context, repo: ModifierRepository) {
    val rules = repo.getJavaCodeRules()
        .filter { it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty()) }
        .sortedByDescending { it.priority }
        .map { RuleSource(it.condition.ifBlank { null }, it.action.ifBlank { null }, it.imports, it.members) }

    if (rules.isEmpty()) return

    withContext(Dispatchers.IO) {
        RuleCompilationManager(ctx).compileAndStore(rules)
    }
}

// ─── Export helpers ───────────────────────────────────────────────────────────

private fun rulesToJson(rules: List<JavaCodeRule>): String {
    val arr = JSONArray()
    for (rule in rules) {
        val obj = JSONObject()
        obj.put("enabled", rule.enabled)
        obj.put("name", rule.name)
        obj.put("imports", rule.imports)
        obj.put("members", rule.members)
        obj.put("condition", rule.condition)
        obj.put("action", rule.action)
        obj.put("priority", rule.priority)
        arr.put(obj)
    }
    return arr.toString()
}

private fun copyToClipboard(ctx: Context, json: String) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("IntentModifierRules", json))
    Toast.makeText(ctx, R.string.exported_to_clipboard, Toast.LENGTH_SHORT).show()
}

private fun exportRules(ctx: Context, rules: List<JavaCodeRule>, selected: List<JavaCodeRule>?, uri: Uri) {
    try {
        val toExport = selected ?: rules
        val json = rulesToJson(toExport)
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        Toast.makeText(ctx, R.string.export_success, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, R.string.export_failed, Toast.LENGTH_SHORT).show()
    }
}
