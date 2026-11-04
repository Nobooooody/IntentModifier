package io.github.nobooooody.intent_modifier.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class DexGroupManifest(
    val sha256: String,
    val size: Long,
    val ruleCount: Int,
    val targetPackages: List<String> = emptyList()
)

data class RuleManifest(
    val version: Long,
    val ruleCount: Int,
    val compiledAt: Long,
    val enabledRuleHash: String?,
    val groups: Map<String, DexGroupManifest>
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("ruleCount", ruleCount)
        root.put("compiledAt", compiledAt)
        enabledRuleHash?.let { root.put("enabledRuleHash", it) }
        val groupsJson = JSONObject()
        for ((group, info) in groups) {
            groupsJson.put(group, JSONObject().apply {
                put("sha256", info.sha256)
                put("size", info.size)
                put("ruleCount", info.ruleCount)
                put("targetPackages", JSONArray(info.targetPackages))
            })
        }
        root.put("groups", groupsJson)
        return root.toString()
    }

    companion object {
        const val GROUP_SHARED = "shared"

        fun fromJson(json: String?): RuleManifest? {
            if (json.isNullOrEmpty()) return null
            return try {
                val root = JSONObject(json)
                val version = root.optLong("version", 0L)
                if (version <= 0L) return null
                val groupsJson = root.optJSONObject("groups")
                val groups = mutableMapOf<String, DexGroupManifest>()
                if (groupsJson != null) {
                    groupsJson.keys().forEach { key ->
                        val group = groupsJson.optJSONObject(key) ?: return@forEach
                        val packagesJson = group.optJSONArray("targetPackages")
                        val packages = if (packagesJson != null) {
                            (0 until packagesJson.length()).mapNotNull { packagesJson.optString(it, "").ifEmpty { null } }
                        } else {
                            emptyList()
                        }
                        groups[key] = DexGroupManifest(
                            sha256 = group.optString("sha256", ""),
                            size = group.optLong("size", 0L),
                            ruleCount = group.optInt("ruleCount", 0),
                            targetPackages = packages
                        )
                    }
                }
                RuleManifest(
                    version = version,
                    ruleCount = root.optInt("ruleCount", 0),
                    compiledAt = root.optLong("compiledAt", 0L),
                    enabledRuleHash = root.optString("enabledRuleHash", "").ifEmpty { null },
                    groups = groups
                )
            } catch (t: Throwable) {
                null
            }
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val builder = StringBuilder(digest.size * 2)
            for (b in digest) {
                builder.append(String.format("%02x", b.toInt() and 0xff))
            }
            return builder.toString()
        }
    }
}
