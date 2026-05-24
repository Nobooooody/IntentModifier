package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.ExtraItem
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.data.NormalRule
import io.github.nobooooody.intent_modifier.engine.RuleCompilationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NormalRuleEditorActivity : ComponentActivity() {

    private lateinit var repo: ModifierRepository
    private var editingRule: NormalRule? = null

    companion object {
        const val EXTRA_RULE_INDEX = "rule_index"
    }

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
        repo = ModifierRepository(this)
        val index = intent.getIntExtra(EXTRA_RULE_INDEX, -1)
        if (index >= 0) {
            val rules = repo.getNormalRules()
            if (index < rules.size) {
                editingRule = rules[index]
            }
        }
        setContent {
            IntentModifierTheme {
                NormalRuleEditorScreen(
                    editingRule = editingRule,
                    onSave = { rule ->
                        val currentRules = repo.getNormalRules().toMutableList()
                        if (editingRule != null) {
                            val idx = currentRules.indexOfFirst { it.id == editingRule!!.id }
                            if (idx >= 0) currentRules[idx] = rule
                        } else {
                            currentRules.add(rule)
                        }
                        repo.saveNormalRules(currentRules)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NormalRuleEditorScreen(
    editingRule: NormalRule?,
    onSave: (NormalRule) -> Unit
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(editingRule?.name ?: "") }
    var enabled by remember { mutableStateOf(editingRule?.enabled ?: true) }
    var blockSubsequent by remember { mutableStateOf(editingRule?.blockSubsequent ?: true) }
    var priority by remember { mutableStateOf(editingRule?.priority?.toString() ?: "0") }
    var targetPackages by remember { mutableStateOf(editingRule?.targetPackages ?: emptyList()) }

    var matchPackage by remember { mutableStateOf(editingRule?.matchPackage ?: "") }
    var matchAction by remember { mutableStateOf(editingRule?.matchAction ?: "") }
    var matchClass by remember { mutableStateOf(editingRule?.matchClass ?: "") }
    var matchData by remember { mutableStateOf(editingRule?.matchData ?: "") }
    var matchCategories by remember { mutableStateOf(editingRule?.matchCategories?.joinToString(", ") ?: "") }
    var matchType by remember { mutableStateOf(editingRule?.matchType ?: "") }

    var customPackage by remember { mutableStateOf(editingRule?.customPackage ?: "") }
    var customAction by remember { mutableStateOf(editingRule?.customAction ?: "") }
    var customClass by remember { mutableStateOf(editingRule?.customClass ?: "") }
    var customData by remember { mutableStateOf(editingRule?.customData ?: "") }
    var customCategories by remember { mutableStateOf(editingRule?.customCategories?.joinToString(", ") ?: "") }
    var customType by remember { mutableStateOf(editingRule?.customType ?: "") }
    var customFlags by remember { mutableStateOf(editingRule?.customFlags?.toString() ?: "") }

    var extras by remember { mutableStateOf(editingRule?.extras ?: emptyList()) }

    var compileResult by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var isCompiling by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val packageManager = ctx.packageManager

    val appPickerLauncher = rememberLauncherForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selected = result.data?.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED_PACKAGES)
            if (selected != null) {
                targetPackages = targetPackages + selected.filter { it !in targetPackages }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editingRule != null) stringResource(R.string.edit_rule) else stringResource(R.string.new_rule)) },
                navigationIcon = {
                    IconButton(onClick = { (ctx as? ComponentActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            // 名称
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rule_name)) },
                placeholder = { Text(stringResource(R.string.rule_name_hint)) },
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            // 启用 + 阻断
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("阻断后续规则", modifier = Modifier.weight(1f))
                Switch(checked = blockSubsequent, onCheckedChange = { blockSubsequent = it })
            }

            Spacer(Modifier.height(16.dp))

            // 优先级
            OutlinedTextField(
                value = priority, onValueChange = { priority = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.priority)) },
                placeholder = { Text(stringResource(R.string.priority_hint)) },
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            // 目标应用
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("目标应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val intent = Intent(ctx, AppPickerActivity::class.java).apply {
                                putExtra(AppPickerActivity.EXTRA_MULTI_SELECT, true)
                            }
                            appPickerLauncher.launch(intent)
                        }) {
                            Text(if (targetPackages.isEmpty()) "选择应用" else "添加")
                        }
                    }
                    Text("留空则对所有应用生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (targetPackages.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            targetPackages.forEach { pkg ->
                                val label = try {
                                    packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
                                } catch (e: Exception) { pkg }
                                AssistChip(
                                    onClick = {},
                                    label = { Text("$label ($pkg)", maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                                    trailingIcon = {
                                        IconButton(onClick = { targetPackages = targetPackages - pkg }) {
                                            Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.padding(0.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 匹配条件
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("匹配条件", style = MaterialTheme.typography.titleMedium)
                    Text("留空表示不检查该字段", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = matchPackage, onValueChange = { matchPackage = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Package") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = matchAction, onValueChange = { matchAction = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Action") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = matchClass, onValueChange = { matchClass = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Class") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = matchData, onValueChange = { matchData = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Data") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = matchCategories, onValueChange = { matchCategories = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Categories（逗号分隔）") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = matchType, onValueChange = { matchType = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("MIME Type") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 自定义 Intent
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("自定义 Intent", style = MaterialTheme.typography.titleMedium)
                    Text("覆盖匹配 Intent 的字段", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customPackage, onValueChange = { customPackage = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Package") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customAction, onValueChange = { customAction = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Action") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customClass, onValueChange = { customClass = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Class") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customData, onValueChange = { customData = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Data") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customCategories, onValueChange = { customCategories = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Categories（逗号分隔）") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customType, onValueChange = { customType = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom MIME Type") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customFlags, onValueChange = { customFlags = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Flags（数字）") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Extras
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Extra", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { extras = extras + ExtraItem("", "string") }) {
                            Icon(Icons.Default.Add, contentDescription = "添加 Extra")
                        }
                    }
                    extras.forEachIndexed { idx, extra ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = extra.key, onValueChange = { newKey ->
                                    val list = extras.toMutableList()
                                    list[idx] = extra.copy(key = newKey)
                                    extras = list
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("Key") },
                                singleLine = true
                            )
                            IconButton(onClick = {
                                val list = extras.toMutableList()
                                list.removeAt(idx)
                                extras = list
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Buttons
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        isCompiling = true
                        compileResult = Pair(ctx.getString(R.string.compiling), Color(0xFFFF9800))
                        scope.launch {
                            try {
                                val manager = RuleCompilationManager(ctx)
                                val testRule = NormalRule(
                                    enabled = true, name = "Test",
                                    matchPackage = matchPackage.trim().ifBlank { null },
                                    matchAction = matchAction.trim().ifBlank { null },
                                    matchClass = matchClass.trim().ifBlank { null },
                                    matchData = matchData.trim().ifBlank { null },
                                    matchCategories = matchCategories.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                    matchType = matchType.trim().ifBlank { null },
                                    customPackage = customPackage.trim().ifBlank { null },
                                    customAction = customAction.trim().ifBlank { null },
                                    customClass = customClass.trim().ifBlank { null },
                                    customData = customData.trim().ifBlank { null },
                                    customCategories = customCategories.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                    customType = customType.trim().ifBlank { null },
                                    customFlags = customFlags.trim().toIntOrNull(),
                                    extras = extras
                                )
                                val result = withContext(Dispatchers.IO) { manager.compileAllRules(emptyList(), listOf(testRule)) }
                                compileResult = if (result.success) {
                                    Pair(ctx.getString(R.string.compile_success), Color(0xFF4CAF50))
                                } else {
                                    val msg = result.errorMessage ?: ctx.getString(R.string.compile_failed)
                                    Pair(if (result.errorRuleName != null) "${result.errorRuleName}:\n$msg" else msg, Color(0xFFF44336))
                                }
                                if (result.success) {
                                    Toast.makeText(ctx, R.string.compile_success, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, R.string.compile_failed, Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                compileResult = Pair("${ctx.getString(R.string.compile_failed)}: ${e.message}", Color(0xFFF44336))
                                Toast.makeText(ctx, R.string.compile_failed, Toast.LENGTH_SHORT).show()
                            }
                            isCompiling = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isCompiling && !isSaving
                ) {
                    Text(stringResource(R.string.test_compile))
                }

                Spacer(Modifier.padding(horizontal = 8.dp))

                Button(
                    onClick = {
                        val trimmedName = name.trim()
                        if (trimmedName.isBlank()) {
                            Toast.makeText(ctx, R.string.error_key_required, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSaving = true
                        val rule = NormalRule(
                            id = editingRule?.id ?: java.util.UUID.randomUUID().toString(),
                            enabled = enabled,
                            name = trimmedName,
                            targetPackages = targetPackages,
                            blockSubsequent = blockSubsequent,
                            priority = priority.toIntOrNull() ?: 0,
                            matchPackage = matchPackage.trim().ifBlank { null },
                            matchAction = matchAction.trim().ifBlank { null },
                            matchClass = matchClass.trim().ifBlank { null },
                            matchData = matchData.trim().ifBlank { null },
                            matchCategories = matchCategories.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            matchType = matchType.trim().ifBlank { null },
                            customPackage = customPackage.trim().ifBlank { null },
                            customAction = customAction.trim().ifBlank { null },
                            customClass = customClass.trim().ifBlank { null },
                            customData = customData.trim().ifBlank { null },
                            customCategories = customCategories.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            customType = customType.trim().ifBlank { null },
                            customFlags = customFlags.trim().toIntOrNull(),
                            extras = extras
                        )
                        onSave(rule)

                        Toast.makeText(ctx, R.string.compiling_all_rules, Toast.LENGTH_SHORT).show()
                        scope.launch {
                            try {
                                val repo = ModifierRepository(ctx)
                                val javaRules = repo.getJavaCodeRules()
                                    .filter { it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty()) }
                                    .sortedByDescending { it.priority }
                                val normalRules = repo.getNormalRules().filter { it.enabled }
                                if (javaRules.isEmpty() && normalRules.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, R.string.no_rules_to_compile, Toast.LENGTH_SHORT).show()
                                        (ctx as? ComponentActivity)?.finish()
                                    }
                                } else {
                                    val manager = RuleCompilationManager(ctx)
                                    val result = withContext(Dispatchers.IO) { manager.compileAllRules(javaRules, normalRules) }
                                    withContext(Dispatchers.Main) {
                                        if (result.success) {
                                            Toast.makeText(ctx, R.string.saved_and_compiled, Toast.LENGTH_SHORT).show()
                                        } else {
                                            val msg = result.errorMessage ?: ctx.getString(R.string.compile_failed)
                                            Toast.makeText(ctx, "${ctx.getString(R.string.saved)}\n$msg", Toast.LENGTH_LONG).show()
                                        }
                                        (ctx as? ComponentActivity)?.finish()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, "${ctx.getString(R.string.saved)}\n${e.message}", Toast.LENGTH_LONG).show()
                                    (ctx as? ComponentActivity)?.finish()
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isCompiling && !isSaving
                ) {
                    Text(stringResource(R.string.save))
                }
            }

            compileResult?.let { (msg, color) ->
                Spacer(Modifier.height(16.dp))
                Text(msg, color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
