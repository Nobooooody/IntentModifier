package io.github.nobooooody.intent_modifier.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

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
        prefs.edit().putString(KEY_JAVA_CODE_RULES, RuleJson.javaRulesJson(rules)).apply()
    }

    fun upsertJavaCodeRule(rule: JavaCodeRule) {
        val rules = getJavaCodeRules().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
        saveJavaCodeRules(rules)
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
                    replaceCategories = obj.optBoolean("replaceCategories", false),
                    replaceExtras = obj.optBoolean("replaceExtras", false),
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
        prefs.edit().putString(KEY_NORMAL_RULES, RuleJson.normalRulesJson(rules)).apply()
    }

    fun upsertNormalRule(rule: NormalRule) {
        val rules = getNormalRules().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
        saveNormalRules(rules)
    }

    // ─── Compiled DEX ──────────────────────────────────────────────────────────

    fun getCompiledVersion(): Long = prefs.getLong(KEY_COMPILED_VERSION, 0)

    fun saveVersion(version: Long) {
        prefs.edit().putLong(KEY_COMPILED_VERSION, version).apply()
    }

    /**
     * 一次性提交 shared DEX、app DEX、manifest 与 version，避免中间态对外可见。
     * 使用 commit() 同步落盘；失败时抛出异常，由调用方转为编译发布失败。
     */
    fun publishCompiledRules(
        sharedDexBase64: String,
        appDexBase64: Map<String, String>,
        manifestJson: String,
        version: Long
    ) {
        val existingAppPackages = getAllAppDexPackages()
        val editor = prefs.edit()
        editor.putString(KEY_SHARED_DEX, sharedDexBase64)
        for ((sanitizedPkg, dexBase64) in appDexBase64) {
            editor.putString("$KEY_APP_DEX_PREFIX$sanitizedPkg", dexBase64)
        }
        for (stalePkg in existingAppPackages - appDexBase64.keys) {
            editor.remove("$KEY_APP_DEX_PREFIX$stalePkg")
        }
        editor.putString(KEY_COMPILED_MANIFEST, manifestJson)
        editor.putLong(KEY_COMPILED_VERSION, version)
        // 能力 token 与 DEX/manifest 同批原子发布；旧数据没有该键时读取方按旧行为放行。
        // 注意：这里的全局 token 会随每次发布轮换；/enroll 发放的包绑定凭证
        // 存放在 KEY_PROVIDER_TOKENS，不随发布清除，只能由用户撤销。
        editor.putString(KEY_PROVIDER_TOKEN, newProviderToken())
        check(editor.commit()) { "Failed to commit compiled rules" }
    }

    fun getCompiledManifest(): String? = prefs.getString(KEY_COMPILED_MANIFEST, null)

    // ─── Provider 能力 token ──────────────────────────────────────────────────

    fun getProviderToken(): String? = prefs.getString(KEY_PROVIDER_TOKEN, null)

    fun isProviderTokenRequired(): Boolean = prefs.getBoolean(KEY_PROVIDER_REQUIRE_TOKEN, false)

    fun getEnrollWindowUntil(): Long = prefs.getLong(KEY_ENROLL_WINDOW_UNTIL, 0L)

    fun getEnrollTargetPackage(): String? = prefs.getString(KEY_ENROLL_TARGET_PACKAGE, null)

    fun getProviderTokens(): Map<String, String> {
        val raw = prefs.getString(KEY_PROVIDER_TOKENS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { pkg ->
                    val token = json.optString(pkg, "")
                    if (token.isNotEmpty()) put(pkg, token)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 授权窗口内为目标包发放一次性凭证，并把窗口关闭与凭证表写入同一次 commit。
     * 返回 null 表示写入失败。
     */
    fun issueEnrollmentToken(targetPackage: String): String? {
        val token = newProviderToken()
        val tokens = getProviderTokens().toMutableMap()
        tokens[targetPackage] = token
        val json = JSONObject()
        tokens.forEach { (pkg, value) -> json.put(pkg, value) }
        val editor = prefs.edit()
        editor.putString(KEY_PROVIDER_TOKENS, json.toString())
        editor.putLong(KEY_ENROLL_WINDOW_UNTIL, 0L)
        return if (editor.commit()) token else null
    }

    private fun newProviderToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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

    fun removeAppDex(sanitizedPkg: String) {
        prefs.edit().remove("${KEY_APP_DEX_PREFIX}$sanitizedPkg").apply()
    }

    fun getAllAppDexPackages(): Set<String> {
        return prefs.all.keys.filter { it.startsWith(KEY_APP_DEX_PREFIX) }
            .map { it.removePrefix(KEY_APP_DEX_PREFIX) }.toSet()
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
        private val SECURE_RANDOM = SecureRandom()

        const val KEY_ENABLED = "module_enabled"
        const val KEY_LAUNCHER_HOOKS = "launcher_hooks"
        const val KEY_JAVA_CODE_RULES = "java_code_rules"
        const val KEY_NORMAL_RULES = "normal_rules"
        const val KEY_COMPILED_VERSION = "compiled_version"
        const val KEY_COMPILED_MANIFEST = "compiled_manifest"
        const val KEY_SHARED_DEX = "shared_dex"
        const val KEY_APP_DEX_PREFIX = "app_dex_"

        // Provider 能力 token 与本地模式授权窗口
        const val KEY_PROVIDER_TOKEN = "provider_token"
        const val KEY_PROVIDER_REQUIRE_TOKEN = "provider_require_token"
        const val KEY_PROVIDER_TOKENS = "provider_tokens"
        const val KEY_ENROLL_WINDOW_UNTIL = "enroll_window_until"
        const val KEY_ENROLL_TARGET_PACKAGE = "enroll_target_package"

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
