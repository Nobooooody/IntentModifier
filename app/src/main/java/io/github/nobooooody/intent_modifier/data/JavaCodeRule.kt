package io.github.nobooooody.intent_modifier.data

import java.util.UUID

data class JavaCodeRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String = "",
    val targetPackages: List<String> = emptyList(),
    val imports: String = "",
    val members: String = "",
    val condition: String = "",
    val action: String = "",
    val priority: Int = 0
)

data class JavaCodeRuleSet(
    val rules: List<JavaCodeRule> = emptyList()
)

data class ExtraItem(
    val key: String,
    val type: String,
    val values: List<String> = emptyList()
)

data class NormalRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String = "",
    val targetPackages: List<String> = emptyList(),
    val blockSubsequent: Boolean = true,
    val priority: Int = 0,

    val matchAction: String? = null,
    val matchData: String? = null,
    val matchPackage: String? = null,
    val matchClass: String? = null,
    val matchCategories: List<String> = emptyList(),
    val matchType: String? = null,

    val customAction: String? = null,
    val customData: String? = null,
    val customPackage: String? = null,
    val customClass: String? = null,
    val customFlags: Int? = null,
    val customCategories: List<String> = emptyList(),
    val customType: String? = null,
    val extras: List<ExtraItem> = emptyList()
)

data class NormalRuleSet(
    val rules: List<NormalRule> = emptyList()
)
