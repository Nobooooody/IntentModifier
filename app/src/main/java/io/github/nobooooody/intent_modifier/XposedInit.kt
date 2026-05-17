package io.github.nobooooody.intent_modifier

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import io.github.nobooooody.intent_modifier.ui.provider.RuleProvider
import android.os.Build
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
import org.json.JSONObject
import java.io.File

class XposedInit : IXposedHookLoadPackage {

    var currentContext: Context? =  null
    private var lastVersion: Long = 0
    private var compiledRules: CompiledRules? = null

    private companion object {
        private const val TAG = "IntentModifier"
        private const val PREFS_NAME = "intent_modifier_config"
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val KEY_COMPILED_DEX = "compiled_dex"
        private const val KEY_RULES_HASH = "rules_hash"
        private const val KEY_RULE_COUNT = "rule_count"

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
            HOOK_LAUNCHER3 -> {
                hookLauncher3(lpparam)
            }
            HOOK_INSTRUMENTATION -> {
                hookInstrumentation(lpparam)
            }
            else -> {
                hookCustomClass(lpparam, hookType)
            }
        }
    }

    private fun loadRulesIfNeeded(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context?) {
        try {
            val targetPkg = lpparam.packageName
            val targetDataDir = "/data/data/$targetPkg"

            val remoteVersion = tryGetRemoteVersion(lpparam, ctx)
            if (remoteVersion == null || remoteVersion == 0L) {
                log("No remote rules found (XSharedPreferences and ContentProvider both failed)")
                return
            }

            val localDexFile = File("$targetDataDir/cache/intent_modifier_rules/rules.dex")
            val localMetaFile = File("$targetDataDir/cache/intent_modifier_rules/meta.json")

            var localVersion = 0L
            var localHash = ""
            var localRuleCount = 0

            if (localDexFile.exists() && localMetaFile.exists()) {
                try {
                    val metaJson = JSONObject(localMetaFile.readText())
                    localVersion = metaJson.optLong("version", 0)
                    localHash = metaJson.optString("hash", "")
                    localRuleCount = metaJson.optInt("count", 0)
                } catch (e: Exception) {
                    log("Failed to read local meta: ${e.message}")
                }
            }

            if (remoteVersion == localVersion && localDexFile.exists() && localRuleCount > 0) {
                log("Local rules version=$localVersion is up to date")
                tryLoadLocalDex(lpparam, localDexFile, localRuleCount)
                return
            }

            log("Need to update rules: remote=$remoteVersion, local=$localVersion")
            val remoteDex = tryGetRemoteDex(ctx)
            if (remoteDex.isNullOrEmpty()) {
                if (localDexFile.exists() && localRuleCount > 0) {
                    log("No remote dex available, falling back to local")
                    tryLoadLocalDex(lpparam, localDexFile, localRuleCount)
                }
                return
            }

            val dexBytes = Base64.decode(remoteDex, Base64.NO_WRAP)
            val remoteHash = tryGetRemoteHash(ctx) ?: ""
            val remoteRuleCount = tryGetRemoteRuleCount(ctx)

            val rulesDir = File("$targetDataDir/cache/intent_modifier_rules")
            rulesDir.deleteRecursively()
            rulesDir.mkdirs()

            localDexFile.writeBytes(dexBytes)
            localDexFile.setReadOnly()
            localMetaFile.writeText(JSONObject().apply {
                put("version", remoteVersion)
                put("hash", remoteHash)
                put("count", remoteRuleCount)
            }.toString())

            tryLoadLocalDex(lpparam, localDexFile, remoteRuleCount)

        } catch (e: Exception) {
            log("Failed to load rules: ${e.message}")
        }
    }

    private fun tryGetRemoteVersion(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context?): Long? {
        try {
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", PREFS_NAME)
            xprefs.makeWorldReadable()
            val version = xprefs.getLong(KEY_COMPILED_VERSION, 0L)
            if (version > 0) {
                log("Got remote version=$version via XSharedPreferences")
                return version
            }
        } catch (e: Exception) {
            log("XSharedPreferences failed: ${e.message}")
        }

        try {
            val cursor = ctx?.contentResolver?.query(RuleProvider.URI_VERSION, null, null, null, null)
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
            val dex = xprefs.getString(KEY_COMPILED_DEX, null)
            if (!dex.isNullOrEmpty()) {
                return dex
            }
        } catch (e: Exception) {
            log("XSharedPreferences dex read failed: ${e.message}")
        }

        try {
            val cursor = ctx?.contentResolver?.query(RuleProvider.URI_DEX, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val dex = it.getString(0)
                    if (!dex.isNullOrEmpty()) {
                        return dex
                    }
                }
            }
        } catch (e: Exception) {
            log("ContentProvider dex failed: ${e.message}")
        }

        return null
    }

    private fun tryGetRemoteHash(ctx: Context?): String? {
        try {
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", PREFS_NAME)
            xprefs.makeWorldReadable()
            val hash = xprefs.getString(KEY_RULES_HASH, null)
            if (hash != null) {
                log("Got hash=${hash.take(16)}... via XSharedPreferences")
                return hash
            }
        } catch (e: Exception) {
            log("XSharedPreferences hash failed: ${e.message}")
        }

        try {
            ctx?.contentResolver?.query(RuleProvider.URI_HASH, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val hash = cursor.getString(0)
                    if (!hash.isNullOrEmpty()) {
                        log("Got hash=${hash.take(16)}... via ContentProvider")
                        return hash
                    }
                }
            }
        } catch (e: Exception) {
            log("ContentProvider hash failed: ${e.message}")
        }

        return null
    }

    private fun tryGetRemoteRuleCount(ctx: Context?): Int {
        try {
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", PREFS_NAME)
            xprefs.makeWorldReadable()
            val count = xprefs.getInt(KEY_RULE_COUNT, 0)
            if (count > 0) {
                log("Got rule count=$count via XSharedPreferences")
                return count
            }
        } catch (e: Exception) {
            log("XSharedPreferences rule count failed: ${e.message}")
        }

        try {
            ctx?.contentResolver?.query(RuleProvider.URI_COUNT, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val count = cursor.getInt(0)
                    log("Got rule count=$count via ContentProvider")
                    return count
                }
            }
        } catch (e: Exception) {
            log("ContentProvider rule count failed: ${e.message}")
        }

        return 0
    }

    private fun tryLoadLocalDex(lpparam: XC_LoadPackage.LoadPackageParam, dexFile: File, ruleCount: Int) {
        try {
            if (compiledRules != null && lastVersion > 0) {
                return
            }

            val targetPkg = lpparam.packageName
            val targetDataDir = "/data/data/$targetPkg"
            val optimizedDir = File("$targetDataDir/code_cache/optimized")
            optimizedDir.mkdirs()

            val dexClassLoader = dalvik.system.DexClassLoader(
                dexFile.absolutePath,
                optimizedDir.absolutePath,
                dexFile.parentFile?.absolutePath,
                lpparam.classLoader
            )

            val rules = mutableListOf<LoadedRule>()
            for (i in 0 until ruleCount) {
                try {
                    val ruleClass = dexClassLoader.loadClass("engine.Rule_$i")
                    val evaluateMethod = ruleClass.getMethod("evaluate", Intent::class.java, Intent::class.java)
                    val executeMethod = ruleClass.getMethod("execute", Intent::class.java, Intent::class.java)
                    rules.add(LoadedRule(evaluateMethod, executeMethod))
                    log("Loaded Rule_$i")
                } catch (e: Exception) {
                    log("Failed to load Rule_$i: ${e.message}")
                }
            }

            compiledRules = if (rules.isNotEmpty()) CompiledRules(rules) else null
            log("Successfully loaded ${rules.size} rules from local DEX")
        } catch (e: Exception) {
            log("Failed to load local DEX: ${e.message}")
        }
    }

    private fun applyRules(intent: Intent): Intent {
        val rules = compiledRules
        if (rules == null || rules.list.isEmpty()) {
            return intent
        }

        val resultIntent = Intent(intent)
        var matched = false
        for (rule in rules.list) {
            try {
                val evalResult = rule.evaluateMethod.invoke(null, intent, resultIntent) as? Boolean ?: false
                if (evalResult) {
                    rule.executeMethod.invoke(null, intent, resultIntent)
                    matched = true
                    break
                }
            } catch (e: Exception) {
                log("Rule evaluation failed: ${e.message}")
            }
        }
        return if (matched) resultIntent else intent
    }

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
            Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentContext == null) {
                        currentContext = AndroidAppHelper.currentApplication().getApplicationContext()
                    }
                    loadRulesIfNeeded(lpparam,currentContext)
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
                                currentContext = AndroidAppHelper.currentApplication().getApplicationContext()
                            }
                            loadRulesIfNeeded(lpparam,currentContext)
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
                                currentContext = AndroidAppHelper.currentApplication().getApplicationContext()
                            }
                            loadRulesIfNeeded(lpparam,currentContext)
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
                                    currentContext = AndroidAppHelper.currentApplication().getApplicationContext()
                                }
                                loadRulesIfNeeded(lpparam,currentContext)
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