package io.github.nobooooody.intent_modifier.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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

    // ─── Launcher Hooks ────────────────────────────────────────────────────────

    fun getLauncherHooks(): Map<String, LauncherHook> {
        val jsonStr = prefs.getString(KEY_LAUNCHER_HOOKS, "{}") ?: "{}"
        return parseLauncherHooksJson(jsonStr)
    }

    private fun parseLauncherHooksJson(jsonStr: String): Map<String, LauncherHook> {
        val result = mutableMapOf<String, LauncherHook>()
        try {
            val json = JSONObject(jsonStr)
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
        val json = JSONObject()
        hooks.forEach { (name, hook) ->
            json.put(name, JSONObject().apply {
                put("hookType", hook.hookType)
            })
        }
        prefs.edit().putString(KEY_LAUNCHER_HOOKS, json.toString()).apply()
    }

    // ─── JavaCodeRule CRUD ─────────────────────────────────────────────────────

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
            val json = JSONArray(jsonStr)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun saveJavaCodeRules(rules: List<JavaCodeRule>) {
        val json = JSONArray()
        rules.forEach { rule ->
            json.put(JSONObject().apply {
                put("id", rule.id)
                put("enabled", rule.enabled)
                put("name", rule.name)
                put("targetPackages", JSONArray(rule.targetPackages))
                put("imports", rule.imports)
                put("members", rule.members)
                put("condition", rule.condition)
                put("action", rule.action)
                put("priority", rule.priority)
            })
        }
        prefs.edit().putString(KEY_JAVA_CODE_RULES, json.toString()).apply()
    }

    // ─── NormalRule CRUD ───────────────────────────────────────────────────────

    fun getNormalRules(): List<NormalRule> {
        val jsonStr = prefs.getString(KEY_NORMAL_RULES, "[]") ?: "[]"
        return parseNormalRulesJson(jsonStr)
    }

    fun getNormalRulesJson(): String {
        return prefs.getString(KEY_NORMAL_RULES, "[]") ?: "[]"
    }

    private fun parseNormalRulesJson(jsonStr: String): List<NormalRule> {
        val result = mutableListOf<NormalRule>()
        try {
            val json = JSONArray(jsonStr)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
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
                    customType = optNullableString(obj, "customType"),
                    extras = parseExtras(obj.optJSONArray("extras"))
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun saveNormalRules(rules: List<NormalRule>) {
        val json = JSONArray()
        rules.forEach { rule ->
            json.put(JSONObject().apply {
                put("id", rule.id)
                put("enabled", rule.enabled)
                put("name", rule.name)
                put("targetPackages", JSONArray(rule.targetPackages))
                put("blockSubsequent", rule.blockSubsequent)
                put("priority", rule.priority)
                rule.matchAction?.let { put("matchAction", it) }
                rule.matchData?.let { put("matchData", it) }
                rule.matchPackage?.let { put("matchPackage", it) }
                rule.matchClass?.let { put("matchClass", it) }
                if (rule.matchCategories.isNotEmpty()) put("matchCategories", JSONArray(rule.matchCategories))
                rule.matchType?.let { put("matchType", it) }
                rule.customAction?.let { put("customAction", it) }
                rule.customData?.let { put("customData", it) }
                rule.customPackage?.let { put("customPackage", it) }
                rule.customClass?.let { put("customClass", it) }
                rule.customFlags?.let { put("customFlags", it) }
                if (rule.customCategories.isNotEmpty()) put("customCategories", JSONArray(rule.customCategories))
                rule.customType?.let { put("customType", it) }
                if (rule.extras.isNotEmpty()) {
                    put("extras", JSONArray().apply {
                        rule.extras.forEach { extra ->
                            put(JSONObject().apply {
                                put("key", extra.key)
                                put("type", extra.type)
                                if (extra.values.size == 1) {
                                    put("value", extra.values[0])
                                } else if (extra.values.isNotEmpty()) {
                                    put("values", JSONArray(extra.values))
                                }
                            })
                        }
                    })
                }
            })
        }
        prefs.edit().putString(KEY_NORMAL_RULES, json.toString()).apply()
    }

    // ─── Compiled DEX ──────────────────────────────────────────────────────────

    fun getCompiledVersion(): Long = prefs.getLong(KEY_COMPILED_VERSION, 0)

    fun saveVersion(version: Long) {
        prefs.edit().putLong(KEY_COMPILED_VERSION, version).apply()
    }

    // v2 残留方法，Stage 2 后移除
    fun saveCompiledDex(dexBase64: String, version: Long, ruleCount: Int) {
        prefs.edit()
            .putString(KEY_COMPILED_DEX, dexBase64)
            .putLong(KEY_COMPILED_VERSION, version)
            .putInt(KEY_RULE_COUNT, ruleCount)
            .apply()
    }

    fun getCompiledDex(): String? = prefs.getString(KEY_COMPILED_DEX, null)

    fun getRuleCount(): Int = prefs.getInt(KEY_RULE_COUNT, 0)

    // shared DEX
    fun getSharedDex(): String? = prefs.getString(KEY_SHARED_DEX, null)

    fun saveSharedDex(dexBase64: String) {
        prefs.edit().putString(KEY_SHARED_DEX, dexBase64).apply()
    }

    // app-specific DEX
    fun getAppDex(sanitizedPkg: String): String? {
        return prefs.getString("${KEY_APP_DEX_PREFIX}$sanitizedPkg", null)
    }

    fun saveAppDex(sanitizedPkg: String, dexBase64: String) {
        prefs.edit().putString("${KEY_APP_DEX_PREFIX}$sanitizedPkg", dexBase64).apply()
    }

    fun hasAppDex(sanitizedPkg: String): Boolean {
        return prefs.contains("${KEY_APP_DEX_PREFIX}$sanitizedPkg")
    }

    // ─── Utilities ─────────────────────────────────────────────────────────────

    private fun optStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
    }

    private fun optNullableString(obj: JSONObject, key: String): String? {
        val v = obj.optString(key, "")
        return v.ifEmpty { null }
    }

    private fun parseExtras(json: JSONArray?): List<ExtraItem> {
        if (json == null) return emptyList()
        val result = mutableListOf<ExtraItem>()
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val valuesJson = obj.optJSONArray("values")
            val values = if (valuesJson != null) {
                (0 until valuesJson.length()).map { valuesJson.getString(it) }
            } else {
                listOf(obj.optString("value", ""))
            }
            result.add(ExtraItem(
                key = obj.getString("key"),
                type = obj.getString("type"),
                values = values
            ))
        }
        return result
    }

    fun sanitizePackageName(pkg: String): String = pkg.replace('.', '_')

    companion object {
        const val KEY_ENABLED = "module_enabled"
        const val KEY_LAUNCHER_HOOKS = "launcher_hooks"
        const val KEY_JAVA_CODE_RULES = "java_code_rules"
        const val KEY_NORMAL_RULES = "normal_rules"
        const val KEY_COMPILED_VERSION = "compiled_version"
        const val KEY_SHARED_DEX = "shared_dex"
        const val KEY_APP_DEX_PREFIX = "app_dex_"

        // v2 遗留（保留以便读取旧数据，Stage 2 后移除）
        const val KEY_COMPILED_DEX = "compiled_dex"
        const val KEY_RULE_COUNT = "rule_count"
        const val KEY_RULES_HASH = "rules_hash"
    }
}

data class LauncherHook(
    val packageName: String,
    val hookType: String = HOOK_INSTRUMENTATION
)

const val HOOK_INSTRUMENTATION = "android.app.Instrumentation"
const val HOOK_LAUNCHER3 = "com.android.launcher3.Launcher"
