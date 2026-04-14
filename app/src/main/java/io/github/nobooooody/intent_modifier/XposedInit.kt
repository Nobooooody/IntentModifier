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

    private fun logIntent(prefix: String, intent: Intent) {
        val extras = intent.extras?.keySet()?.joinToString(",") ?: "null"
        val fillIn = intent.fillIn(Intent.FILL_IN_ACTION, Intent.FILL_IN_DATA) // check fillIn compatibility
        XposedBridge.log("$prefix pkg=${intent.`package`}, component=${intent.component}, action=${intent.action}, data=${intent.data}, dataString=${intent.dataString}, type=${intent.type}, flags=${intent.flags}, categories=${intent.categories}, scheme=${intent.scheme}, selector=${intent.selector}, sourceBounds=${intent.sourceBounds}, identifier=${intent.identifier}, resolveType=${intent.resolveType(null)}, extras=[$extras]")
    }

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
                    logIntent("IntentModifier: Original", intent)
                    val rule = RulesLoader.getRule(targetPackage)
                    if (rule != null && rule.enabled) {
                        val modifiedIntent = rule.apply(intent)
                        param.args[4] = modifiedIntent
                        logIntent("IntentModifier: Modified", modifiedIntent)
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
                            logIntent("IntentModifier L3: Original", intent)
                            val rule = RulesLoader.getRule(targetPackage)
                            if (rule != null && rule.enabled) {
                                val modifiedIntent = rule.apply(intent)
                                param.args[1] = modifiedIntent
                                logIntent("IntentModifier L3: Modified", modifiedIntent)
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
                        val valuesJson = e.optJSONArray("values")
                        val values = if (valuesJson != null) {
                            (0 until valuesJson.length()).map { valuesJson.getString(it) }
                        } else {
                            listOf(e.optString("value", ""))
                        }
                        LoadedExtra(e.getString("key"), e.getString("type"), values)
                    }
                } else emptyList()
                newRules[pkg] = LoadedRule(
                    ruleObj.optBoolean("enabled", true),
                    ruleObj.optString("customAction").ifEmpty { null },
                    ruleObj.optString("customData").ifEmpty { null },
                    ruleObj.optString("customPackage").ifEmpty { null },
                    ruleObj.optString("customClass").ifEmpty { null },
                    if (ruleObj.has("customFlags")) ruleObj.getInt("customFlags") else null,
                    parseCategories(ruleObj.optJSONArray("customCategories")),
                    ruleObj.optString("customType").ifEmpty { null },
                    extras
                )
            }
            rules = newRules
        } catch (e: Exception) {
            rules = emptyMap()
        }
    }

    private fun parseCategories(json: org.json.JSONArray?): List<String> {
        if (json == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until json.length()) {
            result.add(json.getString(i))
        }
        return result
    }
}

data class LoadedRule(
    val enabled: Boolean,
    val customAction: String?,
    val customData: String?,
    val customPackage: String?,
    val customClass: String?,
    val customFlags: Int?,
    val customCategories: List<String>,
    val customType: String?,
    val extras: List<LoadedExtra>
) {
    fun apply(intent: Intent): Intent {
        val modified = Intent(intent)
        customAction?.let { modified.action = it }
        customData?.let { modified.data = android.net.Uri.parse(it) }

        if (customPackage != null || customClass != null) {
            val resolvedClass = when {
                customClass.isNullOrEmpty() -> intent.component?.className
                customClass!!.startsWith(".") -> (customPackage ?: intent.component?.packageName ?: "") + customClass
                else -> customClass
            }
            val finalPkg = customPackage ?: intent.component?.packageName
            if (resolvedClass != null && finalPkg != null) {
                modified.setClassName(finalPkg, resolvedClass)
                modified.setPackage(finalPkg)
            }
        }

        customFlags?.let { modified.addFlags(it) }
        if (customCategories.isNotEmpty()) {
            modified.categories?.forEach { modified.removeCategory(it) }
            customCategories.forEach { modified.addCategory(it) }
        }
        customType?.let { modified.setType(it) }

        extras.forEach { extra ->
            when (extra.type) {
                "Boolean" -> modified.putExtra(extra.key, extra.values[0] == "true")
                "BooleanArray" -> modified.putExtra(extra.key, extra.values.map { it == "true" }.toBooleanArray())
                "Integer" -> extra.values[0].toIntOrNull()?.let { modified.putExtra(extra.key, it) }
                "IntArray" -> modified.putExtra(extra.key, extra.values.map { it.toInt() }.toIntArray())
                "Long" -> extra.values[0].toLongOrNull()?.let { modified.putExtra(extra.key, it) }
                "LongArray" -> modified.putExtra(extra.key, extra.values.map { it.toLong() }.toLongArray())
                "Float" -> extra.values[0].toFloatOrNull()?.let { modified.putExtra(extra.key, it) }
                "FloatArray" -> modified.putExtra(extra.key, extra.values.map { it.toFloat() }.toFloatArray())
                "DoubleArray" -> modified.putExtra(extra.key, extra.values.map { it.toDouble() }.toDoubleArray())
                "String" -> modified.putExtra(extra.key, extra.values[0])
                "StringArray" -> modified.putExtra(extra.key, extra.values.toTypedArray())
                "URI" -> modified.putExtra(extra.key, android.net.Uri.parse(extra.values[0]))
                "ByteArray" -> modified.putExtra(extra.key, extra.values.map { it.toByte() }.toByteArray())
                "CharArray" -> modified.putExtra(extra.key, extra.values.map { it[0] }.toCharArray())
                "ShortArray" -> modified.putExtra(extra.key, extra.values.map { it.toShort() }.toShortArray())
                "CharSequenceArray" -> modified.putExtra(extra.key, extra.values.map { it as CharSequence }.toTypedArray())
                "ComponentName" -> modified.putExtra(extra.key, android.content.ComponentName(extra.values[0], extra.values[0].substringAfterLast(".")))
                "ParcelableArray" -> {} // Parcelable needs class loader
                else -> modified.putExtra(extra.key, extra.values[0])
            }
        }
        return modified
    }
}

data class LoadedExtra(val key: String, val type: String, val values: List<String>)

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