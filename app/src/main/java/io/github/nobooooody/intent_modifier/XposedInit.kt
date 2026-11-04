package io.github.nobooooody.intent_modifier

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nobooooody.intent_modifier.ui.provider.RuleProvider
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method

class XposedInit : IXposedHookLoadPackage {

    @Volatile
    var currentContext: Context? = null
    @Volatile
    private var rulesState: RulesState? = null

    private var hasRuleChecked = false
    private var lastRuleCheckAt = 0L

    private val ruleReentrancy: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

    private data class RulesState(val version: Long, val rules: CompiledRules)

    private data class RegistryRule(
        val className: String,
        val priority: Int,
        val ruleId: String,
        val ruleName: String
    )

    private companion object {
        private const val TAG = "IntentModifier"
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val RULE_VERSION_CHECK_INTERVAL_MS = 3_000L
        private const val PREWARM_WAIT_MS = 1_000L
        private const val LOG_INTENTS = false
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun logThrowable(msg: String, t: Throwable) {
        XposedBridge.log("$TAG: $msg: ${t.javaClass.simpleName}: ${t.message}")
        XposedBridge.log(t)
    }

    private fun logIntent(prefix: String, intent: Intent) {
        if (!LOG_INTENTS) return
        try {
            val extraKeys = intent.extras?.keySet()?.joinToString(",") ?: ""
            val dataScheme = intent.data?.scheme ?: ""
            XposedBridge.log(
                "$prefix pkg=${intent.`package`}, component=${intent.component}, action=${intent.action}, " +
                    "dataScheme=$dataScheme, type=${intent.type}, flags=${intent.flags}, " +
                    "categories=${intent.categories}, extraKeys=[$extraKeys]"
            )
        } catch (t: Throwable) {
            log("Intent log failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val currentPkg = lpparam.packageName

        if (currentPkg == "io.github.nobooooody.intent_modifier") {
            return
        }

        val launcherHooks = LauncherHooksLoader.getHooks()
        val hookType = launcherHooks[currentPkg]?.hookType ?: HOOK_INSTRUMENTATION

        when (hookType) {
            HOOK_LAUNCHER3 -> hookLauncher3(lpparam)
            HOOK_INSTRUMENTATION -> hookInstrumentation(lpparam)
            else -> hookCustomClass(lpparam, hookType)
        }

        prewarmRules(lpparam)
    }

    private fun prewarmRules(lpparam: XC_LoadPackage.LoadPackageParam) {
        val thread = Thread({
            try {
                var waited = 0L
                while (waited < PREWARM_WAIT_MS) {
                    val app = AndroidAppHelper.currentApplication()
                    if (app != null) {
                        currentContext = app.applicationContext
                        loadRulesIfNeeded(lpparam, currentContext)
                        return@Thread
                    }
                    Thread.sleep(50)
                    waited += 50
                }
                log("Rule prewarm skipped: application context unavailable")
            } catch (t: Throwable) {
                log("Rule prewarm failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "IntentModifier-prewarm")
        thread.isDaemon = true
        thread.start()
    }

    // ─── 规则加载 ───────────────────────────────────────────────────────────────

    @Synchronized
    private fun loadRulesIfNeeded(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context?) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (hasRuleChecked && now - lastRuleCheckAt < RULE_VERSION_CHECK_INTERVAL_MS) return
            hasRuleChecked = true
            lastRuleCheckAt = now

            val targetPkg = lpparam.packageName
            val cacheDir = ctx?.cacheDir ?: run {
                log("Cannot load rules: currentContext is null")
                return
            }
            val rulesDir = File(cacheDir, "intent_modifier_rules")

            ModulePrefsReader.refresh(now)
            val remoteVersion = tryGetRemoteVersion()
            val state = rulesState
            if (state != null && remoteVersion != null && remoteVersion == state.version) {
                // 版本未变：不读 meta.json，直接复用当前不可变快照。
                return
            }

            val localMetaFile = File(rulesDir, "meta.json")
            var localVersion = 0L
            if (localMetaFile.exists()) {
                try {
                    localVersion = JSONObject(localMetaFile.readText()).optLong("version", 0)
                } catch (e: Exception) {
                    log("Failed to read local meta: ${e.message}")
                }
            }

            if (remoteVersion == null || remoteVersion == 0L) {
                log("No remote version available, trying local cached DEX")
                tryLoadLocalCached(lpparam, targetPkg)
                return
            }

            if (remoteVersion == localVersion) {
                if (tryLoadLocalCached(lpparam, targetPkg)) {
                    log("Local rules version=$localVersion up to date")
                    return
                }
            }

            log("Need to update rules: remote=$remoteVersion, local=$localVersion")

            // 下载 shared DEX
            val sharedDexBase64 = tryGetRemoteDex(ctx)
            if (sharedDexBase64.isNullOrEmpty()) {
                log("No remote DEX available, falling back to local cached DEX")
                tryLoadLocalCached(lpparam, targetPkg)
                return
            }

            // 尝试下载 app-specific DEX
            val appDexBase64 = tryGetRemoteAppDex(ctx, targetPkg)

            // 写入缓存
            rulesDir.deleteRecursively()
            rulesDir.mkdirs()

            val sharedDexFile = File(rulesDir, "rules_shared.dex")
            sharedDexFile.writeBytes(Base64.decode(sharedDexBase64, Base64.NO_WRAP))
            sharedDexFile.setReadOnly()

            if (appDexBase64 != null) {
                val appDexFile = File(rulesDir, "rules_app.dex")
                appDexFile.writeBytes(Base64.decode(appDexBase64, Base64.NO_WRAP))
                appDexFile.setReadOnly()
            }

            localMetaFile.writeText(JSONObject().apply {
                put("version", remoteVersion)
            }.toString())

            if (!loadDexAndBuildRules(lpparam, targetPkg, remoteVersion)) {
                log("Failed to load downloaded DEX for version=$remoteVersion")
            }

        } catch (t: Throwable) {
            logThrowable("Failed to load rules", t)
        }
    }

    private fun tryLoadLocalCached(lpparam: XC_LoadPackage.LoadPackageParam, targetPkg: String): Boolean {
        val cacheDir = currentContext?.cacheDir ?: return false
        val rulesDir = File(cacheDir, "intent_modifier_rules")
        val sharedDex = File(rulesDir, "rules_shared.dex")
        val metaFile = File(rulesDir, "meta.json")
        if (!sharedDex.exists() || !metaFile.exists()) return false

        val version = try { JSONObject(metaFile.readText()).optLong("version", 0) } catch (e: Exception) { 0L }
        val loaded = loadDexAndBuildRules(lpparam, targetPkg, version)
        if (!loaded) {
            log("Local rules version=$version failed to load, dropping cache")
            runCatching { rulesDir.deleteRecursively() }
        }
        return loaded
    }

    private fun loadDexAndBuildRules(lpparam: XC_LoadPackage.LoadPackageParam, targetPkg: String, version: Long): Boolean {
        try {
            if (rulesState?.version == version) return true

            val cacheDir = currentContext?.cacheDir ?: return false
            val rulesDir = "${cacheDir.absolutePath}/intent_modifier_rules"
            val sharedDex = File("$rulesDir/rules_shared.dex")
            if (!sharedDex.exists()) {
                log("Shared DEX missing at $sharedDex")
                return false
            }

            val dexPaths = mutableListOf(sharedDex.absolutePath)
            val appDex = File("$rulesDir/rules_app.dex")
            if (appDex.exists()) dexPaths.add(appDex.absolutePath)

            val optimizedDir = File("$rulesDir/optimized").also { it.mkdirs() }
            val path = dexPaths.joinToString(":")
            val parentLoader = lpparam.classLoader
            val dexClassLoader = dalvik.system.DexClassLoader(path, optimizedDir.absolutePath, null, parentLoader)

            // 加载 shared RuleRegistry
            val registryClasses = mutableListOf<RegistryRule>()
            val sharedRegistry = loadRegistry(dexClassLoader, "engine.RuleRegistry_shared")
                ?: run {
                    log("Shared RuleRegistry missing, refusing local cache")
                    return false
                }
            registryClasses.addAll(sharedRegistry)

            // 如果有 app DEX，加载 app RuleRegistry
            if (appDex.exists()) {
                val sanitizedPkg = targetPkg.replace('.', '_')
                val appRegistry = loadRegistry(dexClassLoader, "engine.RuleRegistry_app_$sanitizedPkg")
                    ?: run {
                        log("App RuleRegistry missing for $targetPkg, refusing local cache")
                        return false
                    }
                registryClasses.addAll(appRegistry)
            }

            // 按 priority 降序排列
            registryClasses.sortByDescending { it.priority }

            // 加载每条规则的方法；Class.forName(initialize=true) 主动触发静态初始化。
            val rules = mutableListOf<LoadedRule>()
            var failedRules = 0
            for (entry in registryClasses) {
                try {
                    val ruleClass = Class.forName(entry.className, true, dexClassLoader)
                    val evaluateMethod = ruleClass.getMethod("evaluate", Context::class.java, Intent::class.java, Intent::class.java)
                    val executeMethod = ruleClass.getMethod("execute", Context::class.java, Intent::class.java, Intent::class.java)
                    rules.add(LoadedRule(entry.ruleId, entry.ruleName, evaluateMethod, executeMethod))
                } catch (t: Throwable) {
                    failedRules++
                    logThrowable(
                        "Failed to load rule id=${entry.ruleId} name=${entry.ruleName} class=${entry.className}",
                        t
                    )
                }
            }

            if (failedRules > 0) {
                log("$failedRules of ${registryClasses.size} rules failed to load, refusing local cache")
                return false
            }

            rulesState = RulesState(version, CompiledRules(rules))
            log("Successfully loaded ${rules.size} rules from ${dexPaths.size} DEX file(s), version=$version")
            return true
        } catch (t: Throwable) {
            log("Failed to load DEX: ${t.message}")
            return false
        }
    }

    private fun loadRegistry(classLoader: ClassLoader, registryName: String): List<RegistryRule>? {
        return try {
            val registryClass = classLoader.loadClass(registryName)
            @Suppress("UNCHECKED_CAST")
            val raw = registryClass.getMethod("getRules").invoke(null) as Array<Array<Any?>>
            val mapped = raw.mapNotNull { row ->
                val className = row.getOrNull(0) as? String ?: return@mapNotNull null
                val priority = (row.getOrNull(1) as? Number)?.toInt() ?: 0
                val ruleId = row.getOrNull(2) as? String ?: className
                val ruleName = row.getOrNull(3) as? String ?: className
                RegistryRule(className, priority, ruleId, ruleName)
            }
            if (mapped.size != raw.size) {
                log("Registry $registryName malformed: ${raw.size - mapped.size} invalid entries")
                return null
            }
            mapped
        } catch (t: Throwable) {
            logThrowable("Registry $registryName load failed", t)
            null
        }
    }

    // ─── 远端读取 ───────────────────────────────────────────────────────────────

    private fun tryGetRemoteVersion(): Long? {
        try {
            val version = ModulePrefsReader.getLong(KEY_COMPILED_VERSION, 0L)
            if (version > 0) {
                log("Got remote version=$version via XSharedPreferences")
                return version
            }
            log("XSharedPreferences version is 0")
        } catch (e: Exception) {
            log("XSharedPreferences version failed: ${e.message}")
        }

        try {
            val ctx = currentContext ?: return null
            log("Falling back to ContentProvider for version")
            val cursor = ctx.contentResolver.query(RuleProvider.URI_VERSION, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val version = it.getLong(0)
                    if (version > 0) {
                        log("Got remote version=$version via ContentProvider")
                        return version
                    }
                }
            }
        } catch (e: Exception) {
            log("ContentProvider version failed: ${e.message}")
        }

        return null
    }

    private fun tryGetRemoteDex(ctx: Context?): String? {
        try {
            val dex = ModulePrefsReader.getString("shared_dex", null)
            if (!dex.isNullOrEmpty()) return dex
            log("XSharedPreferences shared_dex is empty")
        } catch (e: Exception) {
            log("XSharedPreferences shared_dex failed: ${e.message}")
        }

        try {
            if (ctx == null) return null
            log("Falling back to ContentProvider for shared DEX")
            val cursor = ctx.contentResolver.query(RuleProvider.URI_DEX, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val dex = it.getString(0)
                    if (!dex.isNullOrEmpty()) return dex
                }
            }
        } catch (e: Exception) {
            log("ContentProvider dex failed: ${e.message}")
        }

        return null
    }

    private fun tryGetRemoteAppDex(ctx: Context?, targetPkg: String): String? {
        try {
            val sanitized = targetPkg.replace('.', '_')
            val dex = ModulePrefsReader.getString("app_dex_$sanitized", null)
            if (!dex.isNullOrEmpty()) return dex
            log("XSharedPreferences app_dex_$sanitized is empty")
        } catch (e: Exception) {
            log("XSharedPreferences app_dex failed: ${e.message}")
        }

        try {
            if (ctx == null) return null
            log("Falling back to ContentProvider for app DEX ($targetPkg)")
            val uri = Uri.withAppendedPath(RuleProvider.URI_DEX, targetPkg)
            val cursor = ctx.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val dex = it.getString(0)
                    if (!dex.isNullOrEmpty()) return dex
                }
            }
        } catch (e: Exception) {
            log("ContentProvider app_dex failed: ${e.message}")
        }

        return null
    }

    // ─── 规则执行 ───────────────────────────────────────────────────────────────

    private fun applyRules(intent: Intent): Intent {
        val rules = rulesState?.rules
        if (rules == null || rules.list.isEmpty()) return intent

        val ruleIntent = Intent(intent)
        val resultIntent = Intent(intent)
        var executed = false
        for (rule in rules.list) {
            try {
                val matched = rule.evaluateMethod.invoke(null, currentContext, ruleIntent, resultIntent) as? Boolean ?: false
                if (matched) {
                    val shouldBlock = rule.executeMethod.invoke(null, currentContext, ruleIntent, resultIntent) as? Boolean ?: true
                    executed = true
                    if (shouldBlock) break
                }
            } catch (t: Throwable) {
                logThrowable(
                    "Rule execution failed id=${rule.ruleId} name=${rule.ruleName}",
                    t
                )
            }
        }
        return if (executed) resultIntent else intent
    }

    // ─── Hook 方法 ──────────────────────────────────────────────────────────────

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
                Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[4] as? Intent ?: return
                        val modifiedIntent = applyHookRules(lpparam, "Instrumentation", intent) ?: return
                        param.args[4] = modifiedIntent
                    }
                })
        } catch (t: Throwable) {
            logThrowable("Failed to hook Instrumentation.execStartActivity", t)
        }
    }

    private fun hookLauncher3(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader
            val cls = Class.forName("com.android.launcher3.Launcher", false, classLoader)
            var hooked = 0
            for (method in cls.declaredMethods) {
                if (method.name != "startActivitySafely") continue
                val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
                if (intentIndex < 0) continue
                hookMethodWithIntent(lpparam, "Launcher3", method, intentIndex)
                hooked++
            }
            if (hooked == 0) {
                log("Launcher3.startActivitySafely with Intent parameter not found")
            }
        } catch (t: Throwable) {
            logThrowable("Failed to hook Launcher3", t)
        }
    }

    private fun hookCustomClass(lpparam: XC_LoadPackage.LoadPackageParam, hookClassName: String) {
        try {
            val cls = Class.forName(hookClassName, false, lpparam.classLoader)
            val methods = cls.declaredMethods
            var hooked = 0
            for (method in methods) {
                if (!method.name.contains("startActivity")) continue
                val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
                if (intentIndex < 0) continue
                hookMethodWithIntent(lpparam, "Custom", method, intentIndex)
                hooked++
            }
            if (hooked == 0) {
                for (method in methods) {
                    if (!method.name.contains("startActivity")) continue
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (i in param.args.indices) {
                                val intent = param.args[i] as? Intent ?: continue
                                val modifiedIntent = applyHookRules(lpparam, "Custom", intent) ?: return
                                param.args[i] = modifiedIntent
                                return
                            }
                        }
                    })
                    log("Hooked $hookClassName.${method.name} (fallback)")
                }
                if (methods.none { it.name.contains("startActivity") }) {
                    log("No startActivity method found in $hookClassName")
                }
            }
        } catch (t: Throwable) {
            logThrowable("Failed to hook custom class $hookClassName", t)
        }
    }

    private fun hookMethodWithIntent(
        lpparam: XC_LoadPackage.LoadPackageParam,
        label: String,
        method: Method,
        intentIndex: Int
    ) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args.getOrNull(intentIndex) as? Intent ?: return
                val modifiedIntent = applyHookRules(lpparam, label, intent) ?: return
                param.args[intentIndex] = modifiedIntent
            }
        })
        log("Hooked $label ${method.declaringClass.name}#${method.name} intentArg=$intentIndex")
    }

    private fun applyHookRules(
        lpparam: XC_LoadPackage.LoadPackageParam,
        label: String,
        intent: Intent
    ): Intent? {
        val depth = ruleReentrancy.get() ?: 0
        if (depth > 0) return null
        ruleReentrancy.set(depth + 1)
        return try {
            if (currentContext == null) {
                currentContext = AndroidAppHelper.currentApplication()?.applicationContext
            }
            loadRulesIfNeeded(lpparam, currentContext)
            logIntent("$TAG $label: Original", intent)
            val modifiedIntent = applyRules(intent)
            if (modifiedIntent !== intent) {
                logIntent("$TAG $label: Modified", modifiedIntent)
            }
            modifiedIntent
        } catch (t: Throwable) {
            logThrowable("Rule hook failed label=$label", t)
            null
        } finally {
            ruleReentrancy.set((ruleReentrancy.get() ?: 1) - 1)
        }
    }
}

/**
 * 进程级 XSharedPreferences 读取器：避免每次 hook 都重新解析整份
 * 含 Base64 DEX 的 XML；reload 按 3 秒窗口节流。
 */
private object ModulePrefsReader {
    private const val MODULE_PACKAGE = "io.github.nobooooody.intent_modifier"
    private const val PREFS_NAME = "intent_modifier_config"
    private const val RELOAD_INTERVAL_MS = 3_000L

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences(MODULE_PACKAGE, PREFS_NAME).also { it.makeWorldReadable() }
    }

    private var loadedOnce = false
    private var lastReloadAt = 0L

    @Synchronized
    fun refresh(now: Long = SystemClock.elapsedRealtime(), force: Boolean = false) {
        if (!loadedOnce) {
            // 构造函数已经触发首次加载，这里只记录窗口起点。
            loadedOnce = true
            lastReloadAt = now
            return
        }
        if (force || now - lastReloadAt >= RELOAD_INTERVAL_MS) {
            prefs.reload()
            lastReloadAt = now
        }
    }

    fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    fun getString(key: String, default: String?): String? = prefs.getString(key, default)

    fun all(): Map<String, *> = prefs.all
}

data class LoadedRule(
    val ruleId: String,
    val ruleName: String,
    val evaluateMethod: java.lang.reflect.Method,
    val executeMethod: java.lang.reflect.Method
)

data class CompiledRules(val list: List<LoadedRule>)

const val HOOK_INSTRUMENTATION = "android.app.Instrumentation"
const val HOOK_LAUNCHER3 = "com.android.launcher3.Launcher"

object LauncherHooksLoader {
    private var hooks = mapOf<String, LoadedLauncherHook>()
    private var lastLoad = 0L
    private const val CACHE_DURATION = 5000L

    @Synchronized
    fun getHooks(): Map<String, LoadedLauncherHook> {
        if (System.currentTimeMillis() - lastLoad > CACHE_DURATION) loadHooks()
        return hooks
    }

    private fun loadHooks() {
        lastLoad = System.currentTimeMillis()
        val newHooks = mutableMapOf<String, LoadedLauncherHook>()
        try {
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", "intent_modifier_config")
            xprefs.makeWorldReadable()
            val jsonStr = xprefs.getString("launcher_hooks", null)
            if (jsonStr.isNullOrEmpty() || jsonStr == "{}") {
                hooks = emptyMap()
                return
            }
            val json = JSONObject(jsonStr)
            json.keys().forEach { pkg ->
                val hookJson = json.getJSONObject(pkg)
                newHooks[pkg] = LoadedLauncherHook(pkg, hookJson.optString("hookType", HOOK_INSTRUMENTATION))
            }
            hooks = newHooks
        } catch (e: Exception) {
            hooks = emptyMap()
        }
    }
}

data class LoadedLauncherHook(val packageName: String, val hookType: String)
