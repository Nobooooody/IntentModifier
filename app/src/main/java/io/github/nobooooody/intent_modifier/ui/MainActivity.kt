package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.HOOK_INSTRUMENTATION
import io.github.nobooooody.intent_modifier.HOOK_LAUNCHER3
import io.github.nobooooody.intent_modifier.data.AppIntentRule
import io.github.nobooooody.intent_modifier.data.ExtraItem
import io.github.nobooooody.intent_modifier.data.HookType
import io.github.nobooooody.intent_modifier.data.LauncherHook
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (currentFragmentTag == "rules") {
            menuInflater.inflate(R.menu.settings_menu, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        return when (fragment) {
            is RulesFragment -> fragment.handleMenuItem(item)
            else -> super.onOptionsItemSelected(item)
        }
    }
}

class RulesFragment : Fragment() {
    private lateinit var repo: ModifierRepository
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppRuleAdapter
    private lateinit var empty: View
    private var pendingExport = ""
    private var pendingImport = ""

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray()) }
                Toast.makeText(requireContext(), R.string.export_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                pendingImport = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                if (pendingImport.isNotEmpty() && validateImportJson(pendingImport)) {
                    showImportDialog()
                } else {
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun validateImportJson(json: String): Boolean {
        return try {
            val parsed = repo.parseRulesJson(json)
            parsed.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("package")?.let { showEditDialog(it, null) }
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
            it.supportActionBar?.title = getString(R.string.settings_title)
        }

        recycler = view.findViewById(R.id.recyclerView)
        empty = view.findViewById(R.id.emptyView)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = AppRuleAdapter(
            onEdit = { pkg, rule -> showEditDialog(pkg, rule) },
            onDelete = { pkg ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_rule)
                    .setMessage(getString(R.string.delete_rule_message, pkg))
                    .setPositiveButton(R.string.delete) { _, _ -> repo.saveRule(pkg, null); loadRules() }
                    .setNegativeButton(R.string.cancel, null).show()
            },
            onEnabledChange = { pkg, enabled ->
                val current = repo.getRules()[pkg] ?: AppIntentRule()
                repo.saveRule(pkg, current.copy(enabled = enabled))
            }
        )
        recycler.adapter = adapter

        view.findViewById<View>(R.id.fabAdd)?.setOnClickListener {
            pickerLauncher.launch(Intent(requireContext(), AppPickerActivity::class.java))
        }

        setHasOptionsMenu(true)
        loadRules()
    }

    override fun onResume() {
        super.onResume()
        loadRules()
    }

    private fun loadRules() {
        val rules = repo.getRules()
        adapter.submitList(rules)
        empty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (rules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEditDialog(pkg: String, existingRule: AppIntentRule?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_rule, null)
        
        val actionInput = dialogView.findViewById<TextInputEditText>(R.id.inputAction)
        val dataInput = dialogView.findViewById<TextInputEditText>(R.id.inputData)
        val packageInput = dialogView.findViewById<TextInputEditText>(R.id.inputPackage)
        val classInput = dialogView.findViewById<TextInputEditText>(R.id.inputClass)
        val flagsInput = dialogView.findViewById<TextInputEditText>(R.id.inputFlags)
        val categoriesContainer = dialogView.findViewById<LinearLayout>(R.id.categoriesContainer)
        val addCategoryButton = dialogView.findViewById<MaterialButton>(R.id.buttonAddCategory)
        val typeInput = dialogView.findViewById<TextInputEditText>(R.id.inputType)
        val extrasContainer = dialogView.findViewById<LinearLayout>(R.id.extrasContainer)
        val addExtraButton = dialogView.findViewById<MaterialButton>(R.id.buttonAddExtra)

        existingRule?.let {
            actionInput.setText(it.customAction ?: "")
            dataInput.setText(it.customData ?: "")
            packageInput.setText(it.customPackage ?: "")
            classInput.setText(it.customClass ?: "")
            flagsInput.setText(it.customFlags?.toString() ?: "")
            typeInput.setText(it.customType ?: "")
        }

        val currentEnabled = existingRule?.enabled ?: true

        val extraViews = mutableListOf<View>()

        fun setInputTypeForExtra(eView: View, type: String, valueInput: TextInputEditText, valueInputLayout: TextInputLayout, switchBoolean: MaterialSwitch) {
            val arrayContainer = eView.findViewById<LinearLayout>(R.id.arrayValuesContainer)
            val addArrayBtn = eView.findViewById<MaterialButton>(R.id.buttonAddArrayItem)
            val isArray = type.endsWith("Array")
            when (type) {
                "Boolean" -> {
                    valueInputLayout.visibility = View.GONE
                    switchBoolean.visibility = View.VISIBLE
                    arrayContainer.visibility = View.GONE
                    addArrayBtn.visibility = View.GONE
                }
                "Null" -> {
                    valueInputLayout.visibility = View.GONE
                    switchBoolean.visibility = View.GONE
                    arrayContainer.visibility = View.GONE
                    addArrayBtn.visibility = View.GONE
                }
                else -> {
                    valueInputLayout.visibility = if (isArray) View.GONE else View.VISIBLE
                    switchBoolean.visibility = View.GONE
                    arrayContainer.visibility = if (isArray) View.VISIBLE else View.GONE
                    addArrayBtn.visibility = if (isArray) View.VISIBLE else View.GONE
                    if (!isArray) valueInput.inputType = when (type) {
                        "Integer" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        "Float" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        "Long" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        "URI" -> InputType.TYPE_TEXT_VARIATION_URI
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                }
            }
        }
        
        fun addExtraView(extra: ExtraItem? = null) {
            val eView = LayoutInflater.from(requireContext()).inflate(R.layout.item_extra, extrasContainer, false)
            val keyInput = eView.findViewById<TextInputEditText>(R.id.inputExtraKey)
            val typeSpinner = eView.findViewById<AutoCompleteTextView>(R.id.spinnerType)
            val valueInputLayout = eView.findViewById<TextInputLayout>(R.id.valueInputLayout)
            val valueInput = eView.findViewById<TextInputEditText>(R.id.inputExtraValue)
            val switchBoolean = eView.findViewById<MaterialSwitch>(R.id.switchBoolean)
            val removeBtn = eView.findViewById<ImageButton>(R.id.buttonRemoveExtra)
            val arrayContainer = eView.findViewById<LinearLayout>(R.id.arrayValuesContainer)
            val addArrayBtn = eView.findViewById<MaterialButton>(R.id.buttonAddArrayItem)

            typeSpinner.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, extraTypes))
            val currentType = extra?.type ?: "String"
            typeSpinner.setText(currentType, false)
            setInputTypeForExtra(eView, currentType, valueInput, valueInputLayout, switchBoolean)

            val arrayItemViews = mutableListOf<View>()
            var currentExtraType = currentType

            fun buildArrayItemView(value: String? = null, isSwitch: Boolean = false) {
                val itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_extra, arrayContainer, false)
                val itemKeyInput = itemView.findViewById<TextInputEditText>(R.id.inputExtraKey)
                val itemTypeSpinner = itemView.findViewById<AutoCompleteTextView>(R.id.spinnerType)
                val itemValueLayout = itemView.findViewById<TextInputLayout>(R.id.valueInputLayout)
                val itemValueInput = itemView.findViewById<TextInputEditText>(R.id.inputExtraValue)
                val itemSwitch = itemView.findViewById<MaterialSwitch>(R.id.switchBoolean)
                val itemArrayContainer = itemView.findViewById<LinearLayout>(R.id.arrayValuesContainer)
                val itemAddBtn = itemView.findViewById<MaterialButton>(R.id.buttonAddArrayItem)
                val itemRemoveBtn = itemView.findViewById<ImageButton>(R.id.buttonRemoveExtra)

                itemKeyInput.visibility = View.GONE
                itemTypeSpinner.visibility = View.GONE
                itemArrayContainer.visibility = View.GONE
                itemAddBtn.visibility = View.GONE

                if (isSwitch) {
                    itemValueLayout.visibility = View.GONE
                    itemSwitch.visibility = View.VISIBLE
                } else {
                    itemValueLayout.visibility = View.VISIBLE
                    itemSwitch.visibility = View.GONE
                    itemValueInput.inputType = when (currentExtraType) {
                        "IntArray", "LongArray", "ShortArray", "ByteArray" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        "FloatArray", "DoubleArray" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                }

                value?.let {
                    if (isSwitch) {
                        itemSwitch.isChecked = it == "true"
                    } else {
                        itemValueInput.setText(it)
                    }
                }

                itemRemoveBtn.setOnClickListener {
                    arrayContainer.removeView(itemView)
                    arrayItemViews.remove(itemView)
                }

                arrayContainer.addView(itemView)
                arrayItemViews.add(itemView)
            }

            fun clearArrayItems() {
                arrayContainer.removeAllViews()
                arrayItemViews.clear()
            }

            fun addArrayItemView(value: String? = null) {
                buildArrayItemView(value, currentExtraType == "BooleanArray")
            }

            extra?.let {
                keyInput.setText(it.key)
                if (it.type == "Boolean") {
                    switchBoolean.isChecked = it.values[0] == "true"
                } else if (it.type.endsWith("Array")) {
                    it.values.forEach { v -> buildArrayItemView(v, it.type == "BooleanArray") }
                } else if (it.type != "Null") {
                    valueInput.setText(it.values[0])
                }
            }

            typeSpinner.setOnItemClickListener { _, _, position, _ ->
                currentExtraType = extraTypes[position]
                switchBoolean.isChecked = false
                valueInput.setText("")
                clearArrayItems()
                setInputTypeForExtra(eView, currentExtraType, valueInput, valueInputLayout, switchBoolean)
            }

            addArrayBtn.setOnClickListener { addArrayItemView() }

            removeBtn.setOnClickListener {
                extrasContainer.removeView(eView)
                extraViews.remove(eView)
            }

            extrasContainer.addView(eView)
            extraViews.add(eView)
        }

        existingRule?.extras?.forEach { addExtraView(it) }
        addExtraButton.setOnClickListener { addExtraView() }

        val categoryViews = mutableListOf<View>()

        fun addCategoryView(category: String? = null) {
            val cView = LayoutInflater.from(requireContext()).inflate(R.layout.item_extra, categoriesContainer, false)
            val keyInput = cView.findViewById<TextInputEditText>(R.id.inputExtraKey)
            val typeSpinner = cView.findViewById<AutoCompleteTextView>(R.id.spinnerType)
            val valueInputLayout = cView.findViewById<TextInputLayout>(R.id.valueInputLayout)
            val valueInput = cView.findViewById<TextInputEditText>(R.id.inputExtraValue)
            val switchBoolean = cView.findViewById<MaterialSwitch>(R.id.switchBoolean)
            val removeBtn = cView.findViewById<ImageButton>(R.id.buttonRemoveExtra)

            keyInput.hint = getString(R.string.category_hint)
            typeSpinner.visibility = View.GONE
            valueInput.visibility = View.GONE
            valueInputLayout.visibility = View.GONE
            switchBoolean.visibility = View.GONE

            category?.let { keyInput.setText(it) }

            removeBtn.setOnClickListener {
                categoriesContainer.removeView(cView)
                categoryViews.remove(cView)
            }

            categoriesContainer.addView(cView)
            categoryViews.add(cView)
        }

        existingRule?.customCategories?.forEach { addCategoryView(it) }
        addCategoryButton.setOnClickListener { addCategoryView() }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(pkg)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val newRule = AppIntentRule(
                    enabled = currentEnabled,
                    customAction = actionInput.text.toString().ifBlank { null },
                    customData = dataInput.text.toString().ifBlank { null },
                    customPackage = packageInput.text.toString().ifBlank { null },
                    customClass = classInput.text.toString().ifBlank { null },
                    customFlags = flagsInput.text.toString().toIntOrNull(),
                    customCategories = categoryViews.mapNotNull { cView ->
                        cView.findViewById<TextInputEditText>(R.id.inputExtraKey).text.toString().ifBlank { null }
                    },
                    customType = typeInput.text.toString().ifBlank { null },
                    extras = extraViews.mapNotNull { eView ->
                        val type = eView.findViewById<AutoCompleteTextView>(R.id.spinnerType).text.toString()
                        val values = when (type) {
                            "Boolean" -> listOf(eView.findViewById<MaterialSwitch>(R.id.switchBoolean).isChecked.toString())
                            "Null" -> emptyList()
                            "BooleanArray" -> {
                                val arrContainer = eView.findViewById<LinearLayout>(R.id.arrayValuesContainer)
                                val items = mutableListOf<String>()
                                for (i in 0 until arrContainer.childCount) {
                                    items.add(arrContainer.getChildAt(i).findViewById<MaterialSwitch>(R.id.switchBoolean).isChecked.toString())
                                }
                                items
                            }
                            else -> {
                                if (type.endsWith("Array")) {
                                    val arrContainer = eView.findViewById<LinearLayout>(R.id.arrayValuesContainer)
                                    val items = mutableListOf<String>()
                                    for (i in 0 until arrContainer.childCount) {
                                        val txt = arrContainer.getChildAt(i).findViewById<TextInputEditText>(R.id.inputExtraValue).text.toString()
                                        if (txt.isNotEmpty()) items.add(txt)
                                    }
                                    items
                                } else {
                                    listOf(eView.findViewById<TextInputEditText>(R.id.inputExtraValue).text.toString())
                                }
                            }
                        }
                        val key = eView.findViewById<TextInputEditText>(R.id.inputExtraKey).text.toString()
                        if (key.isBlank()) null else ExtraItem(key, type, values)
                    }
                )
                repo.saveRule(pkg, newRule)
                loadRules()
                Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private val extraTypes = listOf("Boolean", "BooleanArray", "ByteArray", "CharArray", "CharSequenceArray", "ComponentName", "DoubleArray", "FloatArray", "Float", "IntArray", "Integer", "LongArray", "Long", "Null", "ParcelableArray", "ShortArray", "String", "StringArray", "URI")

    fun handleMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_file -> {
                val json = repo.getRulesJson()
                val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                pendingExport = json
                exportLauncher.launch("intent_modifier_$time.json")
                true
            }
            R.id.action_export_clipboard -> {
                try {
                    val json = repo.getRulesJson()
                    val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("Intent Modifier Rules", json))
                    Toast.makeText(requireContext(), R.string.exported_to_clipboard, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_import_file -> {
                importLauncher.launch(arrayOf("application/json"))
                true
            }
            R.id.action_import_clipboard -> {
                try {
                    val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clip.primaryClip?.getItemAt(0)?.text?.toString()
                    if (text.isNullOrEmpty()) {
                        Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                    } else if (validateImportJson(text)) {
                        pendingImport = text
                        showImportDialog()
                    } else {
                        Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> false
        }
    }

    private fun showImportDialog() {
        var selectedMode = 0
        val options = arrayOf(
            getString(R.string.import_conflict_replace),
            getString(R.string.import_conflict_merge_new),
            getString(R.string.import_conflict_keep_old)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_conflict_title)
            .setSingleChoiceItems(options, 0) { _, which -> selectedMode = which }
            .setPositiveButton(R.string.confirm) { _, _ ->
                val imported = repo.parseRulesJson(pendingImport)
                val current = repo.getRules().toMutableMap()
                when (selectedMode) {
                    0 -> current.clear()
                }
                imported.forEach { (p, r) ->
                    if (selectedMode == 1 || selectedMode == 0 || !current.containsKey(p)) {
                        current[p] = r
                    }
                }
                repo.saveAllRules(current)
                Toast.makeText(requireContext(), getString(R.string.import_success, current.size), Toast.LENGTH_SHORT).show()
                loadRules()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
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

class AppRuleAdapter(
    private val onEdit: (String, AppIntentRule) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onEnabledChange: ((String, Boolean) -> Unit)? = null
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
                onEnabledChange?.invoke(packageName, isChecked)
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

        setHasOptionsMenu(true)
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
            .inflate(R.layout.item_app_rule, parent, false)
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