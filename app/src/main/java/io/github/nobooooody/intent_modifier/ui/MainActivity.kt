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
import io.github.nobooooody.intent_modifier.data.AppIntentRule
import io.github.nobooooody.intent_modifier.data.ExtraItem
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
                R.id.nav_settings -> showFragment(SettingsFragment())
            }
            true
        }
    }

    private var currentFragmentTag: String? = null

    private fun showFragment(fragment: Fragment) {
        currentFragmentTag = when (fragment) {
            is RulesFragment -> "rules"
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
                if (pendingImport.isNotEmpty()) {
                    showImportDialog()
                } else {
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
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
        val extrasContainer = dialogView.findViewById<LinearLayout>(R.id.extrasContainer)
        val addExtraButton = dialogView.findViewById<MaterialButton>(R.id.buttonAddExtra)

        existingRule?.let {
            actionInput.setText(it.customAction ?: "")
            dataInput.setText(it.customData ?: "")
            packageInput.setText(it.customPackage ?: "")
            classInput.setText(it.customClass ?: "")
        }

        val currentEnabled = existingRule?.enabled ?: true

        val extraViews = mutableListOf<View>()

        fun setInputTypeForExtra(eView: View, type: String, valueInput: TextInputEditText, valueInputLayout: TextInputLayout, switchBoolean: MaterialSwitch) {
            when (type) {
                "Boolean" -> {
                    valueInputLayout.visibility = View.GONE
                    switchBoolean.visibility = View.VISIBLE
                }
                "Null" -> {
                    valueInputLayout.visibility = View.GONE
                    switchBoolean.visibility = View.GONE
                }
                else -> {
                    valueInputLayout.visibility = View.VISIBLE
                    switchBoolean.visibility = View.GONE
                    valueInput.inputType = when (type) {
                        "Integer", "Long" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        "Decimal Number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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

            typeSpinner.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, extraTypes))
            val currentType = extra?.type ?: "String"
            typeSpinner.setText(currentType, false)
            setInputTypeForExtra(eView, currentType, valueInput, valueInputLayout, switchBoolean)

            extra?.let {
                keyInput.setText(it.key)
                if (it.type == "Boolean") {
                    switchBoolean.isChecked = it.value == "true"
                } else if (it.type != "Null") {
                    valueInput.setText(it.value)
                }
            }

            typeSpinner.setOnItemClickListener { _, _, position, _ ->
                setInputTypeForExtra(eView, extraTypes[position], valueInput, valueInputLayout, switchBoolean)
            }

            removeBtn.setOnClickListener {
                extrasContainer.removeView(eView)
                extraViews.remove(eView)
            }

            extrasContainer.addView(eView)
            extraViews.add(eView)
        }

        existingRule?.extras?.forEach { addExtraView(it) }
        addExtraButton.setOnClickListener { addExtraView() }

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
                    extras = extraViews.mapNotNull { eView ->
                        val type = eView.findViewById<AutoCompleteTextView>(R.id.spinnerType).text.toString()
                        val value = when (type) {
                            "Boolean" -> (eView.findViewById<MaterialSwitch>(R.id.switchBoolean).isChecked).toString()
                            "Null" -> ""
                            else -> eView.findViewById<TextInputEditText>(R.id.inputExtraValue).text.toString()
                        }
                        val key = eView.findViewById<TextInputEditText>(R.id.inputExtraKey).text.toString()
                        if (key.isBlank()) null else ExtraItem(key, type, value)
                    }
                )
                repo.saveRule(pkg, newRule)
                loadRules()
                Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private val extraTypes = listOf("Boolean", "ComponentName", "Decimal Number", "Integer", "Long", "Null", "String", "URI")

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
                    } else {
                        pendingImport = text
                        showImportDialog()
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