package com.llamaport.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock
    data object Rule : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val orderedItemRegex = Regex("^\\d+[.)]\\s+(.*)$")
private val unorderedItemRegex = Regex("^[-*+]\\s+(.*)$")
private val ruleRegex = Regex("^([-*_])\\s*\\1\\s*\\1[-\\s*_]*$")

/**
 * A deliberately small Markdown renderer. It handles the constructs produced by
 * local models (fenced code, headings, lists, quotes, bold/italic, inline code
 * and links) and tolerates partially streamed, unbalanced input.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val codeBackground = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            when (block) {
                is MdBlock.Paragraph -> Text(
                    inlineMarkdown(block.text, codeBackground, linkColor),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, codeBackground, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 8.dp)) {
                        Text(
                            "▏",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            inlineMarkdown(block.text, codeBackground, linkColor),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is MdBlock.ListBlock -> Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    block.items.forEachIndexed { itemIndex, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                if (block.ordered) "${itemIndex + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(if (block.ordered) 28.dp else 18.dp),
                            )
                            Text(
                                inlineMarkdown(item, codeBackground, linkColor),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                MdBlock.Rule -> HorizontalDivider()
            }
        }
        if (streaming) {
            Text(
                "▍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            if (block.language.isNotBlank()) {
                Text(
                    block.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }
            SelectionContainer {
                Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 2.dp),
                )
            }
        }
    }
}

private fun parseMarkdown(source: String): List<MdBlock> {
    if (source.isEmpty()) return emptyList()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = ArrayList<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim()
            val code = StringBuilder()
            var closed = false
            i++
            while (i < lines.size) {
                if (lines[i].trimStart().startsWith("```")) {
                    closed = true
                    i++
                    break
                }
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            blocks += MdBlock.Code(language, code.toString(), closed)
            continue
        }

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        val heading = headingRegex.find(trimmed)
        if (heading != null) {
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            i++
            continue
        }

        if (ruleRegex.matches(trimmed)) {
            blocks += MdBlock.Rule
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            val quote = StringBuilder()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                if (quote.isNotEmpty()) quote.append('\n')
                val q = lines[i].trimStart().removePrefix(">")
                quote.append(q.removePrefix(" "))
                i++
            }
            blocks += MdBlock.Quote(quote.toString())
            continue
        }

        val ordered = orderedItemRegex.find(trimmed)
        val unordered = unorderedItemRegex.find(trimmed)
        if (ordered != null || unordered != null) {
            val isOrdered = ordered != null
            val items = ArrayList<String>()
            while (i < lines.size) {
                val l = lines[i]
                val t = l.trimStart()
                val o = orderedItemRegex.find(t)
                val u = unorderedItemRegex.find(t)
                if (o != null || u != null) {
                    items += (o ?: u)!!.groupValues[1]
                    i++
                } else if (t.isEmpty()) {
                    break
                } else if (l.startsWith("  ") && items.isNotEmpty()) {
                    items[items.lastIndex] = items.last() + "\n" + t
                    i++
                } else {
                    break
                }
            }
            blocks += MdBlock.ListBlock(isOrdered, items)
            continue
        }

        val paragraph = StringBuilder()
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trimStart()
            if (t.isEmpty() || isBlockStart(t)) break
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(l)
            i++
        }
        blocks += MdBlock.Paragraph(paragraph.toString())
    }
    return blocks
}

private fun isBlockStart(trimmed: String): Boolean =
    trimmed.startsWith("```") ||
        headingRegex.matches(trimmed) ||
        trimmed.startsWith(">") ||
        orderedItemRegex.matches(trimmed) ||
        unorderedItemRegex.matches(trimmed) ||
        ruleRegex.matches(trimmed)

private fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(marker)
                        i += 2
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("~~")
                        i += 2
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground.copy(alpha = 0.7f),
                            ),
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('`')
                        i++
                    }
                }
                text.startsWith("[", i) -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > i + 1) text.indexOf(')', close + 2) else -1
                    if (close > i + 1 && end > close + 2) {
                        val label = text.substring(i + 1, close)
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            append(label)
                        }
                        i = end + 1
                    } else {
                        append('[')
                        i++
                    }
                }
                text[i] == '*' || text[i] == '_' -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i + 1 && !(end + 1 < text.length && text[end + 1] == marker)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(marker)
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }