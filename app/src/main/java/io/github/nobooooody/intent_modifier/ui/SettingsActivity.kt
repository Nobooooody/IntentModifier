package io.github.nobooooody.intent_modifier.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.LinearLayout
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.AppIntentRule
import io.github.nobooooody.intent_modifier.data.ExtraItem
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    lateinit var repository: ModifierRepository
    private lateinit var adapter: AppRuleAdapter
    private lateinit var recyclerView: RecyclerView
    lateinit var rules: Map<String, AppIntentRule>
    private lateinit var emptyView: LinearLayout
    private lateinit var fabAdd: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra("package")?.let { pkg ->
                Log.d("IntentMod", "Adding rule for: $pkg")
                val newRule = AppIntentRule()
                showEditDialog(pkg, newRule)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            supportActionBar?.title = getString(R.string.settings_title)
        }

        repository = ModifierRepository(this)

        recyclerView = findViewById(R.id.recyclerView)
        if (recyclerView != null) {
            recyclerView.layoutManager = LinearLayoutManager(this)
        }
        adapter = AppRuleAdapter(
            onEdit = { pkg, rule -> showEditDialog(pkg, rule) },
            onDelete = { pkg ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_rule)
                .setMessage(getString(R.string.delete_rule_message, pkg))
                .setPositiveButton(R.string.delete) { _, _ -> deleteRule(pkg) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        )
        if (recyclerView != null) {
            recyclerView.adapter = adapter
        }
        
        emptyView = findViewById(R.id.emptyView)
        
        fabAdd = findViewById(R.id.fabAdd)
        fabAdd?.setOnClickListener {
            appPickerLauncher.launch(Intent(this, AppPickerActivity::class.java as Class<android.app.Activity>))
        }

        loadRules()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_file -> {
                exportToFile()
                true
            }
            R.id.action_export_clipboard -> {
                exportToClipboard()
                true
            }
            R.id.action_import_file -> {
                importFromFile()
                true
            }
            R.id.action_import_clipboard -> {
                importFromClipboard()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

private var pendingExportJson: String = ""
    private var pendingExportFileName: String = ""

    private fun exportToFile() {
        pendingExportJson = repository.getRulesJson()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        pendingExportFileName = "intent_modifier_$timestamp.json"
        createDocumentLauncher.launch(pendingExportFileName)
    }

    private val createDocumentLauncher = registerForActivityResult(
        CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(pendingExportJson.toByteArray())
                }
                Toast.makeText(this, getString(R.string.exported_to_file, pendingExportFileName), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
                Log.e("IntentMod", "Export failed", e)
            }
        }
    }

    private fun exportToClipboard() {
        try {
            val json = repository.getRulesJson()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Intent Modifier Rules", json)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.exported_to_clipboard, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
            Log.e("IntentMod", "Export to clipboard failed", e)
        }
    }

    private var pendingImportJson: String = ""

    private fun importFromFile() {
        openDocumentLauncher.launch(arrayOf("application/json"))
    }

    private fun importFromClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                pendingImportJson = clip.getItemAt(0).text.toString()
                showImportConflictDialog()
            } else {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            Log.e("IntentMod", "Import from clipboard failed", e)
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    pendingImportJson = input.bufferedReader().readText()
                }
                showImportConflictDialog()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                Log.e("IntentMod", "Import from file failed", e)
            }
        }
    }

    private fun showImportConflictDialog() {
        var selectedOption = 0
        val conflictOptions = arrayOf(
            getString(R.string.import_conflict_replace),
            getString(R.string.import_conflict_merge_new),
            getString(R.string.import_conflict_keep_old)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_conflict_title)
            .setSingleChoiceItems(conflictOptions, 0) { _, which ->
                selectedOption = which
            }
            .setPositiveButton(R.string.action_import) { dialog, _ ->
                val mergedRules = mergeImportRules(selectedOption)
                repository.saveAllRules(mergedRules)
                loadRules()
                val count = mergedRules.size
                Toast.makeText(this, getString(R.string.import_success, count), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun mergeImportRules(conflictMode: Int): Map<String, AppIntentRule> {
        val importedRules = repository.parseRulesJson(pendingImportJson)
        val result = mutableMapOf<String, AppIntentRule>()

        // 先加入所有旧规则
        rules.forEach { (pkg, rule) ->
            result[pkg] = rule
        }

        // 根据冲突模式处理
        when (conflictMode) {
            0 -> { // 替换全部：清空旧规则，只用导入的
                result.clear()
                importedRules.forEach { (pkg, rule) ->
                    result[pkg] = rule
                }
            }
            1 -> { // 冲突时保留新规则：对冲突的包用新规则，同时添加不冲突的新规则
                importedRules.forEach { (pkg, rule) ->
                    result[pkg] = rule
                }
            }
            2 -> { // 冲突时保留旧规则：添加不冲突的新规则
                importedRules.forEach { (pkg, rule) ->
                    if (!result.containsKey(pkg)) {
                        result[pkg] = rule
                    }
                }
            }
        }
        return result
    }

    fun loadRules() {
        rules = repository.getRules()
        Log.d("IntentMod", "loadRules: ${rules.size} rules, keys: ${rules.keys}")
        adapter.submitList(rules)
        Log.d("IntentMod", "Adapter itemCount: ${adapter.itemCount}")
        emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (rules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun deleteRule(packageName: String) {
        repository.saveRule(packageName, null)
        loadRules()
    }

    private val extraTypes = listOf(
        "Boolean", "ComponentName", "Decimal Number", "Integer", 
        "Long", "Null", "String", "URI"
    )

    private fun setInputType(extraView: View, type: String) {
        Log.d("IntentMod", "setInputType called: type=$type")
        val valueInputLayout = extraView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.valueInputLayout)
        val switchBoolean = extraView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchBoolean)
        when (type) {
            "Boolean" -> {
                Log.d("IntentMod", "Boolean case: showing switch")
                valueInputLayout.visibility = android.view.View.GONE
                switchBoolean.visibility = android.view.View.VISIBLE
                switchBoolean.text = getString(R.string.type_boolean)
            }
            "Null" -> {
                valueInputLayout.visibility = android.view.View.GONE
                switchBoolean.visibility = android.view.View.GONE
            }
            else -> {
                valueInputLayout.visibility = android.view.View.VISIBLE
                switchBoolean.visibility = android.view.View.GONE
                val editText = valueInputLayout.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraValue)
                editText.inputType = when (type) {
                    "Integer", "Long" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    "Decimal Number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    "URI" -> InputType.TYPE_TEXT_VARIATION_URI
                    else -> InputType.TYPE_CLASS_TEXT
                }
            }
        }
    }

    private fun validateExtra(view: View, type: String): String? {
        val keyInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraKey)
        val key = keyInput.text.toString()
        
        if (key.isBlank()) {
            keyInput.error = getString(R.string.error_key_required)
            return null
        }

        val switchBoolean = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchBoolean)
        
        when (type) {
            "Boolean" -> {
                return if (switchBoolean.isChecked) "true" else "false"
            }
            "Null" -> return null
            "Integer" -> {
                val valueInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraValue)
                val value = valueInput.text.toString()
                return value.toIntOrNull()?.toString() ?: run {
                    valueInput.error = getString(R.string.error_invalid_integer)
                    null
                }
            }
            "Long" -> {
                val valueInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraValue)
                val value = valueInput.text.toString()
                return value.toLongOrNull()?.toString() ?: run {
                    valueInput.error = getString(R.string.error_invalid_long)
                    null
                }
            }
            "Decimal Number" -> {
                val valueInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraValue)
                val value = valueInput.text.toString()
                return value.toDoubleOrNull()?.toString() ?: run {
                    valueInput.error = getString(R.string.error_invalid_number)
                    null
                }
            }
            else -> {
                val valueInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraValue)
                val value = valueInput.text.toString()
                if (value.isBlank()) {
                    valueInput.error = getString(R.string.error_value_required)
                    return null
                }
                return value
            }
        }
    }

    private fun showEditDialog(packageName: String, rule: AppIntentRule) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_rule, null)
        
        var dialog: androidx.appcompat.app.AlertDialog? = null
        
        val actionInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputAction)
        val dataInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputData)
        val packageInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputPackage)
        val classInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputClass)
        val extrasContainer = dialogView.findViewById<LinearLayout>(R.id.extrasContainer)
        val addExtraButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonAddExtra)

        actionInput.setText(rule.customAction ?: "")
        dataInput.setText(rule.customData ?: "")
        packageInput.setText(rule.customPackage ?: "")
        classInput.setText(rule.customClass ?: "")

        val extraViews = mutableListOf<View>()

        fun addExtraView(extra: ExtraItem? = null) {
            val extraView = LayoutInflater.from(this).inflate(R.layout.item_extra, extrasContainer, false)
            val keyInput = extraView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraKey)
            val typeSpinner = extraView.findViewById<AutoCompleteTextView>(R.id.spinnerType)
            val valueInputLayout = extraView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.valueInputLayout)
            val valueInput = extraView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraValue)
            val switchBoolean = extraView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchBoolean)
            val removeButton = extraView.findViewById<ImageButton>(R.id.buttonRemoveExtra)

            typeSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, extraTypes))
            
            val currentType = extra?.type ?: "String"
            typeSpinner.setText(currentType, false)
            setInputType(extraView, currentType)

            extra?.let {
                keyInput.setText(it.key)
                if (it.type == "Boolean") {
                    switchBoolean.isChecked = it.value == "true"
                } else if (it.type != "Null") {
                    valueInput.setText(it.value)
                }
            }

            typeSpinner.setOnItemClickListener { _, _, position, _ ->
                Log.d("IntentMod", "Type selected: ${extraTypes[position]}")
                setInputType(extraView, extraTypes[position])
            }

            removeButton.setOnClickListener {
                extrasContainer.removeView(extraView)
                extraViews.remove(extraView)
            }

            extrasContainer.addView(extraView)
            extraViews.add(extraView)
        }

        rule.extras.forEach { addExtraView(it) }

        addExtraButton.setOnClickListener { addExtraView() }

        val appName = try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (e: Exception) {
            packageName
        }
        
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(appName)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val newExtras = extraViews.mapNotNull { view ->
                    val type = view.findViewById<AutoCompleteTextView>(R.id.spinnerType).text.toString()
                    val value = validateExtra(view, type) ?: return@setPositiveButton
                    val key = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExtraKey).text.toString()
                    ExtraItem(key, type, value)
                }
                val newRule = AppIntentRule(
                    customAction = actionInput.text?.toString()?.ifBlank { null },
                    customData = dataInput.text?.toString()?.ifBlank { null },
                    customPackage = packageInput.text?.toString()?.ifBlank { null },
                    customClass = classInput.text?.toString()?.ifBlank { null },
                    extras = newExtras
                )
                repository.saveRule(packageName, newRule)
                loadRules()
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}

class AppRuleAdapter(
    private val onEdit: (String, AppIntentRule) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<AppRuleAdapter.ViewHolder>() {

    private var rules: Map<String, AppIntentRule> = emptyMap()

    fun submitList(newRules: Map<String, AppIntentRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (packageName, rule) = rules.entries.toList()[position]
        holder.bind(packageName, rule)
    }

    override fun getItemCount() = rules.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container = itemView as com.google.android.material.card.MaterialCardView
        private val appNameText: TextView
        private val pkgText: TextView
        private val summaryText: TextView
        private val editButton: View
        private val deleteButton: View
        private val switchEnabled: MaterialSwitch

        init {
            appNameText = container.findViewById(R.id.textAppName)
            pkgText = container.findViewById(R.id.textPackageName)
            summaryText = container.findViewById(R.id.textSummary)
            editButton = container.findViewById(R.id.buttonEdit)
            deleteButton = container.findViewById(R.id.buttonDelete)
            switchEnabled = container.findViewById(R.id.switchEnabled)
        }

        fun bind(packageName: String, rule: AppIntentRule) {
            val appName = try {
                itemView.context.packageManager.getApplicationInfo(packageName, 0).loadLabel(itemView.context.packageManager).toString()
            } catch (e: Exception) {
                packageName
            }
            appNameText.text = appName
            pkgText.text = packageName
            val currentRule = rules[packageName]
            switchEnabled.isChecked = currentRule?.enabled ?: true
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                (itemView.context as? SettingsActivity)?.let { activity ->
                    val existingRule = activity.rules[packageName] ?: AppIntentRule()
                    val updatedRule = existingRule.copy(enabled = isChecked)
                    activity.repository.saveRule(packageName, updatedRule)
                }
            }
            val ctx = itemView.context
            val noModText = ctx.getString(R.string.no_modifications)
            summaryText.text = buildString {
                if (rule.customAction?.isNotBlank() == true) append(ctx.getString(R.string.summary_action, rule.customAction))
                if (rule.customPackage?.isNotBlank() == true) append(ctx.getString(R.string.summary_to, rule.customPackage))
                if (rule.customClass?.isNotBlank() == true) append(ctx.getString(R.string.summary_class, rule.customClass))
            }.ifBlank { noModText }
            
            editButton.setOnClickListener { onEdit(packageName, rule) }
            deleteButton.setOnClickListener { onDelete(packageName) }
        }
    }
}