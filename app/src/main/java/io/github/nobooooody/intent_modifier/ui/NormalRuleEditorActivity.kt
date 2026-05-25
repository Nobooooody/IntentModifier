package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.ExtraItem
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.data.NormalRule
import io.github.nobooooody.intent_modifier.engine.RuleCompilationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                }
                                IconButton(onClick = { saveTrigger++ }) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                }
                            }
                        )
                    }
                ) { padding ->
                    NormalRuleForm(
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
fun NormalRuleForm(
    editingRule: NormalRule?,
    onSave: (NormalRule) -> Unit,
    modifier: Modifier = Modifier,
    saveTrigger: Int = 0,
    testCompileTrigger: Int = 0
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
    var matchCategories by remember { mutableStateOf(editingRule?.matchCategories?.filter { it.isNotBlank() } ?: emptyList()) }
    var matchType by remember { mutableStateOf(editingRule?.matchType ?: "") }

    var customPackage by remember { mutableStateOf(editingRule?.customPackage ?: "") }
    var customAction by remember { mutableStateOf(editingRule?.customAction ?: "") }
    var customClass by remember { mutableStateOf(editingRule?.customClass ?: "") }
    var customData by remember { mutableStateOf(editingRule?.customData ?: "") }
    var customCategories by remember { mutableStateOf(editingRule?.customCategories?.filter { it.isNotBlank() } ?: emptyList()) }
    var replaceCategories by remember { mutableStateOf(editingRule?.replaceCategories ?: false) }
    var replaceExtras by remember { mutableStateOf(editingRule?.replaceExtras ?: false) }
    var customType by remember { mutableStateOf(editingRule?.customType ?: "") }
    var customFlags by remember { mutableStateOf(editingRule?.customFlags?.toString() ?: "") }

    var extras by remember { mutableStateOf(editingRule?.extras ?: emptyList()) }

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
        var hasError = false
        for (extra in extras) {
            if (extra.key.isBlank()) {
                Toast.makeText(ctx, R.string.error_key_required, Toast.LENGTH_SHORT).show()
                hasError = true; break
            }
            val valStr = extra.values.firstOrNull() ?: ""
            when (extra.type) {
                "integer" -> if (valStr.isNotBlank() && valStr.toIntOrNull() == null) {
                    Toast.makeText(ctx, R.string.error_invalid_integer, Toast.LENGTH_SHORT).show()
                    hasError = true; break
                }
                "long" -> if (valStr.isNotBlank() && valStr.toLongOrNull() == null) {
                    Toast.makeText(ctx, R.string.error_invalid_long, Toast.LENGTH_SHORT).show()
                    hasError = true; break
                }
                "decimal" -> if (valStr.isNotBlank() && valStr.toDoubleOrNull() == null) {
                    Toast.makeText(ctx, R.string.error_invalid_number, Toast.LENGTH_SHORT).show()
                    hasError = true; break
                }
            }
            if (hasError) break
        }
        if (hasError) return
        val targetList = targetPackages.toList()
        val validExtras = extras.filter { it.key.isNotBlank() }
        val priorityVal = priority.trim().toIntOrNull() ?: 0
        val trimmedCustomType = customType.trim()
        val customFlagsVal = customFlags.trim().toIntOrNull() ?: 0
        val rule = NormalRule(
            id = editingRule?.id ?: UUID.randomUUID().toString(),
            name = trimmedName,
            enabled = enabled,
            targetPackages = targetList,
            blockSubsequent = blockSubsequent,
            priority = priorityVal,
            matchPackage = matchPackage.trim().ifEmpty { null },
            matchAction = matchAction.trim().ifEmpty { null },
            matchData = matchData.trim().ifEmpty { null },
            matchClass = matchClass.trim().ifEmpty { null },
            matchCategories = matchCategories.filter { it.isNotBlank() },
            matchType = matchType.trim().ifEmpty { null },
            customPackage = customPackage.trim().ifEmpty { null },
            customAction = customAction.trim().ifEmpty { null },
            customData = customData.trim().ifEmpty { null },
            customClass = customClass.trim().ifEmpty { null },
            customCategories = customCategories.filter { it.isNotBlank() },
            customFlags = customFlagsVal,
            customType = trimmedCustomType.ifEmpty { null },
            extras = validExtras,
            replaceCategories = replaceCategories,
            replaceExtras = replaceExtras
        )
        onSave(rule)
        isSaving = true
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
                            Toast.makeText(ctx, "${ctx.getString(R.string.saved)}\n${ctx.getString(R.string.compile_success)}", Toast.LENGTH_LONG).show()
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
        isCompiling = true
        scope.launch {
            try {
                val manager = RuleCompilationManager(ctx)
                val testRule = NormalRule(
                    enabled = true, name = "Test",
                    matchPackage = matchPackage.trim().ifBlank { null },
                    matchAction = matchAction.trim().ifBlank { null },
                    matchClass = matchClass.trim().ifBlank { null },
                    matchData = matchData.trim().ifBlank { null },
                    matchCategories = matchCategories,
                    matchType = matchType.trim().ifBlank { null },
                    customPackage = customPackage.trim().ifBlank { null },
                    customAction = customAction.trim().ifBlank { null },
                    customClass = customClass.trim().ifBlank { null },
                    customData = customData.trim().ifBlank { null },
                    customCategories = customCategories,
                    replaceCategories = replaceCategories,
                    replaceExtras = replaceExtras,
                    customType = customType.trim().ifBlank { null },
                    customFlags = customFlags.trim().toIntOrNull(),
                    extras = extras
                )
                val result = withContext(Dispatchers.IO) { manager.compileAllRules(emptyList(), listOf(testRule)) }
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

    val singleAppPickerLauncher = rememberLauncherForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pkg = result.data?.getStringExtra("package")
            if (pkg != null) {
                customPackage = pkg
            }
        }
    }

    val matchAppPickerLauncher = rememberLauncherForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pkg = result.data?.getStringExtra("package")
            if (pkg != null) {
                matchPackage = pkg
            }
        }
    }

    var pendingClassField by remember { mutableStateOf("") }
    val activityPickerLauncher = rememberLauncherForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val activity = result.data?.getStringExtra(ActivityPickerActivity.EXTRA_SELECTED_ACTIVITY)
            if (activity != null) {
                if (pendingClassField == "match") matchClass = activity
                else customClass = activity
            }
        }
    }

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

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.block_subsequent), modifier = Modifier.weight(1f))
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

            // Match Conditions
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.match_condition_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.match_condition_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = matchPackage, onValueChange = { matchPackage = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.match_package)) },
                            minLines = 1,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = {
                                matchAppPickerLauncher.launch(Intent(ctx, AppPickerActivity::class.java))
                            }) {
                                Text(stringResource(R.string.target_apps_pick))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = matchClass, onValueChange = { matchClass = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.match_class)) },
                            minLines = 1,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = {
                                val pkg = matchPackage.trim().ifBlank { null }
                                if (pkg != null) {
                                    pendingClassField = "match"
                                    val intent = Intent(ctx, ActivityPickerActivity::class.java).apply {
                                        putExtra(ActivityPickerActivity.EXTRA_PACKAGE_NAME, pkg)
                                    }
                                    activityPickerLauncher.launch(intent)
                                } else {
                                    Toast.makeText(ctx, R.string.no_package_to_browse, Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text(stringResource(R.string.pick_activity))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = matchData, onValueChange = { matchData = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.match_data)) },
                        minLines = 1,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )

                    var showMatchAdvanced by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { showMatchAdvanced = !showMatchAdvanced },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.match_advanced),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (showMatchAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    AnimatedVisibility(visible = showMatchAdvanced) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = matchAction, onValueChange = { matchAction = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.match_action)) },
                                minLines = 1,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Spacer(Modifier.height(4.dp))
                            CategoryListEditor(
                                items = matchCategories,
                                onItemsChange = { matchCategories = it },
                                label = stringResource(R.string.match_categories)
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = matchType, onValueChange = { matchType = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.match_type)) },
                                minLines = 1,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Custom Intent
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.custom_intent_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.custom_intent_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customPackage, onValueChange = { customPackage = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.custom_package)) },
                            minLines = 1,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = {
                                singleAppPickerLauncher.launch(Intent(ctx, AppPickerActivity::class.java))
                            }) {
                                Text(stringResource(R.string.target_apps_pick))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customClass, onValueChange = { customClass = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.custom_class)) },
                            minLines = 1,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = {
                                val pkg = customPackage.trim().ifBlank { matchPackage.trim().ifBlank { null } }
                                if (pkg != null) {
                                    pendingClassField = "custom"
                                    val intent = Intent(ctx, ActivityPickerActivity::class.java).apply {
                                        putExtra(ActivityPickerActivity.EXTRA_PACKAGE_NAME, pkg)
                                    }
                                    activityPickerLauncher.launch(intent)
                                } else {
                                    Toast.makeText(ctx, R.string.no_package_to_browse, Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text(stringResource(R.string.pick_activity))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customData, onValueChange = { customData = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.custom_data)) },
                        minLines = 1,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )

                    var showCustomAdvanced by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { showCustomAdvanced = !showCustomAdvanced },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.custom_advanced),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (showCustomAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    AnimatedVisibility(visible = showCustomAdvanced) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customAction, onValueChange = { customAction = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.custom_action)) },
                                minLines = 1,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Spacer(Modifier.height(4.dp))
                            CategoryListEditor(
                                items = customCategories,
                                onItemsChange = { customCategories = it },
                                label = stringResource(R.string.custom_categories)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.replace_categories))
                                Switch(
                                    checked = replaceCategories,
                                    onCheckedChange = { replaceCategories = it }
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customType, onValueChange = { customType = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.custom_type)) },
                                minLines = 1,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customFlags, onValueChange = { customFlags = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.custom_flags)) },
                                minLines = 1,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Extras
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.extra_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { extras = extras + ExtraItem("", "string") }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.extra_add))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.replace_extras))
                        Switch(
                            checked = replaceExtras,
                            onCheckedChange = { replaceExtras = it }
                        )
                    }
                    extras.forEachIndexed { idx, extra ->
                        Spacer(Modifier.height(8.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = extra.key,
                                        onValueChange = { newKey ->
                                            val list = extras.toMutableList()
                                            list[idx] = extra.copy(key = newKey)
                                            extras = list
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text(stringResource(R.string.extra_key)) },
                                        singleLine = true
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box {
                                        val internalTypes = listOf("string", "integer", "long", "decimal", "boolean", "uri", "component", "null")
                                        val typeLabels = mapOf(
                                            "string" to stringResource(R.string.type_string),
                                            "integer" to stringResource(R.string.type_integer),
                                            "long" to stringResource(R.string.type_long),
                                            "decimal" to stringResource(R.string.type_decimal),
                                            "boolean" to stringResource(R.string.type_boolean),
                                            "uri" to stringResource(R.string.type_uri),
                                            "component" to stringResource(R.string.type_component),
                                            "null" to stringResource(R.string.type_null)
                                        )
                                        var expandedType by remember { mutableStateOf(false) }
                                        OutlinedButton(onClick = { expandedType = true }) {
                                            Text(typeLabels[extra.type] ?: extra.type, maxLines = 1)
                                        }
                                        DropdownMenu(expanded = expandedType, onDismissRequest = { expandedType = false }) {
                                            internalTypes.forEach { t ->
                                                DropdownMenuItem(
                                                    text = { Text(typeLabels[t] ?: t) },
                                                    onClick = {
                                                        val list = extras.toMutableList()
                                                        val defaultVal = when (t) {
                                                            "boolean" -> "true"
                                                            "null" -> ""
                                                            else -> ""
                                                        }
                                                        list[idx] = extra.copy(type = t, values = listOf(defaultVal))
                                                        extras = list
                                                        expandedType = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(onClick = {
                                        val list = extras.toMutableList()
                                        list.removeAt(idx)
                                        extras = list
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.extra_delete))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                when (extra.type) {
                                    "boolean" -> {
                                        val boolValue = extra.values.firstOrNull()?.toBooleanStrictOrNull() ?: true
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                stringResource(R.string.type_boolean),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Switch(
                                                checked = boolValue,
                                                onCheckedChange = { checked ->
                                                    val list = extras.toMutableList()
                                                    list[idx] = extra.copy(values = listOf(checked.toString()))
                                                    extras = list
                                                }
                                            )
                                        }
                                    }
                                    "null" -> {
                                        Text(
                                            stringResource(R.string.type_null),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    else -> {
                                        OutlinedTextField(
                                            value = extra.values.firstOrNull() ?: "",
                                            onValueChange = { newValue ->
                                                val list = extras.toMutableList()
                                                list[idx] = extra.copy(values = listOf(newValue))
                                                extras = list
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text(stringResource(R.string.extra_value)) },
                                            minLines = 1,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = when (extra.type) {
                                                    "integer", "long" -> KeyboardType.Number
                                                    "decimal" -> KeyboardType.Decimal
                                                    else -> KeyboardType.Text
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
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
private fun CategoryListEditor(
    items: List<String>,
    onItemsChange: (List<String>) -> Unit,
    label: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onItemsChange(items + "") }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.extra_add))
                }
            }
            items.forEachIndexed { idx, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = item,
                        onValueChange = { newVal ->
                            val list = items.toMutableList()
                            list[idx] = newVal
                            onItemsChange(list)
                        },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    IconButton(onClick = {
                        val list = items.toMutableList()
                        list.removeAt(idx)
                        onItemsChange(list)
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.extra_delete))
                    }
                }
            }
        }
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
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
