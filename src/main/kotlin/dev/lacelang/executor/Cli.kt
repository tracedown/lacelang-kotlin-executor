package dev.lacelang.executor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.lacelang.validator.ParseError
import dev.lacelang.validator.parse
import dev.lacelang.validator.validate
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * CLI for lacelang-executor — supports the full parse / validate / run
 * testkit contract.
 *
 * Exit codes:
 *   0 on processed request (errors are in the JSON body)
 *   2 on tool/arg errors
 */

private val cliGson: Gson = GsonBuilder().serializeNulls().create()
private val prettyGson: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

fun main(args: Array<String>) {
    val code = mainImpl(args.toList())
    System.exit(code)
}

@Suppress("UNCHECKED_CAST")
fun mainImpl(args: List<String>): Int {
    if (args.isEmpty()) { System.err.println("usage: lacelang-executor <parse|validate|run> [options]"); return 2 }

    val pretty = "--pretty" in args
    val cleanArgs = args.filter { it != "--pretty" }
    val command = cleanArgs[0]
    val rest = cleanArgs.subList(1, cleanArgs.size)

    return when (command) {
        "parse" -> cmdParse(rest, pretty)
        "validate" -> cmdValidate(rest, pretty)
        "run" -> cmdRun(rest, pretty)
        "--version" -> { println("lacelang-executor $VERSION"); 0 }
        else -> { System.err.println("unknown command: $command"); 2 }
    }
}

private fun cmdParse(args: List<String>, pretty: Boolean): Int {
    if (args.isEmpty()) { System.err.println("usage: lacelang-executor parse <script>"); return 2 }
    val source = try { File(args[0]).readText() } catch (e: Exception) { System.err.println("error reading script: ${e.message}"); return 2 }
    return try {
        val ast = parse(source)
        emit(mapOf("ast" to stripInternalKeys(ast)), pretty)
        0
    } catch (e: ParseError) {
        emit(mapOf("errors" to listOf(mapOf("code" to "PARSE_ERROR", "line" to e.line, "message" to e.message))), pretty)
        0
    }
}

@Suppress("UNCHECKED_CAST")
private fun cmdValidate(args: List<String>, pretty: Boolean): Int {
    if (args.isEmpty()) { System.err.println("usage: lacelang-executor validate <script> [--vars-list file] [--context file]"); return 2 }
    val source = try { File(args[0]).readText() } catch (e: Exception) { System.err.println("error reading script: ${e.message}"); return 2 }
    val ast = try { parse(source) } catch (e: ParseError) {
        emit(mapOf("errors" to listOf(mapOf("code" to "PARSE_ERROR", "line" to e.line, "message" to e.message)), "warnings" to emptyList<Any>()), pretty)
        return 0
    }
    var variables: List<String>? = null
    var context: Map<String, Any?>? = null
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--vars-list" -> { variables = readJsonList(args.getOrNull(i + 1)?.ifEmpty { null }); i += 2 }
            "--context" -> { context = readJsonMap(args.getOrNull(i + 1)?.ifEmpty { null }); i += 2 }
            else -> i++
        }
    }
    val sink = validate(ast, variables = variables, context = context)
    val errors = sink.errors.map { d -> mapOf("code" to d.code, "callIndex" to d.callIndex, "chainMethod" to d.chainMethod, "field" to d.field, "line" to d.line, "detail" to d.detail) }
    val warnings = sink.warnings.map { d -> mapOf("code" to d.code, "callIndex" to d.callIndex, "chainMethod" to d.chainMethod, "field" to d.field, "line" to d.line, "detail" to d.detail) }
    emit(mapOf("errors" to errors, "warnings" to warnings), pretty)
    return 0
}

@Suppress("UNCHECKED_CAST")
private fun cmdRun(args: List<String>, pretty: Boolean): Int {
    if (args.isEmpty()) { System.err.println("usage: lacelang-executor run <script> [--vars file] [--var K=V] [--prev file] [--config file] [--env name]"); return 2 }
    val scriptPath = args[0]
    val source = try { File(scriptPath).readText() } catch (e: Exception) { System.err.println("error reading script: ${e.message}"); return 2 }

    var varsPath: String? = null
    var prevPath: String? = null
    var configPath: String? = null
    var envSelector: String? = null
    var bodiesDir: String? = null
    var saveBody = false
    var saveTo: String? = null
    val varOverrides = mutableListOf<String>()

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--vars" -> { varsPath = args.getOrNull(i + 1)?.ifEmpty { null }; i += 2 }
            "--var" -> { args.getOrNull(i + 1)?.let { varOverrides.add(it) }; i += 2 }
            "--prev-results", "--prev" -> { prevPath = args.getOrNull(i + 1)?.ifEmpty { null }; i += 2 }
            "--config" -> { configPath = args.getOrNull(i + 1)?.ifEmpty { null }; i += 2 }
            "--env" -> { envSelector = args.getOrNull(i + 1)?.ifEmpty { null }; i += 2 }
            "--bodies-dir" -> { bodiesDir = args.getOrNull(i + 1); i += 2 }
            "--save-body" -> { saveBody = true; i++ }
            "--save-to" -> { saveTo = args.getOrNull(i + 1); i += 2 }
            "--enable-extension" -> { i += 2 } // handled below via config merge
            else -> i++
        }
    }

    // Load config
    val config = try {
        loadConfig(scriptPath = scriptPath, explicitPath = configPath, envSelector = envSelector)
    } catch (e: ConfigError) {
        val now = nowIsoStr()
        emit(mapOf("outcome" to "failure", "error" to "config error: ${e.message}",
            "startedAt" to now, "endedAt" to now, "elapsedMs" to 0,
            "runVars" to emptyMap<String, Any?>(), "calls" to emptyList<Any>(), "actions" to emptyMap<String, Any?>()), pretty)
        return 0
    }

    // Parse script vars
    val scriptVars = mutableMapOf<String, Any?>()
    if (varsPath != null) {
        val m = readJsonMap(varsPath)
        if (m != null) scriptVars.putAll(m)
    }
    for (raw in varOverrides) {
        val eq = raw.indexOf('=')
        if (eq < 0) { System.err.println("--var expects KEY=VALUE, got '$raw'"); return 2 }
        val key = raw.substring(0, eq)
        val value = raw.substring(eq + 1)
        scriptVars[key] = try { cliGson.fromJson(value, Any::class.java) } catch (_: Exception) { value }
    }

    val prev: Map<String, Any?>? = if (prevPath != null) readJsonMap(prevPath) else null

    // Parse AST
    val ast = try { parse(source) } catch (e: ParseError) {
        emit(mapOf("outcome" to "failure", "error" to "parse error on line ${e.line}: ${e.message}"), pretty)
        return 0
    }

    // Merge extensions
    val cliExts = mutableListOf<String>()
    var j = 1
    while (j < args.size) {
        if (args[j] == "--enable-extension") { args.getOrNull(j + 1)?.let { cliExts.add(it) }; j += 2 } else j++
    }
    val cfgExts = (config["executor"] as? Map<String, Any?>)?.get("extensions") as? List<*> ?: emptyList<String>()
    val mergedExts = mutableListOf<String>()
    for (name in cliExts + cfgExts.map { it.toString() }) if (name !in mergedExts) mergedExts.add(name)

    // Validate
    val ctx = mapOf(
        "maxRedirects" to ((config["executor"] as? Map<String, Any?>)?.get("maxRedirects") ?: 10),
        "maxTimeoutMs" to ((config["executor"] as? Map<String, Any?>)?.get("maxTimeoutMs") ?: 300_000),
    )
    val sink = validate(ast, variables = null, context = ctx, prevResultsAvailable = prev != null, activeExtensions = mergedExts.ifEmpty { null })
    if (sink.errors.isNotEmpty()) {
        val now = nowIsoStr()
        val codes = sink.errors.joinToString(",") { it.code }
        emit(mapOf("outcome" to "failure", "error" to "validation failed: $codes",
            "startedAt" to now, "endedAt" to now, "elapsedMs" to 0,
            "runVars" to emptyMap<String, Any?>(), "calls" to emptyList<Any>(), "actions" to emptyMap<String, Any?>()), pretty)
        return 0
    }

    // Save body flag: set bodies.dir to result path when --save-body is used without --bodies-dir
    if (saveBody && bodiesDir == null) {
        val resultPath = ((config["result"] as? Map<String, Any?>)?.get("path")) as? String
        if (resultPath != null) {
            bodiesDir = resultPath
        }
    }

    val result = runScript(
        ast = ast,
        scriptVars = scriptVars,
        prev = prev,
        bodiesDir = bodiesDir,
        activeExtensions = mergedExts.ifEmpty { null },
        config = config,
        userAgent = (config["executor"] as? Map<String, Any?>)?.get("user_agent") as? String,
    )

    // Validation warnings
    if (sink.warnings.isNotEmpty()) {
        val mutableResult = result.toMutableMap()
        mutableResult["validationWarnings"] = sink.warnings.map { d ->
            mapOf("code" to d.code, "callIndex" to d.callIndex, "chainMethod" to d.chainMethod, "field" to d.field, "line" to d.line, "detail" to d.detail)
        }
        emit(mutableResult, pretty)
    } else {
        emit(result, pretty)
    }

    return 0
}

// ── helpers ────────────────────────────────────────────────────

private val CLI_ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
private fun nowIsoStr(): String = CLI_ISO_MS.format(Instant.now())

private fun emit(obj: Any, pretty: Boolean) {
    val g = if (pretty) prettyGson else cliGson
    println(g.toJson(obj))
}

@Suppress("UNCHECKED_CAST")
private fun readJsonMap(path: String?): Map<String, Any?>? {
    if (path == null) return null
    return try {
        val text = File(path).readText()
        cliGson.fromJson(text, Map::class.java) as? Map<String, Any?>
    } catch (e: Exception) {
        System.err.println("error reading JSON from $path: ${e.message}")
        null
    }
}

@Suppress("UNCHECKED_CAST")
private fun stripInternalKeys(node: Any?): Any? = when (node) {
    is Map<*, *> -> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in node) {
            val key = k.toString()
            if (key.startsWith("__")) continue
            out[key] = stripInternalKeys(v)
        }
        out
    }
    is List<*> -> node.map { stripInternalKeys(it) }
    else -> node
}

@Suppress("UNCHECKED_CAST")
private fun readJsonList(path: String?): List<String>? {
    if (path == null) return null
    return try {
        val text = File(path).readText()
        (cliGson.fromJson(text, List::class.java) as? List<*>)?.map { it.toString() }
    } catch (e: Exception) {
        System.err.println("error reading JSON from $path: ${e.message}")
        null
    }
}
