package io.github.nobooooody.intent_modifier

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
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

    var currentContext: Context? = null
    private var lastVersion: Long = 0
    private var compiledRules: CompiledRules? = null

    private companion object {
        private const val TAG = "IntentModifier"
        private const val PREFS_NAME = "intent_modifier_config"
        private const val KEY_COMPILED_VERSION = "compiled_version"

        private val MODULE_PACKAGE = "io.github.nobooooody.intent_modifier"
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun logIntent(prefix: String, intent: Intent) {
        val extrasStr = buildString {
            intent.extras?.keySet()?.forEach { key ->
                if (isNotEmpty()) append(", ")
                append("$key=")
                when (val v = intent.extras?.get(key)) {
                    is Boolean -> append(v.toString())
                    is BooleanArray -> append(v.contentToString())
                    is Int -> append(v.toString())
                    is IntArray -> append(v.contentToString())
                    is Long -> append(v.toString())
                    is LongArray -> append(v.contentToString())
                    is Float -> append(v.toString())
                    is FloatArray -> append(v.contentToString())
                    is DoubleArray -> append(v.contentToString())
                    is String -> append(v)
                    is Array<*> -> append(v.contentToString())
                    is android.os.Parcelable -> append(v.javaClass.simpleName)
                    else -> append(v?.toString() ?: "null")
                }
            }
        }
        XposedBridge.log("$prefix pkg=${intent.`package`}, component=${intent.component}, action=${intent.action}, data=${intent.data}, dataString=${intent.dataString}, type=${intent.type}, flags=${intent.flags}, categories=${intent.categories}, extras={$extrasStr}")
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
    }

    // ─── 规则加载 ───────────────────────────────────────────────────────────────

    private fun loadRulesIfNeeded(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context?) {
        try {
            val targetPkg = lpparam.packageName
            val targetDataDir = "/data/data/$targetPkg"

            val remoteVersion = tryGetRemoteVersion(lpparam)

            val localMetaFile = File("$targetDataDir/cache/intent_modifier_rules/meta.json")
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
            val rulesDir = File("$targetDataDir/cache/intent_modifier_rules")
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

            loadDexAndBuildRules(lpparam, targetPkg, remoteVersion)

        } catch (e: Exception) {
            log("Failed to load rules: ${e.message}")
        }
    }

    private fun tryLoadLocalCached(lpparam: XC_LoadPackage.LoadPackageParam, targetPkg: String): Boolean {
        val rulesDir = File("/data/data/$targetPkg/cache/intent_modifier_rules")
        val sharedDex = File(rulesDir, "rules_shared.dex")
        val metaFile = File(rulesDir, "meta.json")
        if (!sharedDex.exists() || !metaFile.exists()) return false

        val version = try { JSONObject(metaFile.readText()).optLong("version", 0) } catch (e: Exception) { 0L }
        loadDexAndBuildRules(lpparam, targetPkg, version)
        return true
    }

    private fun loadDexAndBuildRules(lpparam: XC_LoadPackage.LoadPackageParam, targetPkg: String, version: Long) {
        try {
            if (compiledRules != null && lastVersion == version && lastVersion > 0) return

            val rulesDir = "/data/data/$targetPkg/cache/intent_modifier_rules"
            val dexPaths = mutableListOf("$rulesDir/rules_shared.dex")
            val appDex = File("$rulesDir/rules_app.dex")
            if (appDex.exists()) dexPaths.add(appDex.absolutePath)

            val optimizedDir = File("$rulesDir/optimized").also { it.mkdirs() }
            val path = dexPaths.joinToString(":")
            val parentLoader = lpparam.classLoader
            val dexClassLoader = dalvik.system.DexClassLoader(path, optimizedDir.absolutePath, null, parentLoader)

            // 加载 shared RuleRegistry
            val registryClasses = mutableListOf<Pair<String, String>>()
            loadRegistry(dexClassLoader, "engine.RuleRegistry_shared")?.let { registryClasses.addAll(it) }

            // 如果有 app DEX，加载 app RuleRegistry
            if (appDex.exists()) {
                val sanitizedPkg = targetPkg.replace('.', '_')
                loadRegistry(dexClassLoader, "engine.RuleRegistry_app_$sanitizedPkg")?.let { registryClasses.addAll(it) }
            }

            // 按 priority 降序排列
            registryClasses.sortByDescending { it.second.toIntOrNull() ?: 0 }

            // 加载每条规则的方法
            val rules = mutableListOf<LoadedRule>()
            for ((className, _) in registryClasses) {
                try {
                    val ruleClass = dexClassLoader.loadClass(className)
                    val evaluateMethod = ruleClass.getMethod("evaluate", Context::class.java, Intent::class.java, Intent::class.java)
                    val executeMethod = ruleClass.getMethod("execute", Context::class.java, Intent::class.java, Intent::class.java)
                    rules.add(LoadedRule(evaluateMethod, executeMethod))
                    log("Loaded $className")
                } catch (e: Exception) {
                    log("Failed to load $className: ${e.message}")
                }
            }

            compiledRules = if (rules.isNotEmpty()) CompiledRules(rules) else null
            lastVersion = version
            log("Successfully loaded ${rules.size} rules from ${dexPaths.size} DEX file(s)")
        } catch (e: Exception) {
            log("Failed to load DEX: ${e.message}")
        }
    }

    private fun loadRegistry(classLoader: ClassLoader, registryName: String): List<Pair<String, String>>? {
        return try {
            val registryClass = classLoader.loadClass(registryName)
            val raw = registryClass.getMethod("getRules").invoke(null) as Array<Array<Any>>
            raw.map { Pair(it[0] as String, (it[1] as Int).toString()) }
        } catch (e: Exception) {
            log("Registry $registryName not found: ${e.message}")
            null
        }
    }

    // ─── 远端读取 ───────────────────────────────────────────────────────────────

    private fun tryGetRemoteVersion(lpparam: XC_LoadPackage.LoadPackageParam): Long? {
        try {
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", PREFS_NAME)
            xprefs.makeWorldReadable()
            val version = xprefs.getLong(KEY_COMPILED_VERSION, 0L)
            if (version > 0) {
                log("Got remote version=$version via XSharedPreferences")
                return version
            }
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
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", PREFS_NAME)
            xprefs.makeWorldReadable()
            val dex = xprefs.getString("shared_dex", null)
            if (!dex.isNullOrEmpty()) return dex
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
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", PREFS_NAME)
            xprefs.makeWorldReadable()
            val dex = xprefs.getString("app_dex_$sanitized", null)
            if (!dex.isNullOrEmpty()) return dex
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
        val rules = compiledRules
        if (rules == null || rules.list.isEmpty()) return intent

        val resultIntent = Intent(intent)
        var executed = false
        for (rule in rules.list) {
            try {
                val matched = rule.evaluateMethod.invoke(null, currentContext, intent, resultIntent) as? Boolean ?: false
                if (matched) {
                    val shouldBlock = rule.executeMethod.invoke(null, currentContext, intent, resultIntent) as? Boolean ?: true
                    executed = true
                    if (shouldBlock) break
                }
            } catch (e: Exception) {
                log("Rule evaluation failed: ${e.message}")
            }
        }
        return if (executed) resultIntent else intent
    }

    // ─── Hook 方法 ──────────────────────────────────────────────────────────────

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
            Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentContext == null) {
                        currentContext = AndroidAppHelper.currentApplication()?.applicationContext
                    }
                    loadRulesIfNeeded(lpparam, currentContext)
                    val intent = param.args[4] as? Intent ?: return

                    logIntent("$TAG: Original", intent)
                    val modifiedIntent = applyRules(intent)
                    if (modifiedIntent !== intent) {
                        logIntent("$TAG: Modified", modifiedIntent)
                    }
                    param.args[4] = modifiedIntent
                }
            })
    }

    private fun hookLauncher3(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader
            val cls = Class.forName("com.android.launcher3.Launcher", false, classLoader)
            for (method in cls.declaredMethods) {
                if (method.name == "startActivitySafely") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (currentContext == null) {
                                currentContext = AndroidAppHelper.currentApplication()?.applicationContext
                            }
                            loadRulesIfNeeded(lpparam, currentContext)
                            val intent = param.args[1] as? Intent ?: return

                            logIntent("$TAG L3: Original", intent)
                            val modifiedIntent = applyRules(intent)
                            if (modifiedIntent !== intent) {
                                logIntent("$TAG L3: Modified", modifiedIntent)
                            }
                            param.args[1] = modifiedIntent
                        }
                    })
                    return
                }
            }
        } catch (e: Exception) {
            log("Failed to hook Launcher3: ${e.message}")
        }
    }

    private fun hookCustomClass(lpparam: XC_LoadPackage.LoadPackageParam, hookClassName: String) {
        try {
            val cls = Class.forName(hookClassName, false, lpparam.classLoader)
            val methods = cls.declaredMethods
            var hooked = false
            for (method in methods) {
                if (method.name.contains("startActivity") && method.parameterTypes.any { it == Intent::class.java }) {
                    val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (currentContext == null) {
                                currentContext = AndroidAppHelper.currentApplication()?.applicationContext
                            }
                            loadRulesIfNeeded(lpparam, currentContext)
                            val intent = param.args[intentIndex] as? Intent ?: return
                            logIntent("$TAG Custom: Original", intent)
                            val modifiedIntent = applyRules(intent)
                            if (modifiedIntent !== intent) {
                                logIntent("$TAG Custom: Modified", modifiedIntent)
                            }
                            param.args[intentIndex] = modifiedIntent
                        }
                    })
                    log("Hooked $hookClassName.$method")
                    hooked = true
                }
            }
            if (!hooked) {
                for (method in methods) {
                    if (method.name.contains("startActivity")) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (currentContext == null) {
                                    currentContext = AndroidAppHelper.currentApplication()?.applicationContext
                                }
                                loadRulesIfNeeded(lpparam, currentContext)
                                for (i in param.args.indices) {
                                    if (param.args[i] is Intent) {
                                        val intent = param.args[i] as Intent
                                        logIntent("$TAG Custom: Original", intent)
                                        val modifiedIntent = applyRules(intent)
                                        if (modifiedIntent !== intent) {
                                            logIntent("$TAG Custom: Modified", modifiedIntent)
                                        }
                                        param.args[i] = modifiedIntent
                                        return
                                    }
                                }
                            }
                        })
                        log("Hooked $hookClassName.$method (fallback)")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            log("Failed to hook custom class $hookClassName: ${e.message}")
        }
    }
}

data class LoadedRule(
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
