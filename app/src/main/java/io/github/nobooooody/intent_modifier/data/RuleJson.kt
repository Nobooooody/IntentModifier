package io.github.nobooooody.intent_modifier.data

import org.json.JSONArray
import org.json.JSONObject

object RuleJson {
    fun javaRulesArray(rules: List<JavaCodeRule>): JSONArray {
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
        return json
    }

    fun normalRulesArray(rules: List<NormalRule>): JSONArray {
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
                if (rule.replaceCategories) put("replaceCategories", true)
                if (rule.replaceExtras) put("replaceExtras", true)
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
        return json
    }

    fun javaRulesJson(rules: List<JavaCodeRule>): String = javaRulesArray(rules).toString()

    fun normalRulesJson(rules: List<NormalRule>): String = normalRulesArray(rules).toString()
}
