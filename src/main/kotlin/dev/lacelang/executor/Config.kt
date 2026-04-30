package dev.lacelang.executor

import org.tomlj.Toml
import java.io.File

/**
 * lace.config TOML loader — spec §11.
 *
 * Resolution order (first match wins unless explicitPath is provided):
 *   1. explicitPath (from --config), if set.
 *   2. lace.config in the script's directory.
 *   3. lace.config in the current working directory.
 *   4. Defaults-only (no file).
 */

private const val DEFAULT_MAX_REDIRECTS = 10
private const val DEFAULT_MAX_TIMEOUT_MS = 300_000
private const val DEFAULT_RESULT_PATH = "."

class ConfigError(msg: String) : RuntimeException(msg)

fun loadConfig(
    scriptPath: String? = null,
    explicitPath: String? = null,
    envSelector: String? = null,
): Map<String, Any?> {
    val foundPath = resolvePath(scriptPath, explicitPath)
    val raw = readToml(foundPath)
    val envName = envSelector ?: System.getenv("LACE_ENV")
    val merged = mergeWithEnv(raw, envName)
    @Suppress("UNCHECKED_CAST")
    val resolved = resolveEnvRefs(merged) as Map<String, Any?>
    val cfg = applyDefaults(resolved).toMutableMap()
    cfg["_meta"] = mapOf("source_path" to foundPath)
    return cfg
}

private fun resolvePath(scriptPath: String?, explicitPath: String?): String? {
    if (explicitPath != null) {
        if (!File(explicitPath).isFile) throw ConfigError("config file not found: $explicitPath")
        return explicitPath
    }
    val candidates = mutableListOf<String>()
    if (scriptPath != null) {
        val scriptDir = File(scriptPath).absoluteFile.parentFile
        candidates.add(File(scriptDir, "lace.config").path)
    }
    candidates.add(File(System.getProperty("user.dir"), "lace.config").path)
    for (c in candidates) if (File(c).isFile) return c
    return null
}

private fun readToml(path: String?): Map<String, Any?> {
    if (path == null) return emptyMap()
    return try {
        val doc = Toml.parse(File(path).readText())
        tomlTableToMap(doc)
    } catch (e: Exception) {
        throw ConfigError("failed to read/parse config $path: ${e.message}")
    }
}

@Suppress("UNCHECKED_CAST")
private fun tomlTableToMap(table: org.tomlj.TomlTable): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    for (key in table.keySet()) {
        out[key] = when {
            table.isTable(key) -> tomlTableToMap(table.getTable(key)!!)
            table.isArray(key) -> {
                val arr = table.getArray(key)!!
                (0 until arr.size()).map { i ->
                    if (arr.containsTables()) tomlTableToMap(arr.getTable(i))
                    else tomlArrayItemToNative(arr, i)
                }
            }
            table.isBoolean(key) -> table.getBoolean(key)
            table.isLong(key) -> table.getLong(key)!!.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            table.isDouble(key) -> table.getDouble(key)
            table.isString(key) -> table.getString(key)
            else -> table.get(key)?.toString()
        }
    }
    return out
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

// ── Section merging ───────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun mergeWithEnv(raw: Map<String, Any?>, envName: String?): Map<String, Any?> {
    val base = raw.filterKeys { it != "lace" }.mapValues { deepCopy(it.value) }.toMutableMap()
    if (envName == null) return base
    val lace = raw["lace"] as? Map<String, Any?> ?: return base
    val config = lace["config"] as? Map<String, Any?> ?: return base
    val envSection = config[envName] as? Map<String, Any?> ?: return base
    return deepMerge(base, envSection)
}

private fun deepCopy(v: Any?): Any? = when (v) {
    is Map<*, *> -> v.mapValues { deepCopy(it.value) }.toMutableMap()
    is List<*> -> v.map { deepCopy(it) }.toMutableList()
    else -> v
}

@Suppress("UNCHECKED_CAST")
private fun deepMerge(base: Map<String, Any?>, overlay: Map<String, Any?>): MutableMap<String, Any?> {
    val out = deepCopy(base) as MutableMap<String, Any?>
    for ((k, v) in overlay) {
        val existing = out[k]
        if (v is Map<*, *> && existing is Map<*, *>) {
            out[k] = deepMerge(existing as Map<String, Any?>, v as Map<String, Any?>)
        } else {
            out[k] = deepCopy(v)
        }
    }
    return out
}

// ── env: substitution ─────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun resolveEnvRefs(node: Any?): Any? = when (node) {
    is Map<*, *> -> (node as Map<String, Any?>).mapValues { resolveEnvRefs(it.value) }
    is List<*> -> node.map { resolveEnvRefs(it) }
    is String -> resolveEnvString(node)
    else -> node
}

private fun resolveEnvString(s: String): String {
    if (!s.startsWith("env:")) return s
    val body = s.substring(4)
    if (":" in body) {
        val (varName, default) = body.split(":", limit = 2)
        return System.getenv(varName) ?: default
    }
    return System.getenv(body)
        ?: throw ConfigError("config references env var '$body' but it is not set (use 'env:$body:default' to supply a fallback)")
}

// ── Defaults ──────────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun applyDefaults(cfg: Map<String, Any?>): Map<String, Any?> {
    val executor = (cfg["executor"] as? Map<String, Any?>) ?: emptyMap()

    val extensionsList = (executor["extensions"] as? List<*>)?.map { it.toString() } ?: emptyList()
    val maxRedirects = (executor["maxRedirects"] as? Number)?.toInt() ?: DEFAULT_MAX_REDIRECTS
    val maxTimeoutMs = (executor["maxTimeoutMs"] as? Number)?.toInt() ?: DEFAULT_MAX_TIMEOUT_MS
    val userAgent = executor["user_agent"] as? String

    val result = (cfg["result"] as? Map<String, Any?>) ?: emptyMap()
    var resultPath: Any? = result["path"] ?: DEFAULT_RESULT_PATH
    val envResultPath = System.getenv("LACE_RESULT_PATH")
    if (envResultPath != null) resultPath = envResultPath
    if (resultPath is String && resultPath.lowercase() == "false") resultPath = false

    val bodies = (result["bodies"] as? Map<String, Any?>) ?: emptyMap()
    var bodiesDir: Any? = bodies["dir"] ?: false
    if (bodiesDir is String && bodiesDir.lowercase() == "false") bodiesDir = false
    val envBodiesDir = System.getenv("LACE_BODIES_DIR")
    if (envBodiesDir != null) bodiesDir = envBodiesDir

    val extBlock = (cfg["extensions"] as? Map<String, Any?>) ?: emptyMap()

    return mapOf(
        "executor" to mapOf(
            "extensions" to extensionsList,
            "maxRedirects" to maxRedirects,
            "maxTimeoutMs" to maxTimeoutMs,
            "user_agent" to userAgent,
        ),
        "result" to mapOf(
            "path" to resultPath,
            "bodies" to mapOf("dir" to bodiesDir),
        ),
        "extensions" to extBlock,
    )
}
