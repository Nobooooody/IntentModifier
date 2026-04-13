package io.github.nobooooody.intent_modifier

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val launcherHooks = LauncherHooksLoader.getHooks()
        val currentPkg = lpparam.packageName
        
        val hookType = launcherHooks[currentPkg]?.hookType ?: HOOK_INSTRUMENTATION
        XposedBridge.log("IntentModifier: Loading package=$currentPkg, using hook=$hookType")
        
        when (hookType) {
            HOOK_LAUNCHER3 -> {
                XposedBridge.log("IntentModifier: Using Launcher3 hook for $currentPkg")
                hookLauncher3(lpparam)
            }
            else -> hookInstrumentation(lpparam)
        }
    }
    
    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
            Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as? Context ?: return
                    val intent = param.args[4] as? Intent ?: return
                    if (intent.component == null) return

                    val targetPackage = intent.component!!.packageName
                    val rule = RulesLoader.getRule(targetPackage)
                    if (rule != null && rule.enabled) {
                        param.args[4] = rule.apply(intent)
                        XposedBridge.log("IntentModifier: Modified intent for $targetPackage")
                    }
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

                            val targetPackage = intent.component!!.packageName
                            val rule = RulesLoader.getRule(targetPackage)
                            if (rule != null && rule.enabled) {
                                param.args[1] = rule.apply(intent)
                                XposedBridge.log("IntentModifier: Modified intent for $targetPackage via Launcher3")
                            }
                        }
                    })
                    return
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("IntentModifier: Failed to hook Launcher3: ${e.message}")
        }
    }
}

object RulesLoader {
    private var rules = mapOf<String, LoadedRule>()
    private var lastLoad = 0L
    private const val CACHE_DURATION = 5000L

    @Synchronized
    fun getRule(packageName: String): LoadedRule? {
        if (System.currentTimeMillis() - lastLoad > CACHE_DURATION) loadRules()
        return rules[packageName]
    }

    private fun loadRules() {
        lastLoad = System.currentTimeMillis()
        val newRules = mutableMapOf<String, LoadedRule>()
        try {
            val xprefs = XSharedPreferences("io.github.nobooooody.intent_modifier", "intent_modifier_config")
            val jsonStr = xprefs.getString("modifier_rules", null)
            if (jsonStr.isNullOrEmpty() || jsonStr == "{}") {
                rules = emptyMap()
                return
            }
            val json = JSONObject(jsonStr)
            json.keys().forEach { pkg ->
                val ruleObj = json.getJSONObject(pkg)
                val extrasArray = ruleObj.optJSONArray("extras")
                val extras = if (extrasArray != null) {
                    (0 until extrasArray.length()).map { i ->
                        val e = extrasArray.getJSONObject(i)
                        LoadedExtra(e.getString("key"), e.getString("type"), e.getString("value"))
                    }
                } else emptyList()
                newRules[pkg] = LoadedRule(
                    ruleObj.optBoolean("enabled", true),
                    ruleObj.optString("customAction").ifEmpty { null },
                    ruleObj.optString("customData").ifEmpty { null },
                    ruleObj.optString("customPackage").ifEmpty { null },
                    ruleObj.optString("customClass").ifEmpty { null },
                    extras
                )
            }
            rules = newRules
        } catch (e: Exception) {
            rules = emptyMap()
        }
    }
}

data class LoadedRule(
    val enabled: Boolean,
    val customAction: String?,
    val customData: String?,
    val customPackage: String?,
    val customClass: String?,
    val extras: List<LoadedExtra>
) {
    fun apply(intent: Intent): Intent {
        val modified = Intent(intent)
        customAction?.let { modified.action = it }
        customData?.let { modified.data = android.net.Uri.parse(it) }

        if (customPackage != null || customClass != null) {
            val basePkg = intent.component?.packageName ?: customPackage ?: ""
            val resolvedClass = when {
                customClass.isNullOrEmpty() -> intent.component?.className
                customClass!!.startsWith(".") -> basePkg + customClass
                else -> customClass
            }
            val finalPkg = customPackage ?: basePkg
            if (resolvedClass != null) {
                modified.setClassName(finalPkg, resolvedClass)
            }
        }

        extras.forEach { extra ->
            when (extra.type) {
                "Boolean" -> modified.putExtra(extra.key, extra.value == "true")
                "Integer" -> extra.value.toIntOrNull()?.let { modified.putExtra(extra.key, it) }
                "Long" -> extra.value.toLongOrNull()?.let { modified.putExtra(extra.key, it) }
                "Decimal Number" -> extra.value.toDoubleOrNull()?.let { modified.putExtra(extra.key, it) }
                "String" -> modified.putExtra(extra.key, extra.value)
                "URI" -> modified.putExtra(extra.key, android.net.Uri.parse(extra.value))
                else -> modified.putExtra(extra.key, extra.value)
            }
        }
        return modified
    }
}

data class LoadedExtra(val key: String, val type: String, val value: String)

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
                newHooks[pkg] = LoadedLauncherHook(
                    pkg,
                    hookJson.optString("hookType", HOOK_INSTRUMENTATION)
                )
            }
            hooks = newHooks
        } catch (e: Exception) {
            hooks = emptyMap()
        }
    }
}

data class LoadedLauncherHook(val packageName: String, val hookType: String)