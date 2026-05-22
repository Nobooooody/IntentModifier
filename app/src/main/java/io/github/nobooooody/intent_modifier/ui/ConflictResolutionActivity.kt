package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import org.json.JSONArray

class ConflictResolutionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONFLICT_RULES = "conflict_rules"
        const val EXTRA_CURRENT_RULES = "current_rules"
        const val EXTRA_NEW_RULES = "new_rules"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conflictRulesJson = intent.getStringExtra(EXTRA_CONFLICT_RULES) ?: return finish()
        val currentRulesJson = intent.getStringExtra(EXTRA_CURRENT_RULES) ?: return finish()
        val newRulesJson = intent.getStringExtra(EXTRA_NEW_RULES) ?: return finish()

        val conflictRules = parseRules(conflictRulesJson)
        val currentRules = parseRules(currentRulesJson)
        val newRules = parseRules(newRulesJson)

        setContent {
            IntentModifierTheme {
                ConflictResolutionScreen(
                    conflictRules = conflictRules,
                    currentRules = currentRules,
                    newRules = newRules,
                    onResolved = { resolvedRules ->
                        ModifierRepository(this@ConflictResolutionActivity).saveJavaCodeRules(resolvedRules)
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    private fun parseRules(json: String): List<JavaCodeRule> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            JavaCodeRule(
                enabled = obj.optBoolean("enabled", true),
                name = obj.optString("name", ""),
                imports = obj.optString("imports", ""),
                members = obj.optString("members", ""),
                condition = obj.optString("condition", ""),
                action = obj.optString("action", ""),
                priority = obj.optInt("priority", 0)
            )
        }
    }
}

private data class ConflictItem(
    val rule: JavaCodeRule,
    var action: ConflictAction
)

private enum class ConflictAction {
    NONE, REPLACE, IGNORE, RENAME_OLD, RENAME_NEW;

    companion object {
        fun fromIndex(index: Int): ConflictAction = when (index) {
            0 -> REPLACE; 1 -> IGNORE; 2 -> RENAME_OLD; 3 -> RENAME_NEW
            else -> NONE
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictResolutionScreen(
    conflictRules: List<JavaCodeRule>,
    currentRules: List<JavaCodeRule>,
    newRules: List<JavaCodeRule>,
    onResolved: (List<JavaCodeRule>) -> Unit,
    onCancel: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val conflictItems = remember { mutableStateListOf<ConflictItem>() }
    var applyToAll by remember { mutableStateOf(false) }
    var applyAllAction by remember { mutableIntStateOf(0) }

    if (conflictItems.isEmpty()) {
        conflictItems.addAll(conflictRules.map { ConflictItem(it, ConflictAction.NONE) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_conflict_title)) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.import_conflict_message, conflictRules.size),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(conflictItems, key = { _, item -> item.rule.name }) { index, item ->
                    val oldRule = currentRules.find { it.name == item.rule.name }

                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                item.rule.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuleContentCard(
                                    label = stringResource(R.string.conflict_existing),
                                    rule = oldRule,
                                    modifier = Modifier.weight(1f)
                                )
                                RuleContentCard(
                                    label = stringResource(R.string.conflict_imported),
                                    rule = item.rule,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            val actionLabels = mapOf(
                                ConflictAction.REPLACE to stringResource(R.string.conflict_action_replace_short),
                                ConflictAction.IGNORE to stringResource(R.string.conflict_action_ignore_short),
                                ConflictAction.RENAME_OLD to stringResource(R.string.conflict_action_rename_old_short),
                                ConflictAction.RENAME_NEW to stringResource(R.string.conflict_action_rename_new_short)
                            )
                            var showActionMenu by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { showActionMenu = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        actionLabels[conflictItems[index].action]
                                            ?: stringResource(R.string.conflict_choose_action),
                                        maxLines = 1
                                    )
                                }
                                DropdownMenu(
                                    expanded = showActionMenu,
                                    onDismissRequest = { showActionMenu = false }
                                ) {
                                    actionLabels.forEach { (action, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                conflictItems[index] = conflictItems[index].copy(action = action)
                                                showActionMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Checkbox(checked = applyToAll, onCheckedChange = { checked ->
                    applyToAll = checked
                    if (checked) {
                        val action = ConflictAction.fromIndex(applyAllAction)
                        for (i in conflictItems.indices) {
                            conflictItems[i] = conflictItems[i].copy(action = action)
                        }
                    }
                })
                Text(stringResource(R.string.import_conflict_apply_to_all), modifier = Modifier.weight(1f))

                if (applyToAll) {
                    val allActions = listOf(
                        stringResource(R.string.conflict_action_replace_short),
                        stringResource(R.string.conflict_action_ignore_short),
                        stringResource(R.string.conflict_action_rename_old_short),
                        stringResource(R.string.conflict_action_rename_new_short)
                    )
                    var showActions by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { showActions = true }) {
                            Text(allActions[applyAllAction])
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            allActions.forEachIndexed { i, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        applyAllAction = i
                                        showActions = false
                                        val action = ConflictAction.fromIndex(i)
                                        for (j in conflictItems.indices) {
                                            conflictItems[j] = conflictItems[j].copy(action = action)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        var allResolved = true
                        for (item in conflictItems) {
                            if (item.action == ConflictAction.NONE) { allResolved = false; break }
                        }
                        if (!allResolved) {
                            Toast.makeText(ctx, R.string.import_conflict_not_resolved, Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val resolvedRules = mutableListOf<JavaCodeRule>()
                        val workingCurrent = currentRules.toMutableList()
                        val workingNew = newRules.toMutableList()

                        for (item in conflictItems) {
                            when (item.action) {
                                ConflictAction.REPLACE -> {
                                    workingCurrent.removeAll { it.name == item.rule.name }
                                    resolvedRules.add(item.rule)
                                }
                                ConflictAction.IGNORE -> {}
                                ConflictAction.RENAME_OLD -> {
                                    val existing = workingCurrent.find { it.name == item.rule.name }
                                    if (existing != null) {
                                        workingCurrent.removeAll { it.name == item.rule.name }
                                        var newName = "${item.rule.name}_old"
                                        var counter = 1
                                        while (workingCurrent.any { it.name == newName } || workingNew.any { it.name == newName } || resolvedRules.any { it.name == newName }) {
                                            newName = "${item.rule.name}_old_$counter"; counter++
                                        }
                                        resolvedRules.add(existing.copy(name = newName))
                                    }
                                    resolvedRules.add(item.rule)
                                }
                                ConflictAction.RENAME_NEW -> {
                                    var newName = "${item.rule.name}_new"
                                    var counter = 1
                                    while (workingCurrent.any { it.name == newName } || workingNew.any { it.name == newName } || resolvedRules.any { it.name == newName }) {
                                        newName = "${item.rule.name}_new_$counter"; counter++
                                    }
                                    resolvedRules.add(item.rule.copy(name = newName))
                                }
                                else -> {}
                            }
                        }

                        workingCurrent.addAll(workingNew)
                        workingCurrent.addAll(resolvedRules)
                        onResolved(workingCurrent)
                        Toast.makeText(ctx, ctx.getString(R.string.import_success, newRules.size + resolvedRules.size), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RuleContentCard(label: String, rule: JavaCodeRule?, modifier: Modifier = Modifier) {
    val labelImports = stringResource(R.string.field_imports)
    val labelMembers = stringResource(R.string.field_members)
    val labelCondition = stringResource(R.string.field_condition)
    val labelAction = stringResource(R.string.field_action)
    val labelNone = stringResource(R.string.field_none)
    val labelNotFound = stringResource(R.string.conflict_not_found)

    Card(modifier = modifier) {
        Column(Modifier.padding(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))

            val content = rule?.let { r ->
                buildString {
                    appendLine("$labelImports:")
                    appendLine(if (r.imports.isNotBlank()) r.imports else labelNone)
                    appendLine()
                    appendLine("$labelMembers:")
                    appendLine(if (r.members.isNotBlank()) r.members else labelNone)
                    appendLine()
                    appendLine("$labelCondition:")
                    appendLine(if (r.condition.isNotBlank()) r.condition else labelNone)
                    appendLine()
                    appendLine("$labelAction:")
                    append(if (r.action.isNotBlank()) r.action else labelNone)
                }
            } ?: labelNotFound

            Text(
                content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
