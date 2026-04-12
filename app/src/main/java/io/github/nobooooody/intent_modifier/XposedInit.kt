package io.github.nobooooody.intent_modifier

import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

class XposedInit : IXposedHookLoadPackage {

    private val targetLaunchers = setOf(
        "app.lawnchair",
        "app.lawnchair.play",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.android.launcher"
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in targetLaunchers) return

        try {
            val classLoader = lpparam.classLoader
            for (className in listOf("com.android.launcher3.Launcher")) {
                try {
                    val cls = Class.forName(className, false, classLoader)
                    for (method in cls.declaredMethods) {
                        if (method.name == "startActivitySafely") {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val intent = param.args[1] as Intent
                                    val targetPackage = try {
                                        val field = param.args[2]?.javaClass?.getDeclaredField("targetComponent")
                                        field?.isAccessible = true
                                        (field?.get(param.args[2]) as? ComponentName)?.packageName
                                    } catch (e: Exception) { intent.component?.packageName }

                                    if (targetPackage != null) {
                                        val rule = RulesLoader.getRule(targetPackage)
                                        if (rule != null && rule.enabled) {
                                            param.args[1] = rule.apply(intent)
                                        }
                                    }
                                }
                            })
                            return
                        }
                    }
                } catch (e: Exception) { }
            }
        } catch (e: Throwable) { }
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