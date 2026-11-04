package io.github.nobooooody.intent_modifier.data

object RuleHash {
    fun compute(javaRules: List<JavaCodeRule>, normalRules: List<NormalRule>): String {
        val compiledJava = javaRules.filter {
            it.enabled && (it.condition.isNotEmpty() || it.action.isNotEmpty())
        }
        val compiledNormal = normalRules.filter { it.enabled }
        val payload = buildString {
            append("{\"java\":")
            append(RuleJson.javaRulesJson(compiledJava))
            append(",\"normal\":")
            append(RuleJson.normalRulesJson(compiledNormal))
            append('}')
        }
        return RuleManifest.sha256Hex(payload.toByteArray(Charsets.UTF_8))
    }
}
