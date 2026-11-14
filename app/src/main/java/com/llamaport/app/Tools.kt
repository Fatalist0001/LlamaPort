package com.llamaport.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// ---------------------------------------------------------------- tool model

data class ToolCall(val name: String, val arguments: String)

sealed class ParsedToolCall {
    data class Success(val call: ToolCall) : ParsedToolCall()
    data class Failure(val message: String) : ParsedToolCall()
    object None : ParsedToolCall()
}

data class ToolExecution(val text: String, val isError: Boolean = false)

data class ToolRunContext(
    val settings: Settings,
    val registry: ToolRegistry,
)

interface Tool {
    val name: String
    val summary: String
    suspend fun execute(args: JSONObject, context: ToolRunContext): String
}

object ToolCallParser {
    private val callBlockRegex = Regex(
        """<tool_call>\s*(.*?)\s*</tool_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** Removes any tool block, JSON or XML, from the text shown to the user. */
    private val anyCallBlockRegex = Regex(
        """<tool_call>.*?</tool_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val fenceRegex = Regex(
        """```(?:json)?\s*(\{.*\})\s*```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** Some chat templates (LFM, Qwen, Hermes) emit <function=name> tags instead of JSON. */
    private val functionRegex = Regex(
        """<\s*function\s*(?:name\s*=\s*|=\s*)["']?([A-Za-z0-9_.-]+)["']?\s*>(.*?)</\s*function\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val parameterRegex = Regex(
        """<\s*parameter\s*(?:name\s*=\s*|=\s*)["']?([A-Za-z0-9_.-]+)["']?\s*>(.*?)</\s*parameter\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parse(text: String): ParsedToolCall {
        val marker = "<tool_call>" in text.lowercase(Locale.ROOT)
        val block = callBlockRegex.find(text)?.groupValues?.getOrNull(1)
        val candidate = block ?: text.takeIf { functionRegex.containsMatchIn(it) }

        if (candidate != null) {
            val trimmed = candidate.trim()
            if (trimmed.startsWith("{")) {
                return decode(trimmed)
            }
            val function = functionRegex.find(candidate)
            if (function != null) {
                return decodeFunction(function.groupValues[1], function.groupValues[2])
            }
            if ("\"name\"" in trimmed || "\"tool\"" in trimmed) {
                return decode(trimmed)
            }
        }

        val fenced = fenceRegex.find(text)?.groupValues?.getOrNull(1)
        if (fenced != null && "\"name\"" in fenced) {
            return decode(fenced)
        }

        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}") &&
            "\"name\"" in trimmed && "\"arguments\"" in trimmed
        ) {
            return decode(trimmed)
        }

        return if (marker || functionRegex.containsMatchIn(text)) {
            ParsedToolCall.Failure(
                "Не удалось разобрать вызов инструмента. " +
                    "Нужен JSON: <tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"2+2\"}}</tool_call>",
            )
        } else {
            ParsedToolCall.None
        }
    }

    private fun decodeFunction(name: String, body: String): ParsedToolCall {
        if (name.isBlank()) {
            return ParsedToolCall.Failure("В вызове инструмента отсутствует имя функции.")
        }
        if (body.trim().startsWith("{")) {
            return decode(body.trim())
        }
        val args = JSONObject()
        for (parameter in parameterRegex.findAll(body)) {
            args.put(parameter.groupValues[1], parameter.groupValues[2].trim())
        }
        if (args.length() == 0) {
            return ParsedToolCall.Failure("У вызова «$name» нет параметров.")
        }
        return ParsedToolCall.Success(ToolCall(name, args.toString()))
    }

    private fun decode(json: String): ParsedToolCall {
        return try {
            val obj = JSONObject(json)
            val name = obj.optString("name").ifBlank { obj.optString("tool") }
            if (name.isBlank()) {
                return ParsedToolCall.Failure("В вызове инструмента отсутствует поле \"name\".")
            }
            val rawArgs = obj.opt("arguments") ?: obj.opt("args") ?: JSONObject()
            val args = when (rawArgs) {
                is JSONObject -> rawArgs.toString()
                is String -> rawArgs.ifBlank { "{}" }
                JSONObject.NULL, null -> "{}"
                else -> rawArgs.toString()
            }
            ParsedToolCall.Success(ToolCall(name, args))
        } catch (t: Throwable) {
            ParsedToolCall.Failure("Ошибка разбора JSON вызова инструмента: ${t.message}")
        }
    }

    fun strip(text: String): String = anyCallBlockRegex.replace(text, "").trim()
}

// ------------------------------------------------------------------ registry

class ToolRegistry(private val context: Context) {

    private val sandbox = FileSandbox(context)

    private val all: Map<String, Tool> by lazy {
        val list = listOf(
            TimeTool(),
            CalculatorTool(),
            ReadFileTool(sandbox),
            WriteFileTool(sandbox),
            ListDirTool(sandbox),
            NotesTool(sandbox),
            HttpGetTool(),
            RunJsTool(this),
        )
        list.associateBy { it.name }
    }

    fun enabledTools(settings: Settings): List<Tool> {
        if (!settings.toolsEnabled) return emptyList()
        return settings.enabledTools.mapNotNull { all[it] }
    }

    fun allToolNames(): List<String> = all.keys.toList()

    fun prompt(settings: Settings): String {
        val tools = enabledTools(settings)
        if (tools.isEmpty()) return ""
        return buildString {
            appendLine("You can use tools. To call one, output exactly one line:")
            appendLine("<tool_call>{\"name\":\"tool_name\",\"arguments\":{...}}</tool_call>")
            appendLine("Example: <tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"2+3\"}}</tool_call>")
            appendLine("Use at most one tool per reply. After the tool result, continue and answer the user. Never invent tool output.")
            appendLine("Available tools:")
            for (tool in tools) {
                appendLine("- ${tool.name}: ${tool.summary}")
            }
        }
    }

    suspend fun execute(call: ToolCall, settings: Settings, allowRunJs: Boolean = true): ToolExecution {
        if (!settings.toolsEnabled) {
            return ToolExecution("Инструменты отключены в настройках.", isError = true)
        }
        if (call.name !in settings.enabledTools) {
            return ToolExecution("Инструмент «${call.name}» отключён в настройках.", isError = true)
        }
        if (!allowRunJs && call.name == RunJsTool.NAME) {
            return ToolExecution("run_js нельзя вызывать из run_js.", isError = true)
        }
        if (call.name == "http_get" && !settings.httpToolEnabled) {
            return ToolExecution("http_get выключен в настройках.", isError = true)
        }
        val tool = all[call.name]
            ?: return ToolExecution("Неизвестный инструмент «${call.name}».", isError = true)
        val args = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (t: Throwable) {
            return ToolExecution("Аргументы инструмента должны быть JSON-объектом: ${t.message}", isError = true)
        }
        return try {
            val result = withTimeout(12_000) {
                tool.execute(args, ToolRunContext(settings, this@ToolRegistry))
            }
            ToolExecution(result)
        } catch (t: Throwable) {
            Log.w("ToolRegistry", "tool ${call.name} failed", t)
            ToolExecution(t.message ?: t.toString(), isError = true)
        }
    }
}

// -------------------------------------------------------------- file sandbox

class FileSandbox(context: Context) {
    val root: File = File(context.filesDir, "sandbox").apply { mkdirs() }
    val rootCanonical: File = root.canonicalFile

    fun resolve(relative: String, allowRoot: Boolean = false): File {
        val cleaned = relative.trim()
        require(cleaned.isNotEmpty()) { "Пустой путь." }
        val candidate = File(root, cleaned).canonicalFile
        val inside = candidate == rootCanonical ||
            candidate.path.startsWith(rootCanonical.path + File.separator)
        require(inside) { "Путь вне песочницы: $relative" }
        require(allowRoot || candidate != rootCanonical) { "Укажите файл, а не корень песочницы." }
        return candidate
    }

    fun relative(file: File): String =
        runCatching { file.canonicalFile.relativeTo(rootCanonical).path }.getOrDefault(file.name)
}

private const val MAX_FILE_BYTES = 256 * 1024

// --------------------------------------------------------------------- tools

class TimeTool : Tool {
    override val name = "get_time"
    override val summary = "{} — текущие дата и время устройства."
    override suspend fun execute(args: JSONObject, context: ToolRunContext): String {
        val now = Date()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        return "${fmt.format(now)} (epoch ${now.time / 1000})"
    }
}

class CalculatorTool : Tool {
    override val name = "calculator"
    override val summary = "{\"expression\":\"2+2*3\"} — арифметика: + - * / % ^, sqrt, sin, cos, tan, log, ln, abs, floor, ceil, round, min, max, pow."
    override suspend fun execute(args: JSONObject, context: ToolRunContext): String {
        val expression = args.optString("expression").ifBlank {
            args.optString("expr").ifBlank { args.optString("query") }
        }
        require(expression.isNotBlank()) { "Укажите expression." }
        require(expression.length <= 500) { "Слишком длинное выражение." }
        val value = Calculator.evaluate(expression)
        require(value.isFinite()) { "Результат не является конечным числом." }
        val pretty = BigDecimal(value).round(MathContext(12)).stripTrailingZeros().toPlainString()
        return "$expression = $pretty"
    }
}

class ReadFileTool(private val sandbox: FileSandbox) : Tool {
    override val name = "read_file"
    override val summary = "{\"path\":\"notes.txt\"} — прочитать файл из песочницы (до 256 КБ)."
    override suspend fun execute(args: JSONObject, context: ToolRunContext): String {
        val file = sandbox.resolve(args.optString("path"))
        require(file.exists()) { "Файл не найден: ${sandbox.relative(file)}" }
        require(file.isFile) { "Это не файл." }
        require(file.length() <= MAX_FILE_BYTES) {
            "Файл больше 256 КБ (${file.length()} байт)."
        }
        return file.readText(Charsets.UTF_8)
    }
}

class WriteFileTool(private val sandbox: FileSandbox) : Tool {
    override val name = "write_file"
    override val summary = "{\"path\":\"notes.txt\",\"content\":\"текст\"} — записать файл в песочницу (до 256 КБ)."
    override suspend fun execute(args: JSONObject, context: ToolRunContext): String {
        val path = args.optString("path")
        val content = args.optString("content")
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Содержимое больше 256 КБ." }
        var file = sandbox.resolve(path)
        require(!file.isDirectory) { "Путь указывает на папку." }
        file.parentFile?.let { parent ->
            parent.mkdirs()
            val canonicalParent = parent.canonicalFile
            require(
                canonicalParent == sandbox.rootCanonical ||
                    canonicalParent.path.startsWith(sandbox.rootCanonical.path + File.separator),
            ) { "Путь вне песочницы." }
        }
        file = sandbox.resolve(path)
        file.writeBytes(bytes)
        return "Записано ${bytes.size} байт в ${sandbox.relative(file)}"
    }
}

class ListDirTool(private val sandbox: FileSandbox) : Tool {
    override val name = "list_dir"
    override val summary = "{\"path\":\".\"} — список файлов и папок в песочнице."
    override suspend fun execute(args: JSONObject, context: ToolRunContext): String {
        val dir = sandbox.resolve(args.optString("path").ifBlank { "." }, allowRoot = true)
        require(dir.isDirectory) { "Это не папка." }
        val entries = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) },
        ).orEmpty().take(200)
        if (entries.isEmpty()) return "(пусто)"
        return buildString {
            for (entry in entries) {
                if (entry.isDirectory) {
                    append("[dir]  ").append(sandbox.relative(entry)).append('/')
                } else {
                    append("[file] ").append(sandbox.relative(entry))
                        .append(" (").append(entry.length()).append(" байт)")
                }
                append('\n')
            }
        }.trimEnd()
    }
}

class NotesTool(private val sandbox: FileSandbox) : Tool {
    override val name = "notes"
    override val summary = "{\"action\":\"add|list|clear\",\"text\":\"...\"} — заметки в песочнице."
    override suspend fun execute(args: JSONObject, context: ToolRunContext): String {
        val file = sandbox.resolve("notes.md", allowRoot = true)
        val action = args.optString("action", "add").lowercase(Locale.ROOT)
        return when (action) {
            "add", "append" -> {
                val text = args.optString("text")
                require(text.isNotBlank()) { "Укажите text." }
                val current = if (file.exists()) file.readText(Charsets.UTF_8) else ""
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                val updated = current + "- [$stamp] $text\n"
                val bytes = updated.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_FILE_BYTES) { "Заметки превысили 256 КБ." }
                file.writeBytes(bytes)
                "Добавлено в notes.md"
            }
            "list", "read" -> if (file.exists()) file.readText(Charsets.UTF_8) else "(заметок нет)"
            "clear" -> {
                file.writeText("")
                "Заметки очищены"
            }
            else -> "Неизвестное действие: $action"
        }
    }
}

class HttpGetTool : Tool {
    override val name = "http_get"
    override val summary = "{\"url\":\"https://example.com\"} — GET-запрос, таймаут 10 с, ответ до 64 КБ."

    override suspend fun execute(args: JSONObject, context: ToolRunContext): String = withContext(Dispatchers.IO) {
        val raw = args.optString("url")
        require(raw.isNotBlank()) { "Укажите url." }
        var url = validatePublicUrl(raw)
        var redirects = 0
        while (true) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LlamaPort/1.0 (Android)")
                setRequestProperty("Accept", "text/*,application/json;q=0.9,*/*;q=0.8")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location != null && redirects < 2) {
                        redirects++
                        url = validatePublicUrl(URL(url, location).toString())
                        continue
                    }
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    var truncated = false
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (total + n > 64 * 1024) {
                            out.write(buffer, 0, 64 * 1024 - total)
                            total = 64 * 1024
                            truncated = true
                            break
                        }
                        out.write(buffer, 0, n)
                        total += n
                    }
                    out.toByteArray().toString(Charsets.UTF_8) + if (truncated) "\n[truncated]" else ""
                } ?: ""
                return@withContext "HTTP $code\n$body"
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    private fun validatePublicUrl(raw: String): URL {
        val url = URL(raw)
        val scheme = url.protocol.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "Разрешены только http и https." }
        val host = url.host
        require(host.isNotBlank()) { "Некорректный URL." }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.none { isPrivateAddress(it) }) {
            "Запросы к локальным и приватным адресам запрещены."
        }
        return url
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 10) return true
            if (first == 172 && second in 16..31) return true
            if (first == 192 && second == 168) return true
            if (first == 100 && second in 64..127) return true
            if (first == 169 && second == 254) return true
            if (first == 127) return true
            if (first == 0) return true
        } else if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return true
        }
        return false
    }
}

class RunJsTool(private val registry: ToolRegistry) : Tool {
    companion object {
        const val NAME = "run_js"
        private const val MAX_RESULT_BYTES = 4 * 1024
        private const val TIMEOUT_SECONDS = 8L
    }

    override val name = NAME
    override val summary = "{\"code\":\"1+2\"} — выполнить JavaScript (QuickJS, таймаут 8 с, память 32 МБ, доступ к инструментам через tool(name, args))."

    override suspend fun execute(args: JSONObject, context: ToolRunContext): String = withContext(Dispatchers.IO) {
        val code = args.optString("code")
        require(code.isNotBlank()) { "Укажите code." }
        require(code.length <= 64 * 1024) { "Слишком длинный код." }

        val host = QuickJsHost(context)
        val handle = QuickJsBridge.create(host)
        if (handle == 0L) return@withContext "Ошибка: не удалось создать JavaScript-движок."

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "run-js").apply { isDaemon = true }
        }
        var finished = false
        try {
            val future = executor.submit<String> { QuickJsBridge.eval(handle, code) }
            var raw: String
            try {
                raw = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                finished = true
            } catch (t: TimeoutException) {
                QuickJsBridge.cancel(handle)
                future.cancel(true)
                return@withContext try {
                    val late = future.get(2, TimeUnit.SECONDS)
                    finished = true
                    "Ошибка: превышен таймаут ${TIMEOUT_SECONDS} с.\n$late"
                } catch (ignored: Throwable) {
                    "Ошибка: превышен таймаут ${TIMEOUT_SECONDS} с."
                }
            }
            val obj = JSONObject(raw)
            val ok = obj.optBoolean("ok", false)
            val text = if (ok) obj.optString("output") else obj.optString("error")
            val body = text.ifBlank { if (ok) "(нет вывода)" else "Ошибка JavaScript" }
            return@withContext if (ok) truncate(body) else "Ошибка JavaScript: ${truncate(body)}"
        } catch (t: Throwable) {
            Log.w("RunJsTool", "js failed", t)
            "Ошибка JavaScript: ${t.message}"
        } finally {
            executor.shutdownNow()
            if (finished) {
                QuickJsBridge.destroy(handle)
            }
        }
    }

    private fun truncate(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_RESULT_BYTES) return text
        var end = MAX_RESULT_BYTES
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + "\n[truncated]"
    }
}

class QuickJsHost(private val context: ToolRunContext) {
    @Suppress("unused") // called from native code
    fun callTool(name: String, args: String): String {
        return try {
            if (name == RunJsTool.NAME) {
                JSONObject().put("ok", false).put("error", "run_js нельзя вызывать из run_js.").toString()
            } else {
                val result = runBlocking {
                    context.registry.execute(ToolCall(name, args), context.settings, allowRunJs = false)
                }
                JSONObject().put("ok", !result.isError)
                    .put(if (result.isError) "error" else "result", result.text)
                    .toString()
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: t.toString()).toString()
        }
    }
}

object QuickJsBridge {
    init {
        System.loadLibrary("quickjs-jni")
    }

    external fun create(host: Any): Long
    external fun eval(handle: Long, code: String): String
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

// ---------------------------------------------------------------- calculator

object Calculator {
    fun evaluate(expression: String): Double {
        val parser = Parser(expression)
        val value = parser.parse()
        require(value.isFinite()) { "Результат не является конечным числом." }
        return value
    }

    private class Parser(private val source: String) {
        private var pos = 0
        private var depth = 0

        fun parse(): Double {
            val value = expression()
            skipSpace()
            require(pos == source.length) { "Неожиданный символ: '${source[pos]}'" }
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpace()
                when {
                    take('+') -> value += term()
                    take('-') -> value -= term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                skipSpace()
                when {
                    take('*') -> value *= unary()
                    take('/') -> {
                        val divisor = unary()
                        require(divisor != 0.0) { "Деление на ноль." }
                        value /= divisor
                    }
                    take('%') -> {
                        val divisor = unary()
                        require(divisor != 0.0) { "Деление на ноль." }
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Double {
            skipSpace()
            if (take('+')) return unary()
            if (take('-')) return -unary()
            return power()
        }

        private fun power(): Double {
            val base = atom()
            skipSpace()
            if (take('^')) {
                return base.pow(unary())
            }
            return base
        }

        private fun atom(): Double {
            if (++depth > 64) throw IllegalArgumentException("Слишком глубокая вложенность.")
            try {
                skipSpace()
                if (take('(')) {
                    val value = expression()
                    skipSpace()
                    require(take(')')) { "Ожидалась ')'" }
                    return value
                }
                if (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) {
                    return number()
                }
                val identifier = identifier()
                if (identifier.isEmpty()) {
                    throw IllegalArgumentException("Ожидалось число или функция.")
                }
                skipSpace()
                if (take('(')) {
                    val values = ArrayList<Double>()
                    skipSpace()
                    if (!take(')')) {
                        while (true) {
                            values += expression()
                            skipSpace()
                            if (take(')')) break
                            require(take(',')) { "Ожидалась ',' или ')'" }
                        }
                    }
                    return applyFunction(identifier.lowercase(Locale.ROOT), values)
                }
                return when (identifier.lowercase(Locale.ROOT)) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> throw IllegalArgumentException("Неизвестная константа: $identifier")
                }
            } finally {
                depth--
            }
        }

        private fun number(): Double {
            val start = pos
            var hasDot = false
            var hasExp = false
            while (pos < source.length) {
                val c = source[pos]
                when {
                    c.isDigit() -> pos++
                    c == '.' && !hasDot && !hasExp -> {
                        hasDot = true
                        pos++
                    }
                    (c == 'e' || c == 'E') && !hasExp -> {
                        hasExp = true
                        pos++
                        if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
                    }
                    else -> break
                }
            }
            return source.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("Некорректное число.")
        }

        private fun identifier(): String {
            val start = pos
            while (pos < source.length && (source[pos].isLetter() || source[pos] == '_')) pos++
            return source.substring(start, pos)
        }

        private fun applyFunction(name: String, args: List<Double>): Double {
            fun one() = args.singleOrNull()
                ?: throw IllegalArgumentException("$name ожидает один аргумент.")
            fun two() = if (args.size == 2) args else
                throw IllegalArgumentException("$name ожидает два аргумента.")
            return when (name) {
                "sqrt" -> sqrt(one())
                "abs" -> abs(one())
                "sin" -> sin(one())
                "cos" -> cos(one())
                "tan" -> tan(one())
                "ln" -> ln(one())
                "log" -> log10(one())
                "exp" -> exp(one())
                "floor" -> floor(one())
                "ceil" -> ceil(one())
                "round" -> round(one())
                "pow" -> two()[0].pow(two()[1])
                "min" -> min(two()[0], two()[1])
                "max" -> max(two()[0], two()[1])
                else -> throw IllegalArgumentException("Неизвестная функция: $name")
            }
        }

        private fun skipSpace() {
            while (pos < source.length && source[pos].isWhitespace()) pos++
        }

        private fun take(c: Char): Boolean {
            if (pos < source.length && source[pos] == c) {
                pos++
                return true
            }
            return false
        }
    }
}