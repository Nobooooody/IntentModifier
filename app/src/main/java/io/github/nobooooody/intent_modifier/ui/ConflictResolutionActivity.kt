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
import androidx.compose.material3.HorizontalDivider
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
import io.github.nobooooody.intent_modifier.data.NormalRule
import org.json.JSONArray
import org.json.JSONObject

class ConflictResolutionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONFLICT_JAVA_RULES = "conflict_java_rules"
        const val EXTRA_CONFLICT_NORMAL_RULES = "conflict_normal_rules"
        const val EXTRA_CURRENT_JAVA_RULES = "current_java_rules"
        const val EXTRA_CURRENT_NORMAL_RULES = "current_normal_rules"
        const val EXTRA_NEW_JAVA_RULES = "new_java_rules"
        const val EXTRA_NEW_NORMAL_RULES = "new_normal_rules"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conflictJavaJson = intent.getStringExtra(EXTRA_CONFLICT_JAVA_RULES) ?: "[]"
        val conflictNormalJson = intent.getStringExtra(EXTRA_CONFLICT_NORMAL_RULES) ?: "[]"
        val currentJavaJson = intent.getStringExtra(EXTRA_CURRENT_JAVA_RULES) ?: "[]"
        val currentNormalJson = intent.getStringExtra(EXTRA_CURRENT_NORMAL_RULES) ?: "[]"
        val newJavaJson = intent.getStringExtra(EXTRA_NEW_JAVA_RULES) ?: "[]"
        val newNormalJson = intent.getStringExtra(EXTRA_NEW_NORMAL_RULES) ?: "[]"

        val conflictJavaRules = parseJavaRules(conflictJavaJson)
        val conflictNormalRules = parseNormalRules(conflictNormalJson)
        val currentJavaRules = parseJavaRules(currentJavaJson)
        val currentNormalRules = parseNormalRules(currentNormalJson)
        val newJavaRules = parseJavaRules(newJavaJson)
        val newNormalRules = parseNormalRules(newNormalJson)

        if (conflictJavaRules.isEmpty() && conflictNormalRules.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            IntentModifierTheme {
                ConflictResolutionScreen(
                    conflictJavaRules = conflictJavaRules,
                    conflictNormalRules = conflictNormalRules,
                    currentJavaRules = currentJavaRules,
                    currentNormalRules = currentNormalRules,
                    newJavaRules = newJavaRules,
                    newNormalRules = newNormalRules,
                    onResolved = { resolvedJava, resolvedNormal ->
                        ModifierRepository(this@ConflictResolutionActivity).apply {
                            saveJavaCodeRules(resolvedJava)
                            saveNormalRules(resolvedNormal)
                        }
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

    private fun parseJavaRules(json: String): List<JavaCodeRule> {
        val arr = JSONArray(json)
        val result = mutableListOf<JavaCodeRule>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("_type", "java_code") == "java_code") {
                result.add(JavaCodeRule(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    enabled = obj.optBoolean("enabled", true),
                    name = obj.optString("name", ""),
                    targetPackages = optStringList(obj, "targetPackages"),
                    imports = obj.optString("imports", ""),
                    members = obj.optString("members", ""),
                    condition = obj.optString("condition", ""),
                    action = obj.optString("action", ""),
                    priority = obj.optInt("priority", 0)
                ))
            }
        }
        return result
    }

    private fun parseNormalRules(json: String): List<NormalRule> {
        val arr = JSONArray(json)
        val result = mutableListOf<NormalRule>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("_type", "") == "normal") {
                result.add(NormalRule(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    enabled = obj.optBoolean("enabled", true),
                    name = obj.optString("name", ""),
                    targetPackages = optStringList(obj, "targetPackages"),
                    blockSubsequent = obj.optBoolean("blockSubsequent", true),
                    priority = obj.optInt("priority", 0),
                    matchAction = optNullableString(obj, "matchAction"),
                    matchData = optNullableString(obj, "matchData"),
                    matchPackage = optNullableString(obj, "matchPackage"),
                    matchClass = optNullableString(obj, "matchClass"),
                    matchCategories = optStringList(obj, "matchCategories"),
                    matchType = optNullableString(obj, "matchType"),
                    customAction = optNullableString(obj, "customAction"),
                    customData = optNullableString(obj, "customData"),
                    customPackage = optNullableString(obj, "customPackage"),
                    customClass = optNullableString(obj, "customClass"),
                    customFlags = if (obj.has("customFlags")) obj.getInt("customFlags") else null,
                    customCategories = optStringList(obj, "customCategories"),
                    replaceCategories = obj.optBoolean("replaceCategories", false),
                    replaceExtras = obj.optBoolean("replaceExtras", false),
                    customType = optNullableString(obj, "customType"),
                    extras = parseExtras(obj.optJSONArray("extras"))
                ))
            }
        }
        return result
    }

    private fun optStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
    }

    private fun optNullableString(obj: JSONObject, key: String): String? {
        val v = obj.optString(key, "")
        return v.ifEmpty { null }
    }

    private fun parseExtras(json: JSONArray?): List<io.github.nobooooody.intent_modifier.data.ExtraItem> {
        if (json == null) return emptyList()
        val result = mutableListOf<io.github.nobooooody.intent_modifier.data.ExtraItem>()
        for (i in 0 until json.length()) {
            val extraObj = json.getJSONObject(i)
            val valuesJson = extraObj.optJSONArray("values")
            val values = if (valuesJson != null) {
                (0 until valuesJson.length()).map { valuesJson.getString(it) }
            } else {
                listOf(extraObj.optString("value", ""))
            }
            result.add(io.github.nobooooody.intent_modifier.data.ExtraItem(
                key = extraObj.getString("key"),
                type = extraObj.getString("type"),
                values = values
            ))
        }
        return result
    }
}

private sealed class ConflictItem {
    abstract val name: String
    abstract val ruleId: String
    abstract val action: ConflictAction

    data class JavaConflict(
        val rule: JavaCodeRule,
        override val action: ConflictAction = ConflictAction.NONE
    ) : ConflictItem() {
        override val name get() = rule.name
        override val ruleId get() = rule.id
        fun copy(action: ConflictAction) = JavaConflict(rule, action)
    }

    data class NormalConflict(
        val rule: NormalRule,
        override val action: ConflictAction = ConflictAction.NONE
    ) : ConflictItem() {
        override val name get() = rule.name
        override val ruleId get() = rule.id
        fun copy(action: ConflictAction) = NormalConflict(rule, action)
    }
}

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
    conflictJavaRules: List<JavaCodeRule>,
    conflictNormalRules: List<NormalRule>,
    currentJavaRules: List<JavaCodeRule>,
    currentNormalRules: List<NormalRule>,
    newJavaRules: List<JavaCodeRule>,
    newNormalRules: List<NormalRule>,
    onResolved: (List<JavaCodeRule>, List<NormalRule>) -> Unit,
    onCancel: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val conflictItems = remember { mutableStateListOf<ConflictItem>() }
    var applyToAll by remember { mutableStateOf(false) }
    var applyAllAction by remember { mutableIntStateOf(0) }

    if (conflictItems.isEmpty()) {
        conflictItems.addAll(conflictJavaRules.map { ConflictItem.JavaConflict(it) })
        conflictItems.addAll(conflictNormalRules.map { ConflictItem.NormalConflict(it) })
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
                stringResource(R.string.import_conflict_message, conflictItems.size),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(conflictItems, key = { _, item -> item.name }) { index, item ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val oldJava = currentJavaRules.find { it.id == item.ruleId }
                                val oldNormal = currentNormalRules.find { it.id == item.ruleId }
                                RuleContentCard(
                                    label = stringResource(R.string.conflict_existing),
                                    javaRule = oldJava,
                                    normalRule = oldNormal,
                                    modifier = Modifier.weight(1f)
                                )
                                when (item) {
                                    is ConflictItem.JavaConflict -> {
                                        RuleContentCard(
                                            label = stringResource(R.string.conflict_imported),
                                            javaRule = item.rule,
                                            normalRule = null,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    is ConflictItem.NormalConflict -> {
                                        RuleContentCard(
                                            label = stringResource(R.string.conflict_imported),
                                            javaRule = null,
                                            normalRule = item.rule,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
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
                                                conflictItems[index] = when (val existing = conflictItems[index]) {
                                                    is ConflictItem.JavaConflict -> existing.copy(action = action)
                                                    is ConflictItem.NormalConflict -> existing.copy(action = action)
                                                }
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
                            conflictItems[i] = when (val existing = conflictItems[i]) {
                                is ConflictItem.JavaConflict -> existing.copy(action = action)
                                is ConflictItem.NormalConflict -> existing.copy(action = action)
                            }
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
                                            conflictItems[j] = when (val existing = conflictItems[j]) {
                                                is ConflictItem.JavaConflict -> existing.copy(action = action)
                                                is ConflictItem.NormalConflict -> existing.copy(action = action)
                                            }
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

                        val resolvedJava = mutableListOf<JavaCodeRule>()
                        val resolvedNormal = mutableListOf<NormalRule>()
                        val workingCurrentJava = currentJavaRules.toMutableList()
                        val workingCurrentNormal = currentNormalRules.toMutableList()
                        val workingNewJava = newJavaRules.toMutableList()
                        val workingNewNormal = newNormalRules.toMutableList()

                        fun removeFromAnyJava(id: String, java: MutableList<JavaCodeRule>, normal: MutableList<NormalRule>) {
                            java.removeAll { it.id == id }
                            normal.removeAll { it.id == id }
                        }

                        fun findExistingJava(id: String, java: List<JavaCodeRule>, normal: List<NormalRule>): JavaCodeRule? {
                            return java.find { it.id == id } ?: normal.find { it.id == id }?.let {
                                JavaCodeRule(id = it.id, enabled = it.enabled, name = it.name, priority = it.priority)
                            }
                        }

                        fun findExistingNormal(id: String, normal: List<NormalRule>, java: List<JavaCodeRule>): NormalRule? {
                            return normal.find { it.id == id } ?: java.find { it.id == id }?.let {
                                NormalRule(id = it.id, enabled = it.enabled, name = it.name, priority = it.priority)
                            }
                        }

                        fun resolveJavaConflict(
                            item: ConflictItem.JavaConflict,
                            currentJava: MutableList<JavaCodeRule>,
                            currentNormal: MutableList<NormalRule>,
                            newJava: MutableList<JavaCodeRule>,
                            resolvedJava: MutableList<JavaCodeRule>
                        ) {
                            when (item.action) {
                                ConflictAction.REPLACE -> {
                                    removeFromAnyJava(item.rule.id, currentJava, currentNormal)
                                    resolvedJava.add(item.rule)
                                }
                                ConflictAction.IGNORE -> {}
                                ConflictAction.RENAME_OLD -> {
                                    val existing = findExistingJava(item.rule.id, currentJava, currentNormal)
                                    if (existing != null) {
                                        removeFromAnyJava(item.rule.id, currentJava, currentNormal)
                                        var newName = "${item.rule.name}_old"
                                        var counter = 1
                                        while (currentJava.any { it.name == newName } || newJava.any { it.name == newName } || resolvedJava.any { it.name == newName }) {
                                            newName = "${item.rule.name}_old_$counter"; counter++
                                        }
                                        resolvedJava.add(existing.copy(name = newName, id = java.util.UUID.randomUUID().toString()))
                                    }
                                    resolvedJava.add(item.rule)
                                }
                                ConflictAction.RENAME_NEW -> {
                                    var newName = "${item.rule.name}_new"
                                    var counter = 1
                                    while (currentJava.any { it.name == newName } || newJava.any { it.name == newName } || resolvedJava.any { it.name == newName }) {
                                        newName = "${item.rule.name}_new_$counter"; counter++
                                    }
                                    resolvedJava.add(item.rule.copy(name = newName, id = java.util.UUID.randomUUID().toString()))
                                }
                                else -> {}
                            }
                        }

                        fun resolveNormalConflict(
                            item: ConflictItem.NormalConflict,
                            currentNormal: MutableList<NormalRule>,
                            currentJava: MutableList<JavaCodeRule>,
                            newNormal: MutableList<NormalRule>,
                            resolvedNormal: MutableList<NormalRule>
                        ) {
                            when (item.action) {
                                ConflictAction.REPLACE -> {
                                    removeFromAnyJava(item.rule.id, currentJava, currentNormal)
                                    resolvedNormal.add(item.rule)
                                }
                                ConflictAction.IGNORE -> {}
                                ConflictAction.RENAME_OLD -> {
                                    val existing = findExistingNormal(item.rule.id, currentNormal, currentJava)
                                    if (existing != null) {
                                        removeFromAnyJava(item.rule.id, currentJava, currentNormal)
                                        var newName = "${item.rule.name}_old"
                                        var counter = 1
                                        while (currentNormal.any { it.name == newName } || newNormal.any { it.name == newName } || resolvedNormal.any { it.name == newName }) {
                                            newName = "${item.rule.name}_old_$counter"; counter++
                                        }
                                        resolvedNormal.add(existing.copy(name = newName, id = java.util.UUID.randomUUID().toString()))
                                    }
                                    resolvedNormal.add(item.rule)
                                }
                                ConflictAction.RENAME_NEW -> {
                                    var newName = "${item.rule.name}_new"
                                    var counter = 1
                                    while (currentNormal.any { it.name == newName } || newNormal.any { it.name == newName } || resolvedNormal.any { it.name == newName }) {
                                        newName = "${item.rule.name}_new_$counter"; counter++
                                    }
                                    resolvedNormal.add(item.rule.copy(name = newName, id = java.util.UUID.randomUUID().toString()))
                                }
                                else -> {}
                            }
                        }

                        for (item in conflictItems) {
                            when (item) {
                                is ConflictItem.JavaConflict -> resolveJavaConflict(item, workingCurrentJava, workingCurrentNormal, workingNewJava, resolvedJava)
                                is ConflictItem.NormalConflict -> resolveNormalConflict(item, workingCurrentNormal, workingCurrentJava, workingNewNormal, resolvedNormal)
                            }
                        }

                        workingCurrentJava.addAll(workingNewJava)
                        workingCurrentJava.addAll(resolvedJava)
                        workingCurrentNormal.addAll(workingNewNormal)
                        workingCurrentNormal.addAll(resolvedNormal)

                        onResolved(workingCurrentJava, workingCurrentNormal)
                        Toast.makeText(ctx, ctx.getString(R.string.import_success, newJavaRules.size + newNormalRules.size + resolvedJava.size + resolvedNormal.size), Toast.LENGTH_SHORT).show()
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
private fun RuleContentCard(label: String, javaRule: JavaCodeRule?, normalRule: NormalRule?, modifier: Modifier = Modifier) {
    val labelNone = stringResource(R.string.field_none)
    val labelNotFound = stringResource(R.string.conflict_not_found)

    Card(modifier = modifier) {
        Column(Modifier.padding(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))

            if (javaRule != null) {
                Text(
                    javaRule.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                val labelImports = stringResource(R.string.field_imports)
                val labelMembers = stringResource(R.string.field_members)
                val labelCondition = stringResource(R.string.field_condition)
                val labelAction = stringResource(R.string.field_action)
                FieldSection(label = labelImports, code = javaRule.imports, placeholder = labelNone)
                FieldSection(label = labelMembers, code = javaRule.members, placeholder = labelNone)
                FieldSection(label = labelCondition, code = javaRule.condition, placeholder = labelNone)
                FieldSection(label = labelAction, code = javaRule.action, placeholder = labelNone)
            } else if (normalRule != null) {
                Text(
                    normalRule.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                FieldSection(label = stringResource(R.string.match_action), code = normalRule.matchAction ?: "", placeholder = labelNone)
                FieldSection(label = stringResource(R.string.match_data), code = normalRule.matchData ?: "", placeholder = labelNone)
                FieldSection(label = stringResource(R.string.match_package), code = normalRule.matchPackage ?: "", placeholder = labelNone)
                FieldSection(label = stringResource(R.string.match_class), code = normalRule.matchClass ?: "", placeholder = labelNone)
                if (normalRule.matchCategories.isNotEmpty()) {
                    FieldSection(label = stringResource(R.string.match_categories_short), code = normalRule.matchCategories.joinToString(", "), placeholder = labelNone)
                }
                FieldSection(label = stringResource(R.string.match_type), code = normalRule.matchType ?: "", placeholder = labelNone)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FieldSection(label = stringResource(R.string.custom_action), code = normalRule.customAction ?: "", placeholder = labelNone)
                FieldSection(label = stringResource(R.string.custom_data), code = normalRule.customData ?: "", placeholder = labelNone)
                FieldSection(label = stringResource(R.string.custom_package), code = normalRule.customPackage ?: "", placeholder = labelNone)
                FieldSection(label = stringResource(R.string.custom_class), code = normalRule.customClass ?: "", placeholder = labelNone)
                if (normalRule.customFlags != null) {
                    FieldSection(label = stringResource(R.string.custom_flags), code = normalRule.customFlags.toString(), placeholder = labelNone)
                }
                if (normalRule.customCategories.isNotEmpty()) {
                    FieldSection(label = stringResource(R.string.custom_categories_short), code = normalRule.customCategories.joinToString(", "), placeholder = labelNone)
                }
                FieldSection(label = stringResource(R.string.custom_type), code = normalRule.customType ?: "", placeholder = labelNone)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FieldSection(label = stringResource(R.string.block_subsequent), code = if (normalRule.blockSubsequent) "true" else "false", placeholder = labelNone)
                if (normalRule.targetPackages.isNotEmpty()) {
                    FieldSection(label = stringResource(R.string.target_packages), code = normalRule.targetPackages.joinToString(", "), placeholder = labelNone)
                }
                if (normalRule.extras.isNotEmpty()) {
                    FieldSection(label = stringResource(R.string.extras), code = normalRule.extras.joinToString("; ") { "${it.key} (${it.type})" }, placeholder = labelNone)
                }
            } else {
                Text(
                    labelNotFound,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FieldSection(label: String, code: String, placeholder: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(Modifier.height(2.dp))
    Text(
        code.ifBlank { placeholder },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
