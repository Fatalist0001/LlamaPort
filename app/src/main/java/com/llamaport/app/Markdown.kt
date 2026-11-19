package com.llamaport.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.components.Hairline
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.pressable
import com.llamaport.app.ui.theme.CodeShape
import com.llamaport.app.ui.theme.EyebrowStyle
import com.llamaport.app.ui.theme.MonoBody
import com.llamaport.app.ui.theme.MonoSmall
import com.llamaport.app.ui.theme.ObsidianPalette
import com.llamaport.app.ui.theme.ObsidianTheme
import com.llamaport.app.ui.theme.PillShape
import kotlinx.coroutines.delay

@Immutable
private sealed interface MdBlock {
    @Immutable
    data class Paragraph(val text: String) : MdBlock

    @Immutable
    data class Heading(val level: Int, val text: String) : MdBlock

    @Immutable
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock

    @Immutable
    data class Quote(val text: String) : MdBlock

    @Immutable
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock

    @Immutable
    data object Rule : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val orderedItemRegex = Regex("^\\d+[.)]\\s+(.*)$")
private val unorderedItemRegex = Regex("^[-*+]\\s+(.*)$")
private val ruleRegex = Regex("^([-*_])\\s*\\1\\s*\\1[-\\s*_]*$")

/**
 * A deliberately small Markdown renderer for local-model output. It handles
 * fenced code, headings, lists, quotes, bold/italic, inline code and links,
 * tolerates partially streamed input, and renders code with a muted Obsidian
 * syntax palette instead of flat monochrome.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    contentColor: Color = Color.Unspecified,
    bodyStyle: TextStyle? = null,
) {
    val palette = ObsidianTheme.palette
    val body = bodyStyle ?: MaterialTheme.typography.bodyMedium
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { block ->
            StreamBlock(streaming = streaming) {
                // Blocks are immutable values: on every new token only the
                // block that actually changed recomposes, the rest skip.
                MdBlockView(
                    block = block,
                    palette = palette,
                    bodyStyle = body,
                    contentColor = contentColor,
                )
            }
        }
        if (streaming) {
            StreamingCursorMark(body)
        }
    }
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    palette: ObsidianPalette,
    bodyStyle: TextStyle,
    contentColor: Color,
) {
    when (block) {
        is MdBlock.Paragraph -> Text(
            text = inlineMarkdown(block.text, palette),
            style = bodyStyle,
            color = contentColor,
        )

        is MdBlock.Heading -> Text(
            text = inlineMarkdown(block.text, palette),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = if (contentColor == Color.Unspecified) palette.textPrimary else contentColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 3.dp),
        )

        is MdBlock.Code -> CodeBlock(block, palette)

        is MdBlock.Quote -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                "▎",
                color = palette.silverDim.copy(alpha = 0.75f),
                style = bodyStyle,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = inlineMarkdown(block.text, palette),
                style = bodyStyle,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        is MdBlock.ListBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            block.items.forEachIndexed { itemIndex, item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    if (block.ordered) {
                        Text(
                            "${itemIndex + 1}.",
                            style = MonoSmall.copy(fontSize = 11.sp, color = palette.silverDim),
                            modifier = Modifier
                                .width(24.dp)
                                .padding(top = 3.dp),
                        )
                    } else {
                        Box(
                            Modifier
                                .width(18.dp)
                                .padding(top = 9.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(
                                Modifier
                                    .width(7.dp)
                                    .height(1.5.dp)
                                    .background(palette.silverDim.copy(alpha = 0.8f)),
                            )
                        }
                    }
                    Text(
                        text = inlineMarkdown(item, palette),
                        style = bodyStyle,
                        color = contentColor,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        MdBlock.Rule -> Hairline(Modifier.padding(vertical = 4.dp), palette.hairlineStrong)
    }
}

/**
 * When streaming, each Markdown block fades in as soon as it appears. The
 * blocks already on screen keep their state, so only new content animates.
 */
@Composable
private fun StreamBlock(streaming: Boolean, content: @Composable () -> Unit) {
    if (!streaming) {
        content()
        return
    }
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MotionTokens.Durations.ENTER,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            )
        }
    }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) {
        content()
    }
}

/** Blinking silver block cursor at the end of a streaming answer. */
@Composable
private fun StreamingCursorMark(bodyStyle: TextStyle) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current
    val alpha = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "mdCursor").animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mdCursorAlpha",
        )
    }
    Text(
        "▍",
        style = bodyStyle,
        color = palette.silver,
        // Draw-phase read: the cursor never triggers recomposition.
        modifier = Modifier.graphicsLayer { this.alpha = alpha?.value ?: 1f },
    )
}

@Composable
private fun CodeBlock(block: MdBlock.Code, palette: ObsidianPalette) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    val language = block.language.lowercase().trim()
    val highlighted = remember(block.code, language, palette) {
        highlightCode(block.code, palette)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CodeShape)
            .background(palette.codeBackground)
            .border(1.dp, palette.codeBorder, CodeShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "code" }.uppercase(),
                style = MonoSmall,
                color = palette.codeMuted,
            )
            Spacer(Modifier.weight(1f))
            if (block.code.isNotBlank()) {
                Row(
                    Modifier
                        .clip(PillShape)
                        .background(palette.codeInlineBackground.copy(alpha = 0.55f))
                        .border(1.dp, palette.codeBorder, PillShape)
                        .pressable(hapticsEnabled = true) {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (copied) AppIcons.Check else AppIcons.Copy,
                        contentDescription = null,
                        tint = if (copied) palette.codeString else palette.codeMuted,
                        modifier = Modifier.width(12.dp).height(12.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (copied) "ГОТОВО" else "КОПИРОВАТЬ",
                        style = EyebrowStyle,
                        color = if (copied) palette.codeString else palette.codeMuted,
                    )
                }
            }
        }
        Hairline(color = palette.codeBorder)
        SelectionContainer {
            Text(
                text = highlighted,
                style = MonoBody.copy(color = palette.codeText),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

private val CODE_KEYWORDS = setOf(
    "abstract", "and", "as", "async", "await", "break", "by", "case", "catch",
    "class", "companion", "const", "constructor", "continue", "data", "def",
    "default", "do", "elif", "else", "enum", "except", "external", "false",
    "final", "finally", "for", "from", "fun", "function", "get", "if", "import",
    "in", "inline", "interface", "internal", "is", "lambda", "lateinit", "let",
    "nil", "not", "null", "object", "open", "operator", "or", "override",
    "package", "pass", "private", "protected", "public", "raise", "return",
    "sealed", "set", "static", "struct", "super", "suspend", "switch", "this",
    "throw", "true", "try", "type", "typeof", "val", "var", "void", "when",
    "where", "while", "with", "yield",
)

private val codeTokenRegex = Regex(
    "(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/)" +
        "|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)" +
        "|(\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)" +
        "|(\\b(?:" + CODE_KEYWORDS.joinToString("|") + ")\\b)" +
        "|(\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\())",
    setOf(RegexOption.MULTILINE),
)

/** Small, dependency-free highlighter for the code local models emit. */
private fun highlightCode(code: String, palette: ObsidianPalette): AnnotatedString {
    // Very long tool/code payloads stay plain: colouring 100 KB inline would
    // cost layout time for no reading benefit.
    if (code.isEmpty() || code.length > 20_000) return AnnotatedString(code)
    return buildAnnotatedString {
        var last = 0
        for (match in codeTokenRegex.findAll(code)) {
            if (match.range.first > last) {
                append(code.substring(last, match.range.first))
            }
            val color = when {
                match.groups[1] != null -> palette.codeMuted
                match.groups[2] != null -> palette.codeString
                match.groups[3] != null -> palette.codeNumber
                match.groups[4] != null -> palette.codeKeyword
                match.groups[5] != null -> palette.codeFunction
                else -> palette.codeText
            }
            withStyle(SpanStyle(color = color)) {
                append(match.value)
            }
            last = match.range.last + 1
        }
        if (last < code.length) append(code.substring(last))
    }
}

private fun inlineMarkdown(text: String, palette: ObsidianPalette): AnnotatedString =
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
                                background = palette.codeInlineBackground,
                                color = palette.codeKeyword,
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
                                color = palette.silver,
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
