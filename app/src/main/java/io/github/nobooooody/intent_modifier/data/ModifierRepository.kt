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

    fun getRules(): Map<String, AppIntentRule> {
        val jsonStr = prefs.getString(KEY_RULES, "{}") ?: "{}"
        return parseRulesJson(jsonStr)
    }

    fun parseRulesJson(jsonStr: String): Map<String, AppIntentRule> {
        val result = mutableMapOf<String, AppIntentRule>()
        try {
            val json = JSONObject(jsonStr)
            json.keys().forEach { packageName ->
                val ruleJson = json.getJSONObject(packageName)
                val rule = AppIntentRule(
                    enabled = ruleJson.optBoolean("enabled", true),
                    customAction = ruleJson.optString("customAction").ifEmpty { null },
                    customData = ruleJson.optString("customData").ifEmpty { null },
                    customPackage = ruleJson.optString("customPackage").ifEmpty { null },
                    customClass = ruleJson.optString("customClass").ifEmpty { null },
                    customFlags = if (ruleJson.has("customFlags")) ruleJson.getInt("customFlags") else null,
                    customCategory = ruleJson.optString("customCategory").ifEmpty { null },
                    customType = ruleJson.optString("customType").ifEmpty { null },
                    extras = parseExtras(ruleJson.optJSONArray("extras"))
                )
                result[packageName] = rule
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun parseExtras(json: JSONArray?): List<ExtraItem> {
        if (json == null) return emptyList()
        val result = mutableListOf<ExtraItem>()
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            result.add(ExtraItem(
                key = obj.getString("key"),
                type = obj.getString("type"),
                value = obj.getString("value")
            ))
        }
        return result
    }

    fun saveRule(packageName: String, rule: AppIntentRule?) {
        val currentRules = getRules().toMutableMap()
        if (rule != null) {
            currentRules[packageName] = rule
        } else {
            currentRules.remove(packageName)
        }

        val json = JSONObject()
        currentRules.forEach { (name, r) ->
            val ruleJson = JSONObject().apply {
                put("enabled", r.enabled)
                r.customAction?.let { put("customAction", it) }
                r.customData?.let { put("customData", it) }
                r.customPackage?.let { put("customPackage", it) }
                r.customClass?.let { put("customClass", it) }
                r.customFlags?.let { put("customFlags", it) }
                r.customCategory?.let { put("customCategory", it) }
                r.customType?.let { put("customType", it) }
                if (r.extras.isNotEmpty()) {
                    val extrasJson = JSONArray()
                    r.extras.forEach { extra ->
                        extrasJson.put(JSONObject().apply {
                            put("key", extra.key)
                            put("type", extra.type)
                            put("value", extra.value)
                        })
                    }
                    put("extras", extrasJson)
                }
            }
            json.put(name, ruleJson)
        }

        prefs.edit().putString(KEY_RULES, json.toString()).apply()
    }

    fun getRulesJson(): String {
        return prefs.getString(KEY_RULES, "{}") ?: "{}"
    }

    fun saveAllRules(newRules: Map<String, AppIntentRule>) {
        val filtered = newRules.filterValues { it.enabled || it.customAction != null || it.customData != null || it.customPackage != null || it.customClass != null || it.customFlags != null || it.customCategory != null || it.customType != null || it.extras.isNotEmpty() }
        val json = JSONObject()
        filtered.forEach { (name, r) ->
            val ruleJson = JSONObject().apply {
                put("enabled", r.enabled)
                r.customAction?.let { put("customAction", it) }
                r.customData?.let { put("customData", it) }
                r.customPackage?.let { put("customPackage", it) }
                r.customClass?.let { put("customClass", it) }
                r.customFlags?.let { put("customFlags", it) }
                r.customCategory?.let { put("customCategory", it) }
                r.customType?.let { put("customType", it) }
                if (r.extras.isNotEmpty()) {
                    val extrasJson = JSONArray()
                    r.extras.forEach { extra ->
                        extrasJson.put(JSONObject().apply {
                            put("key", extra.key)
                            put("type", extra.type)
                            put("value", extra.value)
                        })
                    }
                    put("extras", extrasJson)
                }
            }
            json.put(name, ruleJson)
        }
        prefs.edit().putString(KEY_RULES, json.toString()).apply()
    }

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

    fun getLauncherHooksJson(): String {
        return prefs.getString(KEY_LAUNCHER_HOOKS, "{}") ?: "{}"
    }

    companion object {
        private const val KEY_RULES = "modifier_rules"
        private const val KEY_ENABLED = "module_enabled"
        private const val KEY_LAUNCHER_HOOKS = "launcher_hooks"
    }
}

data class ExtraItem(
    val key: String,
    val type: String,
    val value: String
)

data class AppIntentRule(
    val enabled: Boolean = true,
    val customAction: String? = null,
    val customData: String? = null,
    val customPackage: String? = null,
    val customClass: String? = null,
    val customFlags: Int? = null,
    val customCategory: String? = null,
    val customType: String? = null,
    val extras: List<ExtraItem> = emptyList()
)

data class LauncherHook(
    val packageName: String,
    val hookType: String = HOOK_INSTRUMENTATION
)

object HookType {
    const val INSTRUMENTATION = "android.app.Instrumentation"
    const val LAUNCHER3 = "com.android.launcher3.Launcher"
}

const val HOOK_INSTRUMENTATION = HookType.INSTRUMENTATION
const val HOOK_LAUNCHER3 = HookType.LAUNCHER3