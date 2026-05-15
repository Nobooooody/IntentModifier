package io.github.nobooooody.intent_modifier

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File

class XposedInit : IXposedHookLoadPackage {

    companion object {
        private val TAG = "IntentModifier"
        private var lastVersion: Long = 0
        private var compiledRules: CompiledRules? = null
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
        XposedBridge.log("$TAG: Loading package=$currentPkg")

        if (currentPkg == "io.github.nobooooody.intent_modifier") {
            XposedBridge.log("$TAG: Skipping self")
            return
        }

        val launcherHooks = LauncherHooksLoader.getHooks()
        val hookType = launcherHooks[currentPkg]?.hookType ?: HOOK_INSTRUMENTATION

        loadRulesIfNeeded(lpparam)

        when (hookType) {
            HOOK_LAUNCHER3 -> {
                XposedBridge.log("$TAG: Using Launcher3 hook for $currentPkg")
                hookLauncher3(lpparam)
            }
            HOOK_INSTRUMENTATION -> {
                hookInstrumentation(lpparam)
            }
            else -> {
                XposedBridge.log("$TAG: Using custom hook class: $hookType")
                hookCustomClass(lpparam, hookType)
            }
        }
    }

    private fun loadRulesIfNeeded(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", "intent_modifier_config")
            val version = xprefs.getLong("compiled_version", 0L)
            val rulesCount = xprefs.getInt("rule_count", 0)
            
            if (version == 0L || rulesCount == 0) {
                XposedBridge.log("$TAG: No compiled rules found")
                return
            }
            
            if (version == lastVersion && compiledRules != null) {
                XposedBridge.log("$TAG: Using cached rules version=$version")
                return
            }
            
            XposedBridge.log("$TAG: Loading rules version=$version, count=$rulesCount")
            
            val dexBase64 = xprefs.getString("compiled_dex", null)
            if (dexBase64.isNullOrEmpty()) {
                XposedBridge.log("$TAG: No compiled dex in prefs")
                return
            }
            
            val dexBytes = Base64.decode(dexBase64, Base64.NO_WRAP)
            XposedBridge.log("$TAG: DEX size: ${dexBytes.size} bytes")
            
            val targetPkg = lpparam.packageName
            val targetDataDir = "/data/data/$targetPkg"
            val loadedDir = File("$targetDataDir/cache/intent_modifier_rules")
            loadedDir.deleteRecursively()
            loadedDir.mkdirs()
            
            val dexFile = File(loadedDir, "rules.dex")
            dexFile.writeBytes(dexBytes)
            dexFile.setReadOnly()
            
            val optimizedDir = File("$targetDataDir/code_cache/optimized")
            optimizedDir.mkdirs()
            val dexClassLoader = dalvik.system.DexClassLoader(
                dexFile.absolutePath,
                optimizedDir.absolutePath,
                dexFile.parentFile?.absolutePath,
                lpparam.classLoader
            )
            
            val rules = mutableListOf<LoadedRule>()
            for (i in 0 until rulesCount) {
                try {
                    val ruleClass = dexClassLoader.loadClass("engine.Rule_$i")
                    val evaluateMethod = ruleClass.getMethod("evaluate", Intent::class.java, Intent::class.java)
                    val executeMethod = ruleClass.getMethod("execute", Intent::class.java, Intent::class.java)
                    rules.add(LoadedRule(evaluateMethod, executeMethod))
                    XposedBridge.log("$TAG: Loaded Rule_$i")
                } catch (e: Exception) {
                    XposedBridge.log("$TAG: Failed to load Rule_$i: ${e.message}")
                }
            }
            
            lastVersion = version
            compiledRules = if (rules.isNotEmpty()) CompiledRules(rules) else null
            XposedBridge.log("$TAG: Successfully loaded ${rules.size} rules")
            
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to load rules: ${e.message}")
        }
    }

    private fun applyRules(intent: Intent): Intent {
        val rules = compiledRules
        if (rules == null || rules.list.isEmpty()) {
            return intent
        }
        
        val resultIntent = Intent(intent)
        for (rule in rules.list) {
            try {
                val evalResult = rule.evaluateMethod.invoke(null, intent, resultIntent) as? Boolean ?: false
                if (evalResult) {
                    rule.executeMethod.invoke(null, intent, resultIntent)
                    return resultIntent
                }
            } catch (e: Exception) {
                XposedBridge.log("$TAG: Rule evaluation failed: ${e.message}")
            }
        }
        return intent
    }

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
            Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args[4] as? Intent ?: return
                    if (intent.component == null) return

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
                            val intent = param.args[1] as? Intent ?: return
                            if (intent.component == null) return

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
            XposedBridge.log("$TAG: Failed to hook Launcher3: ${e.message}")
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
                            val intent = param.args[intentIndex] as? Intent ?: return
                            if (intent.component == null) return

                            logIntent("$TAG Custom: Original", intent)
                            val modifiedIntent = applyRules(intent)
                            if (modifiedIntent !== intent) {
                                logIntent("$TAG Custom: Modified", modifiedIntent)
                            }
                            param.args[intentIndex] = modifiedIntent
                        }
                    })
                    XposedBridge.log("$TAG: Hooked $hookClassName.$method")
                    hooked = true
                }
            }

            if (!hooked) {
                for (method in methods) {
                    if (method.name.contains("startActivity")) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                for (i in param.args.indices) {
                                    if (param.args[i] is Intent) {
                                        val intent = param.args[i] as Intent
                                        if (intent.component != null) {
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
                            }
                        })
                        XposedBridge.log("$TAG: Hooked $hookClassName.$method (fallback)")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to hook custom class $hookClassName: ${e.message}")
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