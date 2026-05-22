package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.engine.RuleCompilationManager
import io.github.nobooooody.intent_modifier.engine.RuleSource
import kotlinx.coroutines.Dispatchers
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
            IntentModifierTheme {
                RuleEditorScreen(
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
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorScreen(
    editingRule: JavaCodeRule?,
    onSave: (JavaCodeRule) -> Unit
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(editingRule?.name ?: "") }
    var enabled by remember { mutableStateOf(editingRule?.enabled ?: true) }
    var priority by remember { mutableStateOf(editingRule?.priority?.toString() ?: "0") }
    var imports by remember { mutableStateOf(editingRule?.imports ?: "") }
    var members by remember { mutableStateOf(editingRule?.members ?: "") }
    var condition by remember { mutableStateOf(editingRule?.condition ?: "") }
    var action by remember { mutableStateOf(editingRule?.action ?: "") }
    var compileResult by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var isCompiling by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editingRule != null) stringResource(R.string.edit_rule) else stringResource(R.string.new_rule)) },
                navigationIcon = {
                    IconButton(onClick = { (ctx as? ComponentActivity)?.finish() }) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rule_name)) },
                placeholder = { Text(stringResource(R.string.rule_name_hint)) },
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = priority, onValueChange = { priority = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.priority)) },
                placeholder = { Text(stringResource(R.string.priority_hint)) },
                singleLine = true
            )

            Spacer(Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.imports_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.imports_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = imports, onValueChange = { imports = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        minLines = 2
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.members_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.members_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = members, onValueChange = { members = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        minLines = 3
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.condition_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.condition_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = condition, onValueChange = { condition = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        minLines = 3
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.action_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.action_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = action, onValueChange = { action = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        minLines = 6
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (condition.isBlank() && action.isBlank()) {
                            Toast.makeText(ctx, R.string.compile_failed, Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        isCompiling = true
                        compileResult = Pair(ctx.getString(R.string.compiling), Color(0xFFFF9800))
                        scope.launch {
                            try {
                                val manager = RuleCompilationManager(ctx)
                                val sources = listOf(RuleSource(condition.ifBlank { null }, action.ifBlank { null }, imports, members))
                                val result = withContext(Dispatchers.IO) { manager.compileAndStore(sources) }
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
                        val rule = JavaCodeRule(
                            enabled = enabled,
                            name = trimmedName,
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
                                val currentRules = repo.getJavaCodeRules()
                                val sources = currentRules
                                    .filter { it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty()) }
                                    .sortedByDescending { it.priority }
                                    .map { RuleSource(it.condition.ifBlank { null }, it.action.ifBlank { null }, it.imports, it.members) }
                                if (sources.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, R.string.no_rules_to_compile, Toast.LENGTH_SHORT).show()
                                        (ctx as? ComponentActivity)?.finish()
                                    }
                                } else {
                                    val manager = RuleCompilationManager(ctx)
                                    val result = withContext(Dispatchers.IO) { manager.compileAndStore(sources) }
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
