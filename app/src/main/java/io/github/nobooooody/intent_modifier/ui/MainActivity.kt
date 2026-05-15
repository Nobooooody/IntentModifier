package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.HOOK_INSTRUMENTATION
import io.github.nobooooody.intent_modifier.data.HOOK_LAUNCHER3
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.LauncherHook
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.engine.RuleCompilationManager
import io.github.nobooooody.intent_modifier.engine.RuleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

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
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            showFragment(RulesFragment())
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_rules -> showFragment(RulesFragment())
                R.id.nav_launchers -> showFragment(LaunchersFragment())
                R.id.nav_settings -> showFragment(SettingsFragment())
            }
            true
        }
    }

    private var currentFragmentTag: String? = null

    private fun showFragment(fragment: Fragment) {
        currentFragmentTag = when (fragment) {
            is RulesFragment -> "rules"
            is LaunchersFragment -> "launchers"
            is SettingsFragment -> "settings"
            else -> currentFragmentTag
        }
        supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment).commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_rules, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_clipboard -> {
                exportToClipboard()
                true
            }
            R.id.action_export_file -> {
                exportToFileLauncher.launch("intent_modifier_rules.json")
                true
            }
            R.id.action_import_clipboard -> {
                importFromClipboard()
                true
            }
            R.id.action_import_file -> {
                importFromFileLauncher.launch(arrayOf("application/json"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val exportToFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportRulesToFile(it) }
    }

    private val importFromFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importRulesFromFile(it) }
    }

    private fun exportToClipboard() {
        try {
            val repo = ModifierRepository(this)
            val rulesJson = repo.getJavaCodeRulesJson()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("IntentModifierRules", rulesJson)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.exported_to_clipboard, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportRulesToFile(uri: Uri) {
        try {
            val repo = ModifierRepository(this)
            val rulesJson = repo.getJavaCodeRulesJson()
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(rulesJson.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()
                handleImportRules(text)
            } else {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importRulesFromFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val text = inputStream.bufferedReader().readText()
                handleImportRules(text)
                inputStream.close()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImportRules(jsonStr: String) {
        try {
            val importedRules = mutableListOf<JavaCodeRule>()
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    importedRules.add(JavaCodeRule(
                        enabled = obj.optBoolean("enabled", true),
                        name = name,
                        condition = obj.optString("condition", ""),
                        action = obj.optString("action", ""),
                        priority = obj.optInt("priority", 0)
                    ))
                }
            }
            if (importedRules.isEmpty()) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                return
            }

            val repo = ModifierRepository(this)
            val currentRules = repo.getJavaCodeRules().toMutableList()
            val existingNames = currentRules.map { it.name }.toSet()

            val newRules = importedRules.filter { it.name !in existingNames }
            val conflictRules = importedRules.filter { it.name in existingNames }
            val conflictCount = conflictRules.size

            if (conflictCount > 0) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.import_conflict_title)
                    .setMessage(getString(R.string.import_conflict_message, conflictCount))
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        showConflictResolutionDialog(conflictRules, currentRules, repo, newRules)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                currentRules.addAll(importedRules)
                repo.saveJavaCodeRules(currentRules)
                recompileRules()
                Toast.makeText(this, getString(R.string.import_success, importedRules.size), Toast.LENGTH_SHORT).show()
                refreshCurrentFragment()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Import failed", e)
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConflictResolutionDialog(
        conflictRules: List<JavaCodeRule>,
        currentRules: MutableList<JavaCodeRule>,
        repo: ModifierRepository,
        newRules: List<JavaCodeRule>
    ) {
        val conflictItems = conflictRules.map { ConflictItem(it, ConflictAction.NONE) }.toMutableList()
        var applyToAll: ConflictAction? = null

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_conflict_resolution, null)
        val recyclerView = view.findViewById<RecyclerView>(R.id.conflictRecyclerView)
        val checkBoxApplyAll = view.findViewById<android.widget.CheckBox>(R.id.checkBoxApplyAll)
        val spinnerApplyAll = view.findViewById<android.widget.Spinner>(R.id.spinnerApplyAll)
        spinnerApplyAll.visibility = View.GONE

        val adapter = ConflictResolutionAdapter(conflictItems) { index, action ->
            conflictItems[index] = conflictItems[index].copy(action = action)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val options = arrayOf(
            getString(R.string.conflict_action_replace),
            getString(R.string.conflict_action_ignore),
            getString(R.string.conflict_action_rename_old),
            getString(R.string.conflict_action_rename_new)
        )
        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinnerApplyAll.adapter = spinnerAdapter
        spinnerApplyAll.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (checkBoxApplyAll.isChecked && applyToAll != null) {
                    applyToAll = ConflictAction.fromIndex(position)
                    for (i in conflictItems.indices) {
                        conflictItems[i] = conflictItems[i].copy(action = applyToAll!!)
                    }
                    adapter.notifyDataSetChanged()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        checkBoxApplyAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                spinnerApplyAll.visibility = View.VISIBLE
                val selectedIndex = spinnerApplyAll.selectedItemPosition
                applyToAll = ConflictAction.fromIndex(selectedIndex)
                for (i in conflictItems.indices) {
                    conflictItems[i] = conflictItems[i].copy(action = applyToAll!!)
                }
                adapter.notifyDataSetChanged()
            } else {
                spinnerApplyAll.visibility = View.GONE
                applyToAll = null
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_conflict_title)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                var allResolved = true
                for (item in conflictItems) {
                    if (item.action == ConflictAction.NONE) {
                        allResolved = false
                        break
                    }
                }
                if (!allResolved) {
                    Toast.makeText(this, R.string.import_conflict_not_resolved, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val resolvedRules = mutableListOf<JavaCodeRule>()
                for (item in conflictItems) {
                    when (item.action) {
                        ConflictAction.REPLACE -> {
                            currentRules.removeAll { it.name == item.rule.name }
                            resolvedRules.add(item.rule)
                        }
                        ConflictAction.IGNORE -> { }
                        ConflictAction.RENAME_OLD -> {
                            val existingRule = currentRules.find { it.name == item.rule.name }
                            if (existingRule != null) {
                                currentRules.removeAll { it.name == item.rule.name }
                                var newName = item.rule.name + "_old"
                                var counter = 1
                                while (currentRules.any { it.name == newName } || newRules.any { it.name == newName } || resolvedRules.any { it.name == newName }) {
                                    newName = "${item.rule.name}_old_$counter"
                                    counter++
                                }
                                resolvedRules.add(existingRule.copy(name = newName))
                            }
                            resolvedRules.add(item.rule)
                        }
                        ConflictAction.RENAME_NEW -> {
                            var newName = item.rule.name + "_new"
                            var counter = 1
                            while (currentRules.any { it.name == newName } || newRules.any { it.name == newName } || resolvedRules.any { it.name == newName }) {
                                newName = "${item.rule.name}_new_$counter"
                                counter++
                            }
                            resolvedRules.add(item.rule.copy(name = newName))
                        }
                        else -> { }
                    }
                }

                currentRules.addAll(newRules)
                currentRules.addAll(resolvedRules)
                repo.saveJavaCodeRules(currentRules)
                recompileRules()
                Toast.makeText(this, getString(R.string.import_success, newRules.size + resolvedRules.size), Toast.LENGTH_SHORT).show()
                refreshCurrentFragment()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                Toast.makeText(this, R.string.import_cancelled, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun recompileRules() {
        val repo = ModifierRepository(this)
        val rules = repo.getJavaCodeRules()
            .filter { it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty()) }
            .sortedByDescending { it.priority }
            .map { RuleSource(it.condition.ifBlank { null }, it.action.ifBlank { null }) }

        if (rules.isEmpty()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val manager = RuleCompilationManager(this@MainActivity)
            manager.compileAndStore(rules)
        }
    }

    private fun refreshCurrentFragment() {
        when (currentFragmentTag) {
            "rules" -> showFragment(RulesFragment())
            "launchers" -> showFragment(LaunchersFragment())
        }
    }
}

data class ConflictItem(val rule: JavaCodeRule, var action: ConflictAction)

enum class ConflictAction {
    NONE, REPLACE, IGNORE, RENAME_OLD, RENAME_NEW;

    companion object {
        fun fromIndex(index: Int): ConflictAction = when (index) {
            0 -> REPLACE
            1 -> IGNORE
            2 -> RENAME_OLD
            3 -> RENAME_NEW
            else -> NONE
        }
    }
}

class ConflictResolutionAdapter(
    private val items: List<ConflictItem>,
    private val onActionSelected: (Int, ConflictAction) -> Unit
) : RecyclerView.Adapter<ConflictResolutionAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conflict_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position, items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: android.widget.TextView = itemView.findViewById(R.id.textRuleName)
        private val textInfo: android.widget.TextView = itemView.findViewById(R.id.textRuleInfo)
        private val radioGroup: android.widget.RadioGroup = itemView.findViewById(R.id.radioGroup)

        fun bind(position: Int, item: ConflictItem) {
            textName.text = item.rule.name
            val conditionPreview = if (item.rule.condition.length > 30) item.rule.condition.take(30) + "..." else item.rule.condition
            val actionPreview = if (item.rule.action.length > 30) item.rule.action.take(30) + "..." else item.rule.action
            textInfo.text = "Condition: $conditionPreview\nAction: $actionPreview"

            radioGroup.setOnCheckedChangeListener(null)
            radioGroup.clearCheck()

            when (item.action) {
                ConflictAction.REPLACE -> radioGroup.check(R.id.radioReplace)
                ConflictAction.IGNORE -> radioGroup.check(R.id.radioIgnore)
                ConflictAction.RENAME_OLD -> radioGroup.check(R.id.radioRenameOld)
                ConflictAction.RENAME_NEW -> radioGroup.check(R.id.radioRenameNew)
                else -> { }
            }

            radioGroup.setOnCheckedChangeListener { _, checkedId ->
                val action = when (checkedId) {
                    R.id.radioReplace -> ConflictAction.REPLACE
                    R.id.radioIgnore -> ConflictAction.IGNORE
                    R.id.radioRenameOld -> ConflictAction.RENAME_OLD
                    R.id.radioRenameNew -> ConflictAction.RENAME_NEW
                    else -> ConflictAction.NONE
                }
                onActionSelected(position, action)
            }
        }
    }
}

class RulesFragment : Fragment() {
    private lateinit var repo: ModifierRepository
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: JavaCodeRuleAdapter
    private lateinit var empty: View

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        loadRules()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        repo = ModifierRepository(requireContext())

        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        (requireActivity() as? AppCompatActivity)?.let {
            it.setSupportActionBar(toolbar)
            it.supportActionBar?.title = getString(R.string.nav_rules_title)
        }

        recycler = view.findViewById(R.id.recyclerView)
        empty = view.findViewById(R.id.emptyView)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        adapter = JavaCodeRuleAdapter(
            onEdit = { index, rule ->
                val intent = Intent(requireContext(), JavaCodeRuleEditorActivity::class.java)
                intent.putExtra(JavaCodeRuleEditorActivity.EXTRA_RULE_INDEX, index)
                editorLauncher.launch(intent)
            },
            onDelete = { index, rule ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete)
                    .setMessage(getString(R.string.delete_rule_confirm, rule.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        val rules = repo.getJavaCodeRules().toMutableList()
                        rules.removeAt(index)
                        repo.saveJavaCodeRules(rules)
                        loadRules()
                        recompileRules()
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            },
            onEnabledChange = { index, enabled ->
                val rules = repo.getJavaCodeRules().toMutableList()
                rules[index] = rules[index].copy(enabled = enabled)
                repo.saveJavaCodeRules(rules)
                recompileRules()
            }
        )
        recycler.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fabAdd)?.setOnClickListener {
            editorLauncher.launch(Intent(requireContext(), JavaCodeRuleEditorActivity::class.java))
        }

        loadRules()
    }

    override fun onResume() {
        super.onResume()
        loadRules()
    }

    private fun loadRules() {
        val rules = repo.getJavaCodeRules()
        adapter.submitList(rules)
        empty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (rules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun recompileRules() {
        val rules = repo.getJavaCodeRules()
            .filter { it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty()) }
            .sortedByDescending { it.priority }
            .map { RuleSource(it.condition.ifBlank { null }, it.action.ifBlank { null }) }

        if (rules.isEmpty()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val manager = RuleCompilationManager(requireContext())
            manager.compileAndStore(rules)
        }
    }
}

class JavaCodeRuleAdapter(
    private val onEdit: (Int, JavaCodeRule) -> Unit,
    private val onDelete: (Int, JavaCodeRule) -> Unit,
    private val onEnabledChange: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<JavaCodeRuleAdapter.ViewHolder>() {

    private var rules: List<JavaCodeRule> = emptyList()

    fun submitList(newRules: List<JavaCodeRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_java_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount() = rules.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.textRuleName)
        private val textPriority: TextView = itemView.findViewById(R.id.textRulePriority)
        private val textCondition: TextView = itemView.findViewById(R.id.textConditionPreview)
        private val textAction: TextView = itemView.findViewById(R.id.textActionPreview)
        private val switchEnabled: com.google.android.material.materialswitch.MaterialSwitch = itemView.findViewById(R.id.switchEnabled)
        private val buttonEdit: View = itemView.findViewById(R.id.buttonEdit)
        private val buttonDelete: View = itemView.findViewById(R.id.buttonDelete)

        fun bind(rule: JavaCodeRule) {
            textName.text = rule.name
            textPriority.text = itemView.context.getString(R.string.priority) + ": " + rule.priority
            textCondition.text = if (rule.condition.isNotEmpty()) rule.condition else itemView.context.getString(R.string.condition_empty)
            textAction.text = rule.action
            switchEnabled.isChecked = rule.enabled

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEnabledChange(pos, isChecked)
                }
            }
            buttonEdit.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEdit(pos, rules[pos])
                }
            }
            buttonDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDelete(pos, rules[pos])
                }
            }
        }
    }
}

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }

        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        (requireActivity() as? AppCompatActivity)?.let {
            it.setSupportActionBar(toolbar)
            it.supportActionBar?.title = getString(R.string.settings)
        }

        view.findViewById<View>(R.id.languageContainer)?.setOnClickListener { showLangDialog() }
        updateLangText(view)
    }

    private fun showLangDialog() {
        val langs = arrayOf(getString(R.string.language_system), getString(R.string.language_english), getString(R.string.language_chinese))
        val cur = when (getLang()) { "en" -> 1; "zh" -> 2; else -> 0 }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.language)
            .setSingleChoiceItems(langs, cur) { d, w ->
                setLang(when (w) { 1 -> "en"; 2 -> "zh"; else -> "system" })
                d.dismiss()
                requireActivity().recreate()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun getLang() = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "system") ?: "system"
    private fun setLang(l: String) = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("language", l).apply()

    private fun updateLangText(v: View) {
        v.findViewById<android.widget.TextView>(R.id.languageText)?.text = when (getLang()) {
            "en" -> getString(R.string.language_english)
            "zh" -> getString(R.string.language_chinese)
            else -> getString(R.string.language_system)
        }
    }
}

class LaunchersFragment : Fragment() {
    private lateinit var repo: ModifierRepository
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: LauncherHookAdapter
    private lateinit var empty: View

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("package")?.let { pkg ->
                showHookTypeDialog(pkg, null)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        repo = ModifierRepository(requireContext())

        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        (requireActivity() as? AppCompatActivity)?.let {
            it.setSupportActionBar(toolbar)
            it.supportActionBar?.title = getString(R.string.nav_launchers_title)
        }

        recycler = view.findViewById(R.id.recyclerView)
        empty = view.findViewById(R.id.emptyView)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        adapter = LauncherHookAdapter(
            onEdit = { pkg, hook -> showHookTypeDialog(pkg, hook) },
            onDelete = { pkg ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete)
                    .setMessage(getString(R.string.delete_launcher_message, pkg))
                    .setPositiveButton(R.string.delete) { _, _ -> repo.removeLauncherHook(pkg); loadHooks() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        )
        recycler.adapter = adapter

        view.findViewById<View>(R.id.fabAdd)?.setOnClickListener {
            pickerLauncher.launch(Intent(requireContext(), AppPickerActivity::class.java))
        }

        loadHooks()
    }

    private fun loadHooks() {
        val hooks = repo.getLauncherHooks()
        adapter.submitList(hooks)
        empty.visibility = if (hooks.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (hooks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showHookTypeDialog(pkg: String, existingHook: LauncherHook?) {
        val hookTypes = arrayOf(
            getString(R.string.hook_instrumentation),
            getString(R.string.hook_launcher3)
        )
        val currentSelection = when (existingHook?.hookType) {
            HOOK_LAUNCHER3 -> 1
            else -> 0
        }
        var selected = currentSelection
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(pkg)
            .setSingleChoiceItems(hookTypes, currentSelection) { _, which -> selected = which }
            .setPositiveButton(R.string.save) { _, _ ->
                val hookType = when (selected) { 1 -> HOOK_LAUNCHER3 else -> HOOK_INSTRUMENTATION }
                repo.setLauncherHook(LauncherHook(pkg, hookType))
                loadHooks()
                Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}

class LauncherHookAdapter(
    private val onEdit: (String, LauncherHook) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<LauncherHookAdapter.ViewHolder>() {

    private var hooks: Map<String, LauncherHook> = emptyMap()

    fun submitList(newHooks: Map<String, LauncherHook>) {
        hooks = newHooks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_launcher_hook, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (packageName, hook) = hooks.entries.toList()[position]
        holder.bind(packageName, hook)
    }

    override fun getItemCount() = hooks.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container = itemView as com.google.android.material.card.MaterialCardView
        private val appNameText: android.widget.TextView
        private val pkgText: android.widget.TextView
        private val summaryText: android.widget.TextView
        private val editButton: View
        private val deleteButton: View
        private val switchEnabled: com.google.android.material.materialswitch.MaterialSwitch

        init {
            appNameText = container.findViewById(R.id.textAppName)
            pkgText = container.findViewById(R.id.textPackageName)
            summaryText = container.findViewById(R.id.textSummary)
            editButton = container.findViewById(R.id.buttonEdit)
            deleteButton = container.findViewById(R.id.buttonDelete)
            switchEnabled = container.findViewById(R.id.switchEnabled)
        }

        fun bind(packageName: String, hook: LauncherHook) {
            val appName = try {
                itemView.context.packageManager.getApplicationInfo(packageName, 0).loadLabel(itemView.context.packageManager).toString()
            } catch (e: Exception) {
                packageName
            }
            appNameText.text = appName
            pkgText.text = packageName
            switchEnabled.visibility = View.GONE
            summaryText.text = when (hook.hookType) {
                HOOK_LAUNCHER3 -> itemView.context.getString(R.string.hook_launcher3)
                else -> itemView.context.getString(R.string.hook_instrumentation)
            }

            editButton.setOnClickListener { onEdit(packageName, hook) }
            deleteButton.setOnClickListener { onDelete(packageName) }
        }
    }
}