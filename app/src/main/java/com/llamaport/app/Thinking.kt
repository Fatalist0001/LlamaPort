package com.llamaport.app

/** Splits a model answer into its optional reasoning block and the visible reply. */
data class ThinkingParts(
    val thinking: String?,
    val answer: String,
)

private val THINK_OPEN_TAGS = listOf(
    "\u003cthink\u003e",
    "\u003cthinking\u003e",
    "\u003c|thinking|>",
)

private val THINK_CLOSE_TAGS = listOf(
    "\u003c/think\u003e",
    "\u003c/thinking\u003e",
    "\u003c|/thinking|>",
)

fun hasThinkingTags(raw: String): Boolean =
    THINK_OPEN_TAGS.any { raw.contains(it) } || THINK_CLOSE_TAGS.any { raw.contains(it) }

fun splitThinking(raw: String): ThinkingParts {
    if (raw.isEmpty()) return ThinkingParts(null, "")

    var openIndex = -1
    var openTag = ""
    for (tag in THINK_OPEN_TAGS) {
        val index = raw.indexOf(tag)
        if (index >= 0 && (openIndex < 0 || index < openIndex)) {
            openIndex = index
            openTag = tag
        }
    }

    if (openIndex < 0) {
        // Some templates/checkpoints emit only the closing tag.
        for (tag in THINK_CLOSE_TAGS) {
            val index = raw.indexOf(tag)
            if (index >= 0) {
                val thinking = raw.substring(0, index).trim()
                val answer = raw.substring(index + tag.length).trim()
                return ThinkingParts(
                    thinking = thinking.takeIf { it.isNotEmpty() && it != "thinking" },
                    answer = answer,
                )
            }
        }
        return ThinkingParts(null, raw.trim())
    }

    val thinkingStart = openIndex + openTag.length
    var closeIndex = -1
    var closeTag = ""
    for (tag in THINK_CLOSE_TAGS) {
        val index = raw.indexOf(tag, thinkingStart)
        if (index >= 0 && (closeIndex < 0 || index < closeIndex)) {
            closeIndex = index
            closeTag = tag
        }
    }

    return if (closeIndex < 0) {
        val thinking = raw.substring(thinkingStart).trim()
        ThinkingParts(
            thinking = thinking.takeIf { it.isNotEmpty() && it != "thinking" },
            answer = "",
        )
    } else {
        val thinking = raw.substring(thinkingStart, closeIndex).trim()
        val answer = raw.substring(closeIndex + closeTag.length).trim()
        ThinkingParts(
            thinking = thinking.takeIf { it.isNotEmpty() && it != "thinking" },
            answer = answer,
        )
    }
}