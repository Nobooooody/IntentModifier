package io.github.nobooooody.intent_modifier.data

/**
 * 规则的规范化 JSON 序列化。
 *
 * 稳定性约定：
 * - 对象 key 按字母序输出；
 * - 数组保持原有顺序；
 * - 可选字段仍沿用“未设置即省略”的旧语义；
 * - 字符串使用本文件的纯 Kotlin 转义实现，不依赖 org.json/Android Context。
 *
 * 该输出同时用于持久化、RuleHash 与启动一致性比较，保证同一规则集跨进程、
 * 跨 app 版本得到相同的 SHA-256 输入。
 */
object RuleJson {
    fun javaRulesJson(rules: List<JavaCodeRule>): String {
        return rules.joinToString(",", "[", "]") { javaRuleJson(it) }
    }

    fun normalRulesJson(rules: List<NormalRule>): String {
        return rules.joinToString(",", "[", "]") { normalRuleJson(it) }
    }

    private fun javaRuleJson(rule: JavaCodeRule): String {
        return buildString {
            append('{')
            append("\"action\":").append(quote(rule.action)).append(',')
            append("\"condition\":").append(quote(rule.condition)).append(',')
            append("\"enabled\":").append(rule.enabled).append(',')
            append("\"id\":").append(quote(rule.id)).append(',')
            append("\"imports\":").append(quote(rule.imports)).append(',')
            append("\"members\":").append(quote(rule.members)).append(',')
            append("\"name\":").append(quote(rule.name)).append(',')
            append("\"priority\":").append(rule.priority).append(',')
            append("\"targetPackages\":").append(stringArray(rule.targetPackages))
            append('}')
        }
    }

    private fun normalRuleJson(rule: NormalRule): String {
        val fields = mutableListOf<Pair<String, String>>()
        fields += "blockSubsequent" to rule.blockSubsequent.toString()
        rule.customAction?.let { fields += "customAction" to quote(it) }
        if (rule.customCategories.isNotEmpty()) {
            fields += "customCategories" to stringArray(rule.customCategories)
        }
        rule.customClass?.let { fields += "customClass" to quote(it) }
        rule.customData?.let { fields += "customData" to quote(it) }
        rule.customFlags?.let { fields += "customFlags" to it.toString() }
        rule.customPackage?.let { fields += "customPackage" to quote(it) }
        rule.customType?.let { fields += "customType" to quote(it) }
        fields += "enabled" to rule.enabled.toString()
        if (rule.extras.isNotEmpty()) {
            fields += "extras" to extrasArray(rule.extras)
        }
        fields += "id" to quote(rule.id)
        rule.matchAction?.let { fields += "matchAction" to quote(it) }
        if (rule.matchCategories.isNotEmpty()) {
            fields += "matchCategories" to stringArray(rule.matchCategories)
        }
        rule.matchClass?.let { fields += "matchClass" to quote(it) }
        rule.matchData?.let { fields += "matchData" to quote(it) }
        rule.matchPackage?.let { fields += "matchPackage" to quote(it) }
        rule.matchType?.let { fields += "matchType" to quote(it) }
        fields += "name" to quote(rule.name)
        fields += "priority" to rule.priority.toString()
        if (rule.replaceCategories) fields += "replaceCategories" to "true"
        if (rule.replaceExtras) fields += "replaceExtras" to "true"
        fields += "targetPackages" to stringArray(rule.targetPackages)

        fields.sortBy { it.first }
        return fields.joinToString(",", "{", "}") { (key, value) ->
            "${quote(key)}:$value"
        }
    }

    private fun extrasArray(extras: List<ExtraItem>): String {
        return extras.joinToString(",", "[", "]") { extra ->
            val fields = mutableListOf<Pair<String, String>>()
            fields += "key" to quote(extra.key)
            fields += "type" to quote(extra.type)
            when {
                extra.values.size == 1 -> fields += "value" to quote(extra.values[0])
                extra.values.isNotEmpty() -> fields += "values" to stringArray(extra.values)
            }
            fields.sortBy { it.first }
            fields.joinToString(",", "{", "}") { (key, value) ->
                "${quote(key)}:$value"
            }
        }
    }

    private fun stringArray(values: List<String>): String {
        return values.joinToString(",", "[", "]") { quote(it) }
    }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> {
                    if (ch < ' ') {
                        builder.append("\\u")
                        builder.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(ch)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
