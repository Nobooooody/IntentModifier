package io.github.nobooooody.intent_modifier.data

data class JavaCodeRule(
    val enabled: Boolean = true,
    val name: String = "",
    val condition: String = "",
    val action: String = "",
    val priority: Int = 0
)

data class JavaCodeRuleSet(
    val rules: List<JavaCodeRule> = emptyList()
)