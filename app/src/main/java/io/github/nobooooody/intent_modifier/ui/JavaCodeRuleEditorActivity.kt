package io.github.nobooooody.intent_modifier.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.engine.RuleCompilationManager
import io.github.nobooooody.intent_modifier.engine.RuleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JavaCodeRuleEditorActivity : AppCompatActivity() {

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
        setContentView(R.layout.activity_rule_editor)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        repo = ModifierRepository(this)

        val index = intent.getIntExtra(EXTRA_RULE_INDEX, -1)
        if (index >= 0) {
            val rules = repo.getJavaCodeRules()
            if (index < rules.size) {
                editingRule = rules[index]
            }
        }

        setupToolbar()
        setupViews()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = if (editingRule != null) getString(R.string.edit_rule) else getString(R.string.new_rule)
    }

    private fun setupViews() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        val inputName = findViewById<TextInputEditText>(R.id.inputName)
        val switchEnabled = findViewById<MaterialSwitch>(R.id.switchEnabled)
        val inputPriority = findViewById<TextInputEditText>(R.id.inputPriority)
        val inputCondition = findViewById<TextInputEditText>(R.id.inputCondition)
        val inputAction = findViewById<TextInputEditText>(R.id.inputAction)
        val buttonTestCompile = findViewById<MaterialButton>(R.id.buttonTestCompile)
        val buttonSave = findViewById<MaterialButton>(R.id.buttonSave)
        val textResult = findViewById<android.widget.TextView>(R.id.textCompileResult)

        editingRule?.let { rule ->
            inputName.setText(rule.name)
            switchEnabled.isChecked = rule.enabled
            inputPriority.setText(rule.priority.toString())
            inputCondition.setText(rule.condition)
            inputAction.setText(rule.action)
        }

        buttonTestCompile.setOnClickListener {
            val condition = inputCondition.text.toString()
            val action = inputAction.text.toString()

            if (condition.isBlank() && action.isBlank()) {
                Toast.makeText(this, R.string.compile_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            textResult.text = getString(R.string.compiling)
            textResult.setTextColor(ContextCompat.getColor(this, R.color.orange))
            textResult.visibility = View.VISIBLE
            buttonTestCompile.isEnabled = false

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val manager = RuleCompilationManager(this@JavaCodeRuleEditorActivity)
                    val ruleSources = listOf(RuleSource(
                        condition = condition.ifBlank { null },
                        action = action.ifBlank { null }
                    ))
                    val success = withContext(Dispatchers.IO) {
                        manager.compileAndStore(ruleSources)
                    }
                    if (success) {
                        textResult.text = getString(R.string.compile_success)
                        textResult.setTextColor(ContextCompat.getColor(this@JavaCodeRuleEditorActivity, R.color.green))
                    } else {
                        textResult.text = getString(R.string.compile_failed)
                        textResult.setTextColor(ContextCompat.getColor(this@JavaCodeRuleEditorActivity, R.color.red))
                    }
                } catch (e: Exception) {
                    textResult.text = "${getString(R.string.compile_failed)}: ${e.message}"
                    textResult.setTextColor(ContextCompat.getColor(this@JavaCodeRuleEditorActivity, R.color.red))
                }
                buttonTestCompile.isEnabled = true
            }
        }

        buttonSave.setOnClickListener {
            val name = inputName.text.toString().trim()
            val enabled = switchEnabled.isChecked
            val priority = inputPriority.text.toString().toIntOrNull() ?: 0
            val condition = inputCondition.text.toString().trim()
            val action = inputAction.text.toString().trim()

            if (name.isBlank()) {
                Toast.makeText(this, R.string.error_key_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentRules = repo.getJavaCodeRules().toMutableList()

            if (editingRule != null) {
                val index = currentRules.indexOfFirst { it.name == editingRule!!.name }
                if (index >= 0) {
                    currentRules[index] = JavaCodeRule(enabled, name, condition, action, priority)
                }
            } else {
                currentRules.add(JavaCodeRule(enabled, name, condition, action, priority))
            }

            repo.saveJavaCodeRules(currentRules)

            Toast.makeText(this, R.string.compiling_all_rules, Toast.LENGTH_SHORT).show()
            buttonSave.isEnabled = false

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val manager = RuleCompilationManager(this@JavaCodeRuleEditorActivity)
                    val ruleSources = currentRules
                        .filter { it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty()) }
                        .sortedByDescending { it.priority }
                        .map { RuleSource(it.condition.ifBlank { null }, it.action.ifBlank { null }) }

                    if (ruleSources.isEmpty()) {
                        Toast.makeText(this@JavaCodeRuleEditorActivity, R.string.no_rules_to_compile, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        val success = withContext(Dispatchers.IO) {
                            manager.compileAndStore(ruleSources)
                        }
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@JavaCodeRuleEditorActivity, R.string.saved_and_compiled, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@JavaCodeRuleEditorActivity, R.string.saved_but_compile_failed, Toast.LENGTH_SHORT).show()
                            }
                            finish()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@JavaCodeRuleEditorActivity, "${getString(R.string.saved)}\n${e.message}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }
}