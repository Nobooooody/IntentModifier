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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.engine.RuleCompilationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JavaCodeRuleEditorActivity : ComponentActivity() {

    private lateinit var repo: ModifierRepository
    private var editingRule: JavaCodeRule? = null

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
            val rules = repo.getJavaCodeRules()
            if (index < rules.size) {
                editingRule = rules[index]
            }
        }
        setContent {
            @OptIn(ExperimentalMaterial3Api::class)
            IntentModifierTheme {
                var saveTrigger by remember { mutableIntStateOf(0) }
                var testCompileTrigger by remember { mutableIntStateOf(0) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (editingRule != null) stringResource(R.string.edit_rule) else stringResource(R.string.new_rule)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                }
                            },
                            actions = {
                                IconButton(onClick = { testCompileTrigger++ }) {
                                    Icon(Icons.Default.Build, contentDescription = null)
                                }
                                IconButton(onClick = { saveTrigger++ }) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                }
                            }
                        )
                    }
                ) { padding ->
                    JavaCodeRuleForm(
                        editingRule = editingRule,
                        onSave = { rule ->
                            val currentRules = repo.getJavaCodeRules().toMutableList()
                            if (editingRule != null) {
                                val idx = currentRules.indexOfFirst { it.name == editingRule!!.name }
                                if (idx >= 0) currentRules[idx] = rule
                            } else {
                                currentRules.add(rule)
                            }
                            repo.saveJavaCodeRules(currentRules)
                        },
                        modifier = Modifier.padding(padding),
                        saveTrigger = saveTrigger,
                        testCompileTrigger = testCompileTrigger
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JavaCodeRuleForm(
    editingRule: JavaCodeRule?,
    onSave: (JavaCodeRule) -> Unit,
    modifier: Modifier = Modifier,
    saveTrigger: Int = 0,
    testCompileTrigger: Int = 0
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(editingRule?.name ?: "") }
    var enabled by remember { mutableStateOf(editingRule?.enabled ?: true) }
    var priority by remember { mutableStateOf(editingRule?.priority?.toString() ?: "0") }
    var targetPackages by remember { mutableStateOf(editingRule?.targetPackages ?: emptyList()) }
    var imports by remember { mutableStateOf(editingRule?.imports ?: "") }
    var members by remember { mutableStateOf(editingRule?.members ?: "") }
    var condition by remember { mutableStateOf(editingRule?.condition ?: "") }
    var action by remember { mutableStateOf(editingRule?.action ?: "") }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }
    var isCompiling by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doSave() {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            Toast.makeText(ctx, R.string.error_key_required, Toast.LENGTH_SHORT).show()
            return
        }
        isSaving = true
        val rule = JavaCodeRule(
            enabled = enabled,
            name = trimmedName,
            targetPackages = targetPackages,
            imports = imports.trim(),
            members = members.trim(),
            condition = condition.trim(),
            action = action.trim(),
            priority = priority.toIntOrNull() ?: 0
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
                        delay(1500)
                        (ctx as? ComponentActivity)?.apply { setResult(Activity.RESULT_OK); finish() }
                    }
                } else {
                    val manager = RuleCompilationManager(ctx)
                    val result = withContext(Dispatchers.IO) { manager.compileAllRules(javaRules, normalRules) }
                    withContext(Dispatchers.Main) {
                        if (result.success) {
                            Toast.makeText(ctx, R.string.compile_success, Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = result.errorMessage ?: ctx.getString(R.string.compile_failed)
                            Toast.makeText(ctx, "${ctx.getString(R.string.saved)}\n$msg", Toast.LENGTH_LONG).show()
                        }
                        delay(1500)
                        (ctx as? ComponentActivity)?.apply { setResult(Activity.RESULT_OK); finish() }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "${ctx.getString(R.string.saved)}\n${e.message}", Toast.LENGTH_LONG).show()
                    delay(1500)
                    (ctx as? ComponentActivity)?.apply { setResult(Activity.RESULT_OK); finish() }
                }
            }
        }
    }

    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0) doSave()
    }

    fun doTestCompile() {
        if (condition.isBlank() && action.isBlank()) {
            errorDialogMessage = ctx.getString(R.string.condition_or_action_required)
            return
        }
        isCompiling = true
        scope.launch {
            try {
                val manager = RuleCompilationManager(ctx)
                val testRule = JavaCodeRule(
                    enabled = true, name = "Test",
                    condition = condition.trim(), action = action.trim(),
                    imports = imports.trim(), members = members.trim()
                )
                val result = withContext(Dispatchers.IO) { manager.compileAllRules(listOf(testRule), emptyList()) }
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        Toast.makeText(ctx, R.string.compile_success, Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = result.errorMessage ?: ctx.getString(R.string.compile_failed)
                        val displayMsg = if (result.errorRuleName != null) "${result.errorRuleName}:\n$msg" else msg
                        Toast.makeText(ctx, R.string.compile_failed, Toast.LENGTH_SHORT).show()
                        errorDialogMessage = displayMsg
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.compile_failed, Toast.LENGTH_SHORT).show()
                    errorDialogMessage = "${ctx.getString(R.string.compile_failed)}: ${e.message}"
                }
            }
            isCompiling = false
        }
    }

    LaunchedEffect(testCompileTrigger) {
        if (testCompileTrigger > 0) doTestCompile()
    }

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

    val packageManager = ctx.packageManager

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState())
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

            Spacer(Modifier.height(16.dp))

            // 优先级
            OutlinedTextField(
                value = priority,
                onValueChange = { if (it.all { c -> c.isDigit() || c == '-' }) priority = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.priority)) },
                placeholder = { Text(stringResource(R.string.priority_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(Modifier.height(16.dp))

            // Target apps (compilation scope)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.target_apps_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val intent = Intent(ctx, AppPickerActivity::class.java).apply {
                                putExtra(AppPickerActivity.EXTRA_MULTI_SELECT, true)
                            }
                            appPickerLauncher.launch(intent)
                        }) {
                            Text(if (targetPackages.isEmpty()) stringResource(R.string.target_apps_pick) else stringResource(R.string.target_apps_add))
                        }
                    }
                    Text(stringResource(R.string.target_apps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    tonalElevation = 0.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                    ) {
                                        Text("$label ($pkg)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Close, contentDescription = stringResource(R.string.remove),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.clickable { targetPackages = targetPackages - pkg }.padding(4.dp).size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Imports
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.imports_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.imports_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = imports, onValueChange = { imports = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 2
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Members
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.members_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.members_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = members, onValueChange = { members = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 3
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Condition
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.condition_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.condition_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = condition, onValueChange = { condition = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 3
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.action_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.action_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = action, onValueChange = { action = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 6
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Buttons
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { doTestCompile() },
                    modifier = Modifier.weight(1f),
                    enabled = !isCompiling && !isSaving
                ) {
                    Text(stringResource(R.string.test_compile))
                }

                Spacer(Modifier.padding(horizontal = 8.dp))

                Button(
                    onClick = { doSave() },
                    modifier = Modifier.weight(1f),
                    enabled = !isCompiling && !isSaving
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        errorDialogMessage?.let { msg ->
            ErrorDialog(message = msg, onDismiss = { errorDialogMessage = null })
        }
    }

@Composable
private fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compile_failed)) },
        text = {
            SelectionContainer {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
