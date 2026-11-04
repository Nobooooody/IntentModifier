package io.github.nobooooody.intent_modifier.ui

internal fun formatCompileFailure(
    prefix: String,
    message: String,
    ruleName: String?,
    line: Int?
): String {
    val location = buildString {
        if (!ruleName.isNullOrBlank()) append(ruleName)
        if (line != null) {
            if (isNotEmpty()) append(' ')
            append("(line ").append(line).append(')')
        }
    }
    return if (location.isEmpty()) {
        "$prefix\n$message"
    } else {
        "$prefix\n$location:\n$message"
    }
}
