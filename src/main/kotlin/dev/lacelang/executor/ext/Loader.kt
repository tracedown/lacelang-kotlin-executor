package dev.lacelang.executor.ext

import org.tomlj.Toml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Loader for `.laceext` TOML files (lace-extensions.md §2).
 */

data class HookRegistration(val hook: String, val after: List<String>, val before: List<String>)

data class RuleDef(
    val name: String,
    val hooks: List<HookRegistration>,
    val body: List<Map<String, Any?>>,
    val declarationIndex: Int = 0,
)

data class FunctionDef(
    val name: String,
    val params: List<String>,
    val body: List<Map<String, Any?>>,
    val exposed: Boolean = false,
)

data class OneOfType(val name: String, val variants: List<Map<String, Any?>>)

data class Extension(
    val name: String,
    val version: String,
    val path: String? = null,
    val requires: List<String> = emptyList(),
    val schema: Map<String, Any?> = emptyMap(),
    val result: Map<String, Any?> = emptyMap(),
    val functions: MutableMap<String, FunctionDef> = mutableMapOf(),
    val oneOfTypes: MutableMap<String, OneOfType> = mutableMapOf(),
    val rules: MutableList<RuleDef> = mutableListOf(),
    val configDefaults: Map<String, Any?> = emptyMap(),
) {
    fun tagConstructors(): Map<String, (List<Any?>) -> Any?> {
        val out = mutableMapOf<String, (List<Any?>) -> Any?>()
        for (t in oneOfTypes.values) {
            for (variant in t.variants) {
                val tag = variant["tag"] as String
                @Suppress("UNCHECKED_CAST")
                val fields = (variant["fields"] as? Map<String, Any?>) ?: emptyMap()
                val fieldNames = fields.keys.toList()
                out[tag] = makeTagCtor(tag, fieldNames)
            }
        }
        return out
    }

    fun functionSpecs(): Map<String, Map<String, Any?>> =
        functions.mapValues { (_, f) -> mapOf("params" to f.params, "body" to f.body, "exposed" to f.exposed) }
}

private fun makeTagCtor(tag: String, fieldNames: List<String>): (List<Any?>) -> Any? = { args ->
    val out = linkedMapOf<String, Any?>("tag" to tag)
    for ((i, fname) in fieldNames.withIndex()) out[fname] = args.getOrNull(i)
    out
}

val HOOK_NAMES = setOf(
    "before script", "script",
    "before call", "call",
    "before expect", "expect",
    "before check", "check",
    "before assert", "assert",
    "before store", "store",
)

fun loadExtension(path: String): Extension {
    val text = Path.of(path).readText()
    val doc = Toml.parse(text)

    val knownTopLevel = setOf("extension", "schema", "result", "types", "functions", "rules")
    for (key in doc.keySet()) {
        if (key !in knownTopLevel) {
            System.err.println("warning: $path: unknown top-level section [$key] (known: ${knownTopLevel.sorted().joinToString(", ")})")
        }
    }

    val meta = doc.getTable("extension")
    val name = meta?.getString("name") ?: throw ExtensionLoadError("$path: [extension].name is required")
    val version = meta.getString("version") ?: "0.0.0"

    if (!Regex("[a-z][A-Za-z0-9]*").matches(name)) {
        throw ExtensionLoadError("$path: [extension].name must match [a-z][A-Za-z0-9]* (camelCase), got '$name'")
    }

    val requires = meta.getArray("require")?.let { arr ->
        (0 until arr.size()).map { arr.getString(it) }
    } ?: emptyList()

    val ext = Extension(
        name = name, version = version, path = path,
        requires = requires,
        configDefaults = loadConfigDefaults(path, name, version),
    )

    // Schema
    if (doc.contains("schema")) {
        // Opaque — passed through to validator
    }

    // Types — harvest one_of entries
    val typesTable = doc.getTable("types")
    if (typesTable != null) {
        for (tname in typesTable.keySet()) {
            val tdef = typesTable.getTable(tname) ?: continue
            val oneOfArr = tdef.getArray("one_of") ?: continue
            val variants = mutableListOf<Map<String, Any?>>()
            for (i in 0 until oneOfArr.size()) {
                val v = oneOfArr.getTable(i) ?: continue
                val tag = v.getString("tag") ?: continue
                val fieldsTable = v.getTable("fields")
                val fields = linkedMapOf<String, Any?>()
                if (fieldsTable != null) {
                    for (fk in fieldsTable.keySet()) fields[fk] = tomlToNative(fieldsTable, fk)
                }
                variants.add(mapOf("tag" to tag, "fields" to fields))
            }
            ext.oneOfTypes[tname] = OneOfType(name = tname, variants = variants)
        }
    }

    // Functions
    val funcsTable = doc.getTable("functions")
    if (funcsTable != null) {
        for (fname in funcsTable.keySet()) {
            val fdef = funcsTable.getTable(fname) ?: continue
            val params = fdef.getArray("params")?.let { arr ->
                (0 until arr.size()).map { arr.getString(it) }
            } ?: emptyList()
            val bodyText = fdef.getString("body") ?: ""
            val exposed = fdef.getBoolean("exposed") ?: false
            val bodyAst = try { parseFunctionBody(bodyText) } catch (e: Exception) {
                throw ExtensionLoadError("$path: error parsing function '$fname': ${e.message}")
            }
            // Safety checks
            if (bodyContainsKind(bodyAst, "exit")) {
                throw ExtensionLoadError("$path: function '$fname' contains an 'exit' statement; exit is only valid in rule bodies")
            }
            if (!exposed && bodyContainsKind(bodyAst, "emit")) {
                throw ExtensionLoadError("$path: function '$fname' contains an 'emit' statement but is not exposed")
            }
            ext.functions[fname] = FunctionDef(name = fname, params = params, body = bodyAst, exposed = exposed)
        }
    }

    // Recursion check within this extension
    checkNoRecursion(path, ext.functions)

    // Rules
    val rulesTable = doc.getTable("rules")
    val ruleArray = rulesTable?.getArray("rule")
    if (ruleArray != null) {
        for (declIdx in 0 until ruleArray.size()) {
            val rdef = ruleArray.getTable(declIdx) ?: continue
            val rname = rdef.getString("name") ?: "<unnamed>"
            val rawHooks = rdef.getArray("on")?.let { arr ->
                (0 until arr.size()).map { arr.getString(it) }
            } ?: (rdef.getString("on")?.let { listOf(it) } ?: emptyList())
            val parsedHooks = rawHooks.map { entry ->
                try { parseOnEntry(entry) } catch (e: Exception) {
                    throw ExtensionLoadError("$path: rule '$rname': ${e.message}")
                }
            }
            val bodyText = rdef.getString("body") ?: ""
            val bodyAst = try { parseRuleBody(bodyText) } catch (e: Exception) {
                throw ExtensionLoadError("$path: error parsing rule '$rname': ${e.message}")
            }
            ext.rules.add(RuleDef(name = rname, hooks = parsedHooks, body = bodyAst, declarationIndex = declIdx))
        }
    }

    return ext
}

private fun loadConfigDefaults(laceextPath: String, extName: String, extVersion: String): Map<String, Any?> {
    val p = Path.of(laceextPath)
    val configPath = p.parent.resolve("$extName.config")
    if (!configPath.exists()) return emptyMap()
    val doc = try { Toml.parse(configPath.readText()) } catch (e: Exception) {
        throw ExtensionLoadError("$configPath: failed to parse extension config: ${e.message}")
    }
    val cfgMeta = doc.getTable("extension")
    val cfgName = cfgMeta?.getString("name")
    val cfgVersion = cfgMeta?.getString("version")
    if (cfgName != null && cfgName != extName)
        System.err.println("warning: $configPath: config name '$cfgName' does not match extension name '$extName'")
    if (cfgVersion != null && cfgVersion != extVersion)
        System.err.println("warning: $configPath: config version '$cfgVersion' does not match extension version '$extVersion'")
    val configTable = doc.getTable("config") ?: return emptyMap()
    return tomlTableToMap(configTable)
}

private fun tomlTableToMap(table: org.tomlj.TomlTable): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    for (key in table.keySet()) out[key] = tomlToNative(table, key)
    return out
}

private fun tomlToNative(table: org.tomlj.TomlTable, key: String): Any? {
    if (table.isTable(key)) return tomlTableToMap(table.getTable(key)!!)
    if (table.isArray(key)) {
        val arr = table.getArray(key)!!
        return (0 until arr.size()).map { i ->
            if (arr.containsTables()) tomlTableToMap(arr.getTable(i))
            else tomlArrayItemToNative(arr, i)
        }
    }
    if (table.isBoolean(key)) return table.getBoolean(key)
    if (table.isLong(key)) return table.getLong(key)!!.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    if (table.isDouble(key)) return table.getDouble(key)
    if (table.isString(key)) return table.getString(key)
    return table.get(key)?.toString()
}

private fun tomlArrayItemToNative(arr: org.tomlj.TomlArray, i: Int): Any? {
    return try { arr.getString(i) } catch (_: Exception) {
        try { arr.getLong(i).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } } catch (_: Exception) {
            try { arr.getDouble(i) } catch (_: Exception) {
                try { arr.getBoolean(i) } catch (_: Exception) { null }
            }
        }
    }
}

internal fun parseOnEntry(entry: String): HookRegistration {
    val tokens = entry.trim().split("\\s+".toRegex())
    if (tokens.isEmpty()) throw ExtensionLoadError("empty on-entry")
    var hook: String? = null
    var consumed = 0
    if (tokens.size >= 2 && "${tokens[0]} ${tokens[1]}" in HOOK_NAMES) {
        hook = "${tokens[0]} ${tokens[1]}"; consumed = 2
    } else if (tokens[0] in HOOK_NAMES) {
        hook = tokens[0]; consumed = 1
    }
    if (hook == null) throw ExtensionLoadError("unknown hook in on-entry '$entry'")
    val after = mutableListOf<String>()
    val before = mutableListOf<String>()
    var i = consumed
    while (i < tokens.size) {
        val kw = tokens[i]
        if (kw != "after" && kw != "before") throw ExtensionLoadError("expected 'after' or 'before' in on-entry '$entry', got '$kw'")
        if (i + 1 >= tokens.size) throw ExtensionLoadError("dangling qualifier in on-entry '$entry'")
        val extName = tokens[i + 1]
        if (kw == "after") after.add(extName) else before.add(extName)
        i += 2
    }
    return HookRegistration(hook = hook, after = after, before = before)
}

// ── safety checks ─────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun bodyContainsKind(body: List<Map<String, Any?>>, kind: String): Boolean {
    for (node in walkNodes(body)) if (node is Map<*, *> && node["kind"] == kind) return true
    return false
}

private fun walkNodes(node: Any?): Sequence<Any?> = sequence {
    when (node) {
        is Map<*, *> -> { yield(node); for (v in node.values) yieldAll(walkNodes(v)) }
        is List<*> -> { for (item in node) yieldAll(walkNodes(item)) }
    }
}

private fun checkNoRecursion(path: String, functions: Map<String, FunctionDef>) {
    val graph = mutableMapOf<String, MutableSet<String>>()
    for ((fname, fdef) in functions) {
        val targets = mutableSetOf<String>()
        for (n in walkNodes(fdef.body)) {
            if (n is Map<*, *> && n["kind"] == "call") {
                val target = n["name"] as? String
                if (target != null && target in functions) targets.add(target)
            }
        }
        graph[fname] = targets
    }
    val WHITE = 0; val GREY = 1; val BLACK = 2
    val color = functions.keys.associateWith { WHITE }.toMutableMap()
    val stack = mutableListOf<String>()

    fun dfs(u: String) {
        color[u] = GREY; stack.add(u)
        for (v in graph[u] ?: emptySet()) {
            if (color[v] == GREY) {
                val idx = stack.indexOf(v)
                val cycle = stack.subList(idx, stack.size) + listOf(v)
                throw ExtensionLoadError("$path: function recursion detected: ${cycle.joinToString(" -> ")} (recursion is forbidden per spec §6)")
            }
            if (color[v] == WHITE) dfs(v)
        }
        stack.removeAt(stack.lastIndex); color[u] = BLACK
    }
    for (fname in graph.keys) if (color[fname] == WHITE) dfs(fname)
}

class ExtensionLoadError(msg: String) : RuntimeException(msg)
