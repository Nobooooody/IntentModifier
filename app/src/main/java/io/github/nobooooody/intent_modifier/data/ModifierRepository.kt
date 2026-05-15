package io.github.nobooooody.intent_modifier.data

import android.content.Context
import android.content.SharedPreferences

class ModifierRepository(private val context: Context) {

    private val prefs: SharedPreferences = try {
        context.getSharedPreferences("intent_modifier_config", Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        context.getSharedPreferences("intent_modifier_config", Context.MODE_PRIVATE)
    }

    fun isModuleEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setModuleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getLauncherHooks(): Map<String, LauncherHook> {
        val jsonStr = prefs.getString(KEY_LAUNCHER_HOOKS, "{}") ?: "{}"
        return parseLauncherHooksJson(jsonStr)
    }

    private fun parseLauncherHooksJson(jsonStr: String): Map<String, LauncherHook> {
        val result = mutableMapOf<String, LauncherHook>()
        try {
            val json = org.json.JSONObject(jsonStr)
            json.keys().forEach { pkg ->
                val hookJson = json.getJSONObject(pkg)
                result[pkg] = LauncherHook(
                    packageName = pkg,
                    hookType = hookJson.optString("hookType", HOOK_INSTRUMENTATION)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun setLauncherHook(launcherHook: LauncherHook) {
        val current = getLauncherHooks().toMutableMap()
        current[launcherHook.packageName] = launcherHook
        saveLauncherHooks(current)
    }

    fun removeLauncherHook(packageName: String) {
        val current = getLauncherHooks().toMutableMap()
        current.remove(packageName)
        saveLauncherHooks(current)
    }

    private fun saveLauncherHooks(hooks: Map<String, LauncherHook>) {
        val json = org.json.JSONObject()
        hooks.forEach { (name, hook) ->
            json.put(name, org.json.JSONObject().apply {
                put("hookType", hook.hookType)
            })
        }
        prefs.edit().putString(KEY_LAUNCHER_HOOKS, json.toString()).apply()
    }

    fun getJavaCodeRules(): List<JavaCodeRule> {
        val jsonStr = prefs.getString(KEY_JAVA_CODE_RULES, "[]") ?: "[]"
        return parseJavaCodeRulesJson(jsonStr)
    }

    fun getJavaCodeRulesJson(): String {
        return prefs.getString(KEY_JAVA_CODE_RULES, "[]") ?: "[]"
    }

    private fun parseJavaCodeRulesJson(jsonStr: String): List<JavaCodeRule> {
        val result = mutableListOf<JavaCodeRule>()
        try {
            val json = org.json.JSONArray(jsonStr)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                result.add(JavaCodeRule(
                    enabled = obj.optBoolean("enabled", true),
                    name = obj.optString("name", ""),
                    condition = obj.optString("condition", ""),
                    action = obj.optString("action", ""),
                    priority = obj.optInt("priority", 0)
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun saveJavaCodeRules(rules: List<JavaCodeRule>) {
        val json = org.json.JSONArray()
        rules.forEach { rule ->
            val ruleJson = org.json.JSONObject().apply {
                put("enabled", rule.enabled)
                put("name", rule.name)
                put("condition", rule.condition)
                put("action", rule.action)
                put("priority", rule.priority)
            }
            json.put(ruleJson)
        }
        prefs.edit().putString(KEY_JAVA_CODE_RULES, json.toString()).apply()
    }

    fun getCompiledVersion(): Long = prefs.getLong(KEY_COMPILED_VERSION, 0)

    fun saveCompiledDex(dexBase64: String, version: Long, ruleCount: Int) {
        prefs.edit()
            .putString(KEY_COMPILED_DEX, dexBase64)
            .putLong(KEY_COMPILED_VERSION, version)
            .putInt(KEY_RULE_COUNT, ruleCount)
            .apply()
    }

    fun getCompiledDex(): String? = prefs.getString(KEY_COMPILED_DEX, null)

    fun getRuleCount(): Int = prefs.getInt(KEY_RULE_COUNT, 0)

    companion object {
        private const val KEY_ENABLED = "module_enabled"
        private const val KEY_LAUNCHER_HOOKS = "launcher_hooks"
        private const val KEY_JAVA_CODE_RULES = "java_code_rules"
        private const val KEY_COMPILED_DEX = "compiled_dex"
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val KEY_RULE_COUNT = "rule_count"
    }
}

data class LauncherHook(
    val packageName: String,
    val hookType: String = HOOK_INSTRUMENTATION
)

const val HOOK_INSTRUMENTATION = "android.app.Instrumentation"
const val HOOK_LAUNCHER3 = "com.android.launcher3.Launcher"