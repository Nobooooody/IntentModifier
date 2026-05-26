package io.github.nobooooody.intent_modifier.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build as BuildIcon
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.nobooooody.intent_modifier.R
import io.github.nobooooody.intent_modifier.data.ModifierRepository

class TabbedRuleEditorActivity : ComponentActivity() {

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
        val repo = ModifierRepository(this)
        setContent {
            @OptIn(ExperimentalMaterial3Api::class)
            IntentModifierTheme {
                var tabIndex by remember { mutableIntStateOf(0) }
                var saveTrigger by remember { mutableIntStateOf(0) }
                var testCompileTrigger by remember { mutableIntStateOf(0) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.new_rule)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                }
                            },
                            actions = {
                                IconButton(onClick = { testCompileTrigger++ }) {
                                    Icon(Icons.Default.BuildIcon, contentDescription = null)
                                }
                                IconButton(onClick = { saveTrigger++ }) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                        TabRow(selectedTabIndex = tabIndex) {
                            Tab(
                                selected = tabIndex == 0,
                                onClick = { tabIndex = 0; testCompileTrigger = 0; saveTrigger = 0 },
                                text = { Text(stringResource(R.string.normal_rule)) }
                            )
                            Tab(
                                selected = tabIndex == 1,
                                onClick = { tabIndex = 1; testCompileTrigger = 0; saveTrigger = 0 },
                                text = { Text(stringResource(R.string.java_code_rule)) }
                            )
                        }
                        when (tabIndex) {
                            0 -> key("normal_rule_form") {
                                NormalRuleForm(
                                    editingRule = null,
                                    onSave = { rule ->
                                        val currentRules = repo.getNormalRules().toMutableList()
                                        currentRules.add(rule)
                                        repo.saveNormalRules(currentRules)
                                    },
                                    saveTrigger = saveTrigger,
                                    testCompileTrigger = testCompileTrigger
                                )
                            }
                            1 -> key("java_rule_form") {
                                JavaCodeRuleForm(
                                    editingRule = null,
                                    onSave = { rule ->
                                        val currentRules = repo.getJavaCodeRules().toMutableList()
                                        currentRules.add(rule)
                                        repo.saveJavaCodeRules(currentRules)
                                    },
                                    saveTrigger = saveTrigger,
                                    testCompileTrigger = testCompileTrigger
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
