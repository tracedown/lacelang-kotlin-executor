package dev.lacelang.executor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.lacelang.executor.ext.ExtensionRegistry
import dev.lacelang.executor.ext.Interpreter
import dev.lacelang.validator.fmt
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Spec-compliant Lace runtime executor.
 *
 * Implements lace-spec.md §3 (HTTP calls, cookies, redirects, timing),
 * §4 (chain methods), §7 (execution model), §9 (ProbeResult wire format).
 */

const val VERSION = "0.1.0"
val DEFAULT_USER_AGENT = "lace-probe/$VERSION (lacelang-kotlin)"

private const val DEFAULT_TIMEOUT_MS = 30_000
private const val DEFAULT_TIMEOUT_ACTION = "fail"
private const val DEFAULT_TIMEOUT_RETRIES = 0
private const val DEFAULT_REJECT_INVALID_CERTS = true
private const val DEFAULT_FOLLOW_REDIRECTS = true
private const val DEFAULT_MAX_REDIRECTS = 10
private const val DEFAULT_JAR_NAME = "__default__"

private val ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
private fun nowIso(): String = ISO_MS.format(Instant.now())

private val gson: Gson = GsonBuilder().serializeNulls().create()

private val INTERP_RE = Regex(
    """\$\{(\$\$[a-zA-Z_][a-zA-Z0-9_]*)""" +
    """}|\$\{(\$[a-zA-Z_][a-zA-Z0-9_]*)""" +
    """}|\$\$([a-zA-Z_][a-zA-Z0-9_]*)""" +
    """|\$([a-zA-Z_][a-zA-Z0-9_]*)"""
)

val BUILTIN_EXTENSIONS: Map<String, String> = mapOf(
    "laceNotifications" to "laceNotifications.laceext",
    "laceBaseline" to "laceBaseline.laceext",
    "notifCounter" to "notifCounter.laceext",
    "notifWatch" to "notifWatch.laceext",
    "notifRelay" to "notifRelay.laceext",
    "hookTrace" to "hookTrace.laceext",
    "badNamespace" to "badNamespace.laceext",
    "configDemo" to "configDemo.laceext",
)

// ═══════════════════════════════════════════════════════════════
// Runtime state
// ═══════════════════════════════════════════════════════════════

class ExecutionEnv(
    val scriptVars: Map<String, Any?>,
    prev: Map<String, Any?>?,
    val bodiesDir: String,
    val registry: ExtensionRegistry,
    userAgent: String? = null,
    val saveBodies: Boolean = false,
) {
    val runVars = mutableMapOf<String, Any?>()
    val prev: Map<String, Any?> = prev ?: emptyMap()
    var thisRef: Map<String, Any?>? = null
    val cookieJars = mutableMapOf<String, MutableMap<String, String>>(DEFAULT_JAR_NAME to mutableMapOf())
    val tagCtors = registry.tagConstructors()
    val userAgent: String = userAgent ?: DEFAULT_USER_AGENT
    var defaultMaxRedirects: Int = 10
}

// ═══════════════════════════════════════════════════════════════
// Public entry point
// ═══════════════════════════════════════════════════════════════

@Suppress("UNCHECKED_CAST")
fun runScript(
    ast: Map<String, Any?>,
    scriptVars: Map<String, Any?>? = null,
    prev: Map<String, Any?>? = null,
    bodiesDir: String? = null,
    activeExtensions: List<String>? = null,
    extensionPaths: List<String>? = null,
    userAgent: String? = null,
    config: Map<String, Any?>? = null,
): Map<String, Any?> {
    // Resolve bodies dir
    val resolvedBodiesDir: String
    val saveBodies: Boolean
    if (bodiesDir != null) {
        resolvedBodiesDir = bodiesDir; saveBodies = true
    } else {
        val cfgDir = ((config?.get("result") as? Map<String, Any?>)?.get("bodies") as? Map<String, Any?>)?.get("dir")
        if (cfgDir is String && cfgDir.isNotEmpty()) {
            resolvedBodiesDir = cfgDir; saveBodies = true
        } else {
            resolvedBodiesDir = System.getenv("LACE_BODIES_DIR") ?: System.getProperty("java.io.tmpdir") + "/lacelang-bodies"
            saveBodies = false
        }
    }
    if (saveBodies) File(resolvedBodiesDir).mkdirs()

    val extCfg = (config?.get("extensions") as? Map<String, Any?>) ?: emptyMap()
    val registry = loadExtensions(activeExtensions ?: emptyList(), extensionPaths ?: emptyList(), extCfg)
    val env = ExecutionEnv(scriptVars ?: emptyMap(), prev, resolvedBodiesDir, registry, userAgent = userAgent, saveBodies = saveBodies)
    env.defaultMaxRedirects = defaultMaxRedirectsFrom(config)

    val startedAt = nowIso()
    val startedMono = System.nanoTime()
    val scriptCalls = (ast["calls"] as? List<Map<String, Any?>>) ?: emptyList()

    registry.fireHook("before script", mapOf(
        "script" to mapOf("callCount" to scriptCalls.size, "startedAt" to startedAt),
        "prev" to prev,
    ))

    val calls = mutableListOf<Map<String, Any?>>()
    val writeback = mutableMapOf<String, Any?>()
    var overall = "success"
    var cascadeOutcome: String? = null

    for ((i, call) in scriptCalls.withIndex()) {
        if (cascadeOutcome != null) { calls.add(skippedRecord(i)); continue }

        val record = runCall(call, i, env, writeback)
        calls.add(record)

        val callAction = ((record["config"] as? Map<String, Any?>)?.get("timeout") as? Map<String, Any?>)?.get("action") as? String ?: DEFAULT_TIMEOUT_ACTION
        if (record["outcome"] == "failure") { cascadeOutcome = "failure"; overall = "failure" }
        else if (record["outcome"] == "timeout" && callAction != "warn") { cascadeOutcome = "timeout"; overall = "timeout" }

        val wait = ((call["chain"] as? Map<String, Any?>)?.get("wait") as? Number)?.toLong()
        if (wait != null && wait > 0 && cascadeOutcome == null) Thread.sleep(wait)
    }

    val endedAt = nowIso()
    val elapsedMs = ((System.nanoTime() - startedMono) / 1_000_000).toInt()

    val actions = mutableMapOf<String, Any?>()
    if (writeback.isNotEmpty()) actions["variables"] = writeback
    for ((key, events) in registry.actions) actions[key] = events

    val mergedRunVars = linkedMapOf<String, Any?>()
    mergedRunVars.putAll(env.runVars)
    mergedRunVars.putAll(registry.extRunVars)

    registry.fireHook("script", mapOf(
        "script" to mapOf("callCount" to scriptCalls.size, "startedAt" to startedAt, "endedAt" to endedAt),
        "result" to mapOf("outcome" to overall, "calls" to calls, "runVars" to mergedRunVars, "actions" to actions),
        "prev" to prev,
    ))

    // Re-merge after on-script hook
    for ((key, events) in registry.actions) actions[key] = events
    mergedRunVars.clear()
    mergedRunVars.putAll(env.runVars)
    mergedRunVars.putAll(registry.extRunVars)

    return linkedMapOf(
        "outcome" to overall,
        "startedAt" to startedAt,
        "endedAt" to endedAt,
        "elapsedMs" to elapsedMs,
        "runVars" to mergedRunVars,
        "calls" to calls,
        "actions" to actions,
    )
}

// ═══════════════════════════════════════════════════════════════
// Extension loading
// ═══════════════════════════════════════════════════════════════

private fun loadExtensions(names: List<String>, paths: List<String>, extensionConfig: Map<String, Any?>): ExtensionRegistry {
    val reg = ExtensionRegistry(extensionConfig)
    for (name in names) {
        val filename = BUILTIN_EXTENSIONS[name] ?: throw RuntimeException("unknown builtin extension: '$name'")
        reg.load(builtinPath(filename))
    }
    for (p in paths) reg.load(p)
    reg.finalize()
    return reg
}

private fun builtinPath(filename: String): String {
    // Always extract to temp — works consistently from both JAR and classpath
    return extractResourceToTemp(filename)
}

private fun extractResourceToTemp(filename: String): String {
    val stream = ExecutionEnv::class.java.classLoader.getResourceAsStream("extensions/$filename")
        ?: throw RuntimeException("builtin extension not found in resources: $filename")
    val tmpDir = File(System.getProperty("java.io.tmpdir"), "lacelang-extensions")
    tmpDir.mkdirs()
    val tmpFile = File(tmpDir, filename)
    // Read as bytes to preserve exact content (avoid CRLF conversion)
    tmpFile.writeBytes(stream.readBytes())
    // Also extract sibling .config file if it exists
    val configName = filename.removeSuffix(".laceext") + ".config"
    val configStream = ExecutionEnv::class.java.classLoader.getResourceAsStream("extensions/$configName")
    if (configStream != null) {
        File(tmpDir, configName).writeBytes(configStream.readBytes())
    }
    return tmpFile.absolutePath
}

private fun defaultMaxRedirectsFrom(config: Map<String, Any?>?): Int {
    if (config == null) return DEFAULT_MAX_REDIRECTS
    val executor = config["executor"] as? Map<*, *> ?: return DEFAULT_MAX_REDIRECTS
    return try { (executor["maxRedirects"] as? Number)?.toInt() ?: DEFAULT_MAX_REDIRECTS } catch (_: Exception) { DEFAULT_MAX_REDIRECTS }
}

// ═══════════════════════════════════════════════════════════════
// Per-call execution
// ═══════════════════════════════════════════════════════════════

@Suppress("UNCHECKED_CAST")
private fun runCall(call: Map<String, Any?>, idx: Int, env: ExecutionEnv, writeback: MutableMap<String, Any?>): Map<String, Any?> {
    val callStarted = nowIso()
    val cfg = (call["config"] as? Map<String, Any?>) ?: emptyMap()
    val method = call["method"] as String

    val resolvedCfg = resolveCallConfig(cfg, env)
    env.registry.fireHook("before call", mapOf("call" to mapOf("index" to idx, "config" to resolvedCfg), "prev" to env.prev))

    val warnings = mutableListOf<String>()
    val url = interp(call["url"] as String, env, warnings)
    val headers = mutableMapOf<String, String>()
    for ((k, v) in (cfg["headers"] as? Map<String, Any?>) ?: emptyMap()) {
        headers[k] = interpHeaderValue(v, env, warnings)
    }

    val (bodyBytes, bodyCt) = resolveBody(cfg["body"] as? Map<String, Any?>, env, warnings)
    if (bodyCt != null && headers.none { it.key.equals("content-type", ignoreCase = true) }) headers["Content-Type"] = bodyCt
    if (headers.none { it.key.equals("user-agent", ignoreCase = true) }) headers["User-Agent"] = env.userAgent

    val activeJar = applyCookiesToRequest(cfg, env, url, headers)
    val (timeoutS, action, retries) = resolveTimeout(cfg)
    val verify = (resolvedCfg["security"] as? Map<String, Any?>)?.get("rejectInvalidCerts") as? Boolean ?: DEFAULT_REJECT_INVALID_CERTS

    if (!verify && url.startsWith("https://")) {
        try { probeTlsVerify(url, timeoutS) } catch (e: Exception) {
            warnings.add("TLS certificate invalid: ${e.message}; proceeding with rejectInvalidCerts=false")
        }
    }

    val follow = (resolvedCfg["redirects"] as? Map<String, Any?>)?.get("follow") as? Boolean ?: DEFAULT_FOLLOW_REDIRECTS
    val maxRedirects = ((resolvedCfg["redirects"] as? Map<String, Any?>)?.get("max") as? Number)?.toInt() ?: env.defaultMaxRedirects

    val (httpResult, _, redirectHops, redirectExceeded) = issueWithRedirectsAndRetries(
        method.uppercase(), url, headers, bodyBytes, timeoutS, verify, follow, maxRedirects,
        if (action == "retry") retries else 0, activeJar, env,
    )

    val requestRec = mapOf("url" to url, "method" to method, "headers" to headers.toMap())
    val redirectsList: List<String> = if (redirectHops.size > 1) redirectHops.subList(1, redirectHops.size) else emptyList()

    var callOutcome = "success"
    var responseRec: Map<String, Any?>? = null
    var error: String? = null

    if (httpResult.timedOut) {
        callOutcome = "timeout"
        error = if (action == "warn") null else httpResult.error
    } else if (httpResult.error != null) {
        callOutcome = "failure"; error = httpResult.error
    } else if (httpResult.response == null) {
        callOutcome = "failure"; error = "no response and no error — internal inconsistency"
    } else if (redirectExceeded) {
        callOutcome = "failure"; error = "redirect limit $maxRedirects exceeded"
    } else {
        val resp = httpResult.response
        val respCt = resp.headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        val respCtStr = if (respCt is String) respCt else if (respCt is List<*>) respCt.firstOrNull() as? String else null

        val chain = (call["chain"] as? Map<String, Any?>) ?: emptyMap()
        val bodyCap = bodyCaptureLimit(chain, env)
        val bodyTooLarge = bodyCap != null && resp.body.size > bodyCap

        val bodyPath = if (!env.saveBodies || bodyTooLarge) null
            else writeBodyFile(env, resp.body, false, respCtStr, idx)

        responseRec = buildResponseRec(resp, bodyPath)
        val mutableRespRec = (responseRec as LinkedHashMap<String, Any?>)
        if (!env.saveBodies) mutableRespRec["bodyNotCapturedReason"] = "notRequested"
        else if (bodyTooLarge) mutableRespRec["bodyNotCapturedReason"] = "bodyTooLarge"
        else if (bodyPath == null) mutableRespRec["bodyNotCapturedReason"] = "notRequested"

        absorbResponseCookies(activeJar, env, resp.headers)
        env.thisRef = buildThis(resp, responseRec, redirectsList)
    }

    // Evaluate chain
    val assertions = mutableListOf<Map<String, Any?>>()
    var scopeHardFail = false
    val chain = (call["chain"] as? Map<String, Any?>) ?: emptyMap()

    if (responseRec != null) {
        val (hardFail, scopeAsserts) = evaluateScopeBlocks(chain, env, responseRec, idx)
        assertions.addAll(scopeAsserts)
        scopeHardFail = hardFail

        val (condHardFail, condAsserts) = evaluateAssertBlock(chain, env, idx)
        assertions.addAll(condAsserts)
        if (condHardFail && !scopeHardFail) scopeHardFail = true

        if (scopeHardFail) callOutcome = "failure"

        if (!scopeHardFail && "store" in chain) {
            val storeBlock = (chain["store"] as Map<String, Any?>).toMutableMap()
            storeBlock["__call_index"] = idx
            applyStore(storeBlock, env, writeback, warnings)
        }
    }

    val record = linkedMapOf<String, Any?>(
        "index" to idx, "outcome" to callOutcome, "startedAt" to callStarted, "endedAt" to nowIso(),
        "request" to requestRec, "response" to responseRec, "redirects" to redirectsList,
        "assertions" to assertions, "config" to resolvedCfg, "warnings" to warnings, "error" to error,
    )

    env.registry.fireHook("call", mapOf(
        "call" to mapOf("index" to idx, "outcome" to callOutcome, "response" to responseRec, "assertions" to assertions, "config" to resolvedCfg),
        "prev" to env.prev,
    ))
    return record
}

private fun skippedRecord(idx: Int): Map<String, Any?> = linkedMapOf(
    "index" to idx, "outcome" to "skipped", "startedAt" to null, "endedAt" to null,
    "request" to null, "response" to null, "redirects" to emptyList<String>(),
    "assertions" to emptyList<Any>(), "config" to emptyMap<String, Any?>(),
    "warnings" to emptyList<String>(), "error" to null,
)

// ═══════════════════════════════════════════════════════════════
// Redirect + retry dispatch
// ═══════════════════════════════════════════════════════════════

private data class RedirectResult(val result: HttpResult, val finalUrl: String, val hops: List<String>, val redirectExceeded: Boolean)

@Suppress("UNCHECKED_CAST")
private fun issueWithRedirectsAndRetries(
    method: String, url: String, headers: MutableMap<String, String>, body: ByteArray?,
    timeoutS: Float, verify: Boolean, follow: Boolean, maxRedirects: Int, retries: Int,
    jarName: String, env: ExecutionEnv,
): RedirectResult {
    var attempt = 0
    while (true) {
        val hops = mutableListOf(url)
        var curUrl = url; var curMethod = method; var curBody = body
        var redirects = 0
        while (true) {
            val r = sendRequest(curMethod, curUrl, headers, curBody, timeoutS, verify)
            if (r.response == null) {
                if (r.timedOut && attempt < retries) { attempt++; break }
                return RedirectResult(r, curUrl, hops, false)
            }
            val status = r.response.status
            if (follow && status in listOf(301, 302, 303, 307, 308)) {
                absorbResponseCookies(jarName, env, r.response.headers)
                val jar = env.cookieJars[jarName] ?: mutableMapOf()
                if (jar.isNotEmpty()) headers["Cookie"] = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (redirects >= maxRedirects) return RedirectResult(r, curUrl, hops, true)
                redirects++
                val loc = r.response.headers["location"]
                val locStr = if (loc is String) loc else if (loc is List<*>) loc.firstOrNull() as? String else null
                if (locStr == null) return RedirectResult(r, curUrl, hops, false)
                curUrl = try { URI(curUrl).resolve(locStr).toString() } catch (_: Exception) { return RedirectResult(r, curUrl, hops, false) }
                hops.add(curUrl)
                if (status == 303 || (status in listOf(301, 302) && curMethod == "POST")) { curMethod = "GET"; curBody = null }
                continue
            }
            return RedirectResult(r, curUrl, hops, false)
        }
        // Retry path (from break above)
        if (attempt > retries) break
    }
    return RedirectResult(HttpResult(error = "max retries exceeded"), url, listOf(url), false)
}

// ═══════════════════════════════════════════════════════════════
// Body handling
// ═══════════════════════════════════════════════════════════════

@Suppress("UNCHECKED_CAST")
private fun resolveBody(bodyNode: Map<String, Any?>?, env: ExecutionEnv, warnings: MutableList<String>): Pair<ByteArray?, String?> {
    if (bodyNode == null) return null to null
    return when (bodyNode["type"]) {
        "json" -> gson.toJson(eval(bodyNode["value"], env)).toByteArray(Charsets.UTF_8) to "application/json"
        "form" -> {
            val data = eval(bodyNode["value"], env)
            if (data !is Map<*, *>) ByteArray(0) to "application/x-www-form-urlencoded"
            else {
                val encoded = data.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(stringify(k), "UTF-8")}=${URLEncoder.encode(stringify(v), "UTF-8")}"
                }
                encoded.toByteArray(Charsets.UTF_8) to "application/x-www-form-urlencoded"
            }
        }
        "raw" -> interp(bodyNode["value"] as String, env, warnings).toByteArray(Charsets.UTF_8) to null
        else -> null to null
    }
}

private val MIME_EXT = mapOf(
    "application/json" to ".json", "application/ld+json" to ".json", "application/problem+json" to ".json",
    "text/html" to ".html", "application/xhtml+xml" to ".html", "text/xml" to ".xml", "application/xml" to ".xml",
    "text/plain" to ".txt", "text/css" to ".css", "text/javascript" to ".js", "application/javascript" to ".js",
    "text/csv" to ".csv", "application/x-www-form-urlencoded" to ".form", "application/pdf" to ".pdf",
    "application/zip" to ".zip", "application/gzip" to ".gz", "application/octet-stream" to ".bin",
    "image/png" to ".png", "image/jpeg" to ".jpg", "image/gif" to ".gif", "image/webp" to ".webp",
    "image/svg+xml" to ".svg", "image/x-icon" to ".ico",
)

private fun extForContentType(ct: String?): String {
    if (ct == null) return ".bin"
    val base = ct.split(";", limit = 2)[0].trim().lowercase()
    MIME_EXT[base]?.let { return it }
    if ("+" in base) {
        val suffix = "+" + base.split("+", limit = 2)[1]
        mapOf("+json" to ".json", "+xml" to ".xml", "+yaml" to ".yaml", "+zip" to ".zip")[suffix]?.let { return it }
    }
    return ".bin"
}

private fun writeBodyFile(env: ExecutionEnv, body: ByteArray?, isRequest: Boolean, contentType: String?, callIndex: Int): String? {
    if (body == null || body.isEmpty()) return null
    val kind = if (isRequest) "request" else "response"
    val ext = extForContentType(contentType)
    val name = "call_${callIndex}_$kind$ext"
    val path = File(env.bodiesDir, name).absolutePath
    File(path).writeBytes(body)
    return path
}

private fun bodyCaptureLimit(chain: Map<String, Any?>, env: ExecutionEnv): Int? {
    for (method in listOf("expect", "check")) {
        @Suppress("UNCHECKED_CAST")
        val block = (chain[method] as? Map<String, Any?>) ?: continue
        @Suppress("UNCHECKED_CAST")
        val sv = (block["bodySize"] as? Map<String, Any?>) ?: continue
        var expected = eval(sv["value"], env)
        if (expected is String) expected = parseSize(expected)
        if (expected is Number) return expected.toInt()
    }
    return null
}

private val SIZE_RE = Regex("""^(\d+)(k|kb|m|mb|g|gb)?$""", RegexOption.IGNORE_CASE)

private fun parseSize(s: String): Any {
    val m = SIZE_RE.matchEntire(s.trim()) ?: return s
    val num = m.groupValues[1].toLong()
    val suf = m.groupValues[2].uppercase()
    val mult = mapOf("" to 1L, "K" to 1024L, "KB" to 1024L, "M" to 1048576L, "MB" to 1048576L, "G" to 1073741824L, "GB" to 1073741824L)
    return (num * (mult[suf] ?: 1L)).toInt()
}

// ═══════════════════════════════════════════════════════════════
// Response shaping
// ═══════════════════════════════════════════════════════════════

private fun buildResponseRec(resp: HttpResponse, bodyPath: String?): LinkedHashMap<String, Any?> {
    val t = resp.timings
    val dnsObj = mapOf("resolvedIps" to resp.dns.resolvedIps, "resolvedIp" to resp.dns.resolvedIp)
    val tlsObj: Map<String, Any?>? = resp.tls?.let { mapOf("protocol" to it.protocol, "cipher" to it.cipher, "alpn" to it.alpn, "certificate" to it.certificate) }
    return linkedMapOf(
        "status" to resp.status, "statusText" to resp.statusText,
        "headers" to lowerHeaders(resp.headers), "bodyPath" to bodyPath,
        "responseTimeMs" to t.responseTimeMs, "dnsMs" to t.dnsMs, "connectMs" to t.connectMs,
        "tlsMs" to t.tlsMs, "ttfbMs" to t.ttfbMs, "transferMs" to t.transferMs,
        "sizeBytes" to resp.body.size, "dns" to dnsObj, "tls" to tlsObj,
    )
}

@Suppress("UNCHECKED_CAST")
private fun lowerHeaders(h: Map<String, Any>): Map<String, Any> {
    val out = linkedMapOf<String, Any>()
    for ((k, v) in h) out[k.lowercase()] = v
    return out
}

private fun buildThis(resp: HttpResponse, rec: Map<String, Any?>, redirects: List<String>): Map<String, Any?> {
    val ctype = (rec["headers"] as? Map<*, *>)?.entries?.firstOrNull { (it.key as? String)?.lowercase() == "content-type" }?.value
    val ctypeStr = if (ctype is String) ctype else if (ctype is List<*>) ctype.firstOrNull() as? String else ""
    val decoded = try { String(resp.body, Charsets.UTF_8) } catch (_: Exception) { null }
    val body: Any? = if (decoded != null && ctypeStr.toString().lowercase().contains("application/json")) {
        try { gson.fromJson(decoded, Any::class.java) } catch (_: Exception) { decoded }
    } else decoded

    return mapOf(
        "status" to rec["status"], "statusText" to rec["statusText"], "headers" to rec["headers"],
        "body" to body, "size" to rec["sizeBytes"], "redirects" to redirects,
        "responseTime" to rec["responseTimeMs"], "responseTimeMs" to rec["responseTimeMs"],
        "totalDelayMs" to rec["responseTimeMs"], "connect" to rec["connectMs"],
        "ttfb" to rec["ttfbMs"], "transfer" to rec["transferMs"],
        "dns" to rec["dns"], "tls" to rec["tls"], "dnsMs" to rec["dnsMs"], "tlsMs" to rec["tlsMs"],
    )
}

// ═══════════════════════════════════════════════════════════════
// Cookie jar handling
// ═══════════════════════════════════════════════════════════════

@Suppress("UNCHECKED_CAST")
private fun applyCookiesToRequest(cfg: Map<String, Any?>, env: ExecutionEnv, url: String, headers: MutableMap<String, String>): String {
    val jarSpec = cfg["cookieJar"] as? String ?: "inherit"
    val clearList = (cfg["clearCookies"] as? List<String>) ?: emptyList()
    val (jarName, fresh, selective) = resolveJarSpec(jarSpec, clearList)
    if (fresh) env.cookieJars[jarName] = mutableMapOf()
    else if (selective) {
        val jar = env.cookieJars.getOrPut(jarName) { mutableMapOf() }
        for (c in clearList) jar.remove(c)
    } else env.cookieJars.getOrPut(jarName) { mutableMapOf() }

    val staticCookies = mutableMapOf<String, String>()
    for ((name, expr) in (cfg["cookies"] as? Map<String, Any?>) ?: emptyMap()) staticCookies[name] = stringify(eval(expr, env))
    val combined = env.cookieJars[jarName]!! + staticCookies
    if (combined.isNotEmpty()) headers["Cookie"] = combined.entries.joinToString("; ") { "${it.key}=${it.value}" }
    return jarName
}

private data class JarSpec(val name: String, val fresh: Boolean, val selective: Boolean)

private fun resolveJarSpec(spec: String, clearList: List<String>): JarSpec = when {
    spec == "inherit" -> JarSpec(DEFAULT_JAR_NAME, false, false)
    spec == "fresh" -> JarSpec(DEFAULT_JAR_NAME, true, false)
    spec == "selective_clear" -> JarSpec(DEFAULT_JAR_NAME, false, true)
    spec.startsWith("named:") -> JarSpec(spec.removePrefix("named:"), false, false)
    spec.endsWith(":selective_clear") -> JarSpec(spec.removeSuffix(":selective_clear"), false, true)
    else -> JarSpec(DEFAULT_JAR_NAME, false, false)
}

@Suppress("UNCHECKED_CAST")
private fun absorbResponseCookies(jarName: String, env: ExecutionEnv, headers: Map<String, Any>) {
    for ((k, v) in headers) {
        if (k.lowercase() != "set-cookie") continue
        val values = if (v is List<*>) v as List<String> else listOf(v as String)
        for (raw in values) {
            val eqIdx = raw.indexOf('=')
            if (eqIdx < 0) continue
            val name = raw.substring(0, eqIdx).trim()
            val rest = raw.substring(eqIdx + 1)
            val value = rest.split(";", limit = 2)[0].trim()
            env.cookieJars.getOrPut(jarName) { mutableMapOf() }[name] = value
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Timeout / config resolution
// ═══════════════════════════════════════════════════════════════

private data class TimeoutSpec(val timeoutS: Float, val action: String, val retries: Int)

@Suppress("UNCHECKED_CAST")
private fun resolveTimeout(cfg: Map<String, Any?>): TimeoutSpec {
    val t = (cfg["timeout"] as? Map<String, Any?>) ?: emptyMap()
    val ms = (t["ms"] as? Number)?.toInt() ?: DEFAULT_TIMEOUT_MS
    val action = t["action"] as? String ?: DEFAULT_TIMEOUT_ACTION
    val retries = if (action == "retry") (t["retries"] as? Number)?.toInt() ?: DEFAULT_TIMEOUT_RETRIES else 0
    return TimeoutSpec(ms / 1000f, action, retries)
}

@Suppress("UNCHECKED_CAST")
private fun resolveCallConfig(cfg: Map<String, Any?>, env: ExecutionEnv): Map<String, Any?> {
    val resolved = resolveNode(cfg, env) as? MutableMap<String, Any?> ?: mutableMapOf()

    val timeout = ((resolved["timeout"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf())
    timeout.putIfAbsent("ms", DEFAULT_TIMEOUT_MS)
    timeout.putIfAbsent("action", DEFAULT_TIMEOUT_ACTION)
    timeout.putIfAbsent("retries", DEFAULT_TIMEOUT_RETRIES)
    resolved["timeout"] = timeout

    val redirects = ((resolved["redirects"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf())
    redirects.putIfAbsent("follow", DEFAULT_FOLLOW_REDIRECTS)
    redirects.putIfAbsent("max", env.defaultMaxRedirects)
    resolved["redirects"] = redirects

    val security = ((resolved["security"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf())
    security.putIfAbsent("rejectInvalidCerts", DEFAULT_REJECT_INVALID_CERTS)
    resolved["security"] = security

    return resolved
}

// ═══════════════════════════════════════════════════════════════
// Scope / assertion evaluation
// ═══════════════════════════════════════════════════════════════

private val DEFAULT_OP = mapOf(
    "status" to "eq", "body" to "eq", "headers" to "eq", "size" to "eq",
    "bodySize" to "lt", "totalDelayMs" to "lt", "dns" to "lt", "connect" to "lt",
    "tls" to "lt", "ttfb" to "lt", "transfer" to "lt",
)

private val SCOPE_ACTUAL_KEY = mapOf(
    "status" to "status", "body" to "body", "headers" to "headers",
    "bodySize" to "sizeBytes", "totalDelayMs" to "responseTimeMs",
    "dns" to "dnsMs", "connect" to "connectMs", "tls" to "tlsMs",
    "ttfb" to "ttfbMs", "transfer" to "transferMs", "size" to "sizeBytes",
)

@Suppress("UNCHECKED_CAST")
private fun evaluateScopeBlocks(chain: Map<String, Any?>, env: ExecutionEnv, response: Map<String, Any?>, callIndex: Int): Pair<Boolean, List<Map<String, Any?>>> {
    var hardFail = false
    val records = mutableListOf<Map<String, Any?>>()
    for (method in listOf("expect", "check")) {
        val block = (chain[method] as? Map<String, Any?>) ?: continue
        for (field in block.keys.filter { !it.startsWith("__") }) {
            if (field == "tls" && ((env.thisRef?.get("tlsMs") as? Number)?.toInt() ?: 0) == 0) continue
            val sv = block[field] as? Map<String, Any?> ?: continue
            val expected = eval(sv["value"], env)
            val op = sv["op"] as? String ?: DEFAULT_OP[field] ?: "eq"
            val matchSel = sv["match"] as? String
            val resolvedOptions = resolveOptions(sv["options"] as? Map<String, Any?>, env)
            val mode = sv["mode"] as? String

            fireScopeHook(env, "before $method", callIndex, field, expected, op, resolvedOptions, null, null)

            val (actual, outcome) = evaluateScope(field, op, expected, env, response, matchSel, mode)
            val rec = linkedMapOf<String, Any?>(
                "method" to method, "scope" to field, "op" to op, "outcome" to outcome,
                "actual" to jsonable(actual), "expected" to jsonable(expected),
                "options" to if (resolvedOptions?.isNotEmpty() == true) resolvedOptions else null,
            )
            if (field == "redirects") rec["match"] = matchSel ?: "any"
            records.add(rec)

            fireScopeHook(env, method, callIndex, field, expected, op, resolvedOptions, actual, outcome)
            if (outcome == "failed" && method == "expect") hardFail = true
        }
    }
    return hardFail to records
}

private fun fireScopeHook(env: ExecutionEnv, hook: String, callIndex: Int, scopeName: String, expected: Any?, op: String, options: Map<String, Any?>?, actual: Any?, outcome: String?) {
    val scopeCtx = mutableMapOf<String, Any?>("name" to scopeName, "value" to expected, "op" to op, "options" to options)
    if (outcome != null) { scopeCtx["actual"] = actual; scopeCtx["outcome"] = outcome }
    env.registry.fireHook(hook, mapOf("call" to mapOf("index" to callIndex), "scope" to scopeCtx, "this" to env.thisRef, "prev" to env.prev))
}

private fun resolveOptions(options: Map<String, Any?>?, env: ExecutionEnv): Map<String, Any?>? {
    if (options == null) return null
    return options.mapValues { eval(it.value, env) }
}

@Suppress("UNCHECKED_CAST")
private fun evaluateScope(field: String, op: String, expected: Any?, env: ExecutionEnv, response: Map<String, Any?>, matchSel: String?, mode: String?): Pair<Any?, String> {
    if (field == "redirects") {
        val redirects = (env.thisRef?.get("redirects") as? List<String>) ?: emptyList()
        val match = matchSel ?: "any"
        return when (match) {
            "any" -> redirects to if (expected in redirects) "passed" else "failed"
            "first" -> { val a = redirects.firstOrNull(); a to if (a == expected) "passed" else "failed" }
            "last" -> { val a = redirects.lastOrNull(); a to if (a == expected) "passed" else "failed" }
            else -> redirects to "indeterminate"
        }
    }

    val actual = resolveScopeActual(field, env, response)
    var exp = expected
    if (field == "bodySize" && exp is String) exp = parseSize(exp)
    if (field == "body" && exp is Map<*, *> && exp["__lace_schema__"] == true) {
        val schema = exp["schema"]
        if (schema == null) return actual to "failed"
        val strict = mode == "strict"
        return actual to validateSchema(actual, schema, strict = strict)
    }
    return actual to applyOp(op, actual, exp)
}

private fun resolveScopeActual(field: String, env: ExecutionEnv, response: Map<String, Any?>): Any? {
    val key = SCOPE_ACTUAL_KEY[field] ?: return null
    if (key == "body") return env.thisRef?.get("body")
    if (key == "headers") return response["headers"]
    return response[key]
}

@Suppress("UNCHECKED_CAST")
private fun evaluateAssertBlock(chain: Map<String, Any?>, env: ExecutionEnv, callIndex: Int): Pair<Boolean, List<Map<String, Any?>>> {
    var hardFail = false
    val records = mutableListOf<Map<String, Any?>>()
    val block = (chain["assert"] as? Map<String, Any?>) ?: return false to records
    for (kind in listOf("expect", "check")) {
        val items = (block[kind] as? List<Map<String, Any?>>) ?: continue
        for ((idx, item) in items.withIndex()) {
            val cond = item["condition"] as? Map<String, Any?>
            val expressionSrc = if (cond != null) fmt(cond) else ""
            val resolvedOptions = resolveOptions(item["options"] as? Map<String, Any?>, env)

            fireAssertHook(env, "before assert", callIndex, idx, kind, expressionSrc, resolvedOptions, null, null, null)

            val (lhsNode, rhsNode) = splitOperands(cond)
            val actualLhs = if (lhsNode != null) eval(lhsNode, env) else null
            val actualRhs = if (rhsNode != null) eval(rhsNode, env) else null

            val opName = (cond as? Map<String, Any?>)?.get("op") as? String
            val indeterminateOps = setOf("lt", "lte", "gt", "gte", "add", "sub", "mul", "div", "mod")
            val outcome = if (opName in indeterminateOps && (actualLhs == null || actualRhs == null)) "indeterminate"
            else { val result = eval(cond, env); if (Interpreter.truthy(result)) "passed" else "failed" }

            val rec = linkedMapOf<String, Any?>(
                "method" to "assert", "kind" to kind, "index" to idx, "outcome" to outcome,
                "expression" to expressionSrc, "actualLhs" to jsonable(actualLhs), "actualRhs" to jsonable(actualRhs),
                "options" to if (resolvedOptions?.isNotEmpty() == true) resolvedOptions else null,
            )
            records.add(rec)

            fireAssertHook(env, "assert", callIndex, idx, kind, expressionSrc, resolvedOptions, actualLhs, actualRhs, outcome)
            if (outcome == "failed" && kind == "expect") hardFail = true
        }
    }
    return hardFail to records
}

private fun fireAssertHook(env: ExecutionEnv, hook: String, callIndex: Int, index: Int, kind: String, expression: String, options: Map<String, Any?>?, actualLhs: Any?, actualRhs: Any?, outcome: String?) {
    val condCtx = mutableMapOf<String, Any?>("index" to index, "kind" to kind, "expression" to expression, "options" to options)
    if (outcome != null) { condCtx["actualLhs"] = actualLhs; condCtx["actualRhs"] = actualRhs; condCtx["outcome"] = outcome }
    env.registry.fireHook(hook, mapOf("call" to mapOf("index" to callIndex), "condition" to condCtx, "this" to env.thisRef, "prev" to env.prev))
}

@Suppress("UNCHECKED_CAST")
private fun splitOperands(expr: Any?): Pair<Any?, Any?> {
    if (expr is Map<*, *> && expr["kind"] == "binary") return expr["left"] to expr["right"]
    return expr to null
}

private fun applyOp(op: String, actual: Any?, expected: Any?): String {
    if (expected is List<*>) return if (actual in expected) "passed" else "failed"
    if (actual == null || expected == null) {
        if (op == "eq" || op == "neq") { val eq = actual == expected; return if ((op == "eq") == eq) "passed" else "failed" }
        return "indeterminate"
    }
    return try {
        when (op) {
            "eq" -> if (numAwareEquals(actual, expected)) "passed" else "failed"
            "neq" -> if (!numAwareEquals(actual, expected)) "passed" else "failed"
            "lt" -> if (numCompare(actual, expected) < 0) "passed" else "failed"
            "lte" -> if (numCompare(actual, expected) <= 0) "passed" else "failed"
            "gt" -> if (numCompare(actual, expected) > 0) "passed" else "failed"
            "gte" -> if (numCompare(actual, expected) >= 0) "passed" else "failed"
            else -> "indeterminate"
        }
    } catch (_: Exception) { "indeterminate" }
}

private fun numAwareEquals(a: Any, b: Any): Boolean {
    if (a is Number && b is Number) return a.toDouble() == b.toDouble()
    return a == b
}

private fun numCompare(a: Any, b: Any): Int {
    if (a is Number && b is Number) return a.toDouble().compareTo(b.toDouble())
    if (a is String && b is String) return a.compareTo(b)
    throw IllegalArgumentException("incomparable types")
}

// ═══════════════════════════════════════════════════════════════
// Schema validation
// ═══════════════════════════════════════════════════════════════

@Suppress("UNCHECKED_CAST")
private fun validateSchema(body: Any?, schema: Any?, strict: Boolean = false): String {
    if (body == null && schema != null) return "failed"
    if (schema !is Map<*, *>) return "indeterminate"
    val s = schema as Map<String, Any?>
    val t = s["type"]
    if (t != null) {
        val types = if (t is String) listOf(t) else (t as? List<*>)?.map { it.toString() } ?: emptyList()
        if (body is Boolean && "boolean" !in types) return "failed"
        val typeCheck = types.any { tt -> when (tt) {
            "string" -> body is String
            "integer" -> body is Int || body is Long || (body is Double && body == kotlin.math.floor(body) && !body.isInfinite())
            "number" -> body is Number && body !is Boolean; "boolean" -> body is Boolean
            "object" -> body is Map<*, *>; "array" -> body is List<*>; "null" -> body == null
            else -> false
        }}
        if (!typeCheck) return "failed"
    }
    val enum = s["enum"] as? List<*>
    if (enum != null && body !in enum) return "failed"
    if (body is Map<*, *>) {
        val required = (s["required"] as? List<*>)?.map { it.toString() } ?: emptyList()
        for (req in required) if (req !in body) return "failed"
        val declared = (s["properties"] as? Map<String, Any?>) ?: emptyMap()
        if (strict && declared.isNotEmpty()) {
            val extra = body.keys.map { it.toString() }.toSet() - declared.keys
            if (extra.isNotEmpty()) return "failed"
        }
        for ((k, sub) in declared) {
            if (k in body) { val out = validateSchema(body[k], sub, strict = strict); if (out != "passed") return out }
        }
    }
    if (body is List<*>) {
        val items = s["items"] as? Map<String, Any?>
        if (items != null) for (it in body) { val out = validateSchema(it, items, strict = strict); if (out != "passed") return out }
    }
    if (body is String) {
        val pat = s["pattern"] as? String
        if (pat != null && !Regex(pat).containsMatchIn(body)) return "failed"
    }
    return "passed"
}

// ═══════════════════════════════════════════════════════════════
// .store
// ═══════════════════════════════════════════════════════════════

@Suppress("UNCHECKED_CAST")
private fun applyStore(block: Map<String, Any?>, env: ExecutionEnv, writeback: MutableMap<String, Any?>, warnings: MutableList<String>) {
    val callIndex = (block["__call_index"] as? Number)?.toInt() ?: 0
    for (key in block.keys.filter { !it.startsWith("__") }) {
        val entry = block[key] as? Map<String, Any?> ?: continue
        val scope = if (entry["scope"] == "run") "run" else "writeback"
        val value = eval(entry["value"], env)

        env.registry.fireHook("before store", mapOf(
            "call" to mapOf("index" to callIndex),
            "entry" to mapOf("key" to key, "value" to value, "scope" to scope),
            "this" to env.thisRef, "prev" to env.prev,
        ))

        var written = true
        if (entry["scope"] == "run") {
            val bare = if (key.startsWith("$$")) key.substring(2) else key
            if (bare in env.runVars) { warnings.add("run-scope var '$bare' already assigned; write-once skip"); written = false }
            else env.runVars[bare] = value
        } else {
            val wbKey = if (key.startsWith("$")) key.substring(1) else key
            writeback[wbKey] = value
        }

        env.registry.fireHook("store", mapOf(
            "call" to mapOf("index" to callIndex),
            "entry" to mapOf("key" to key, "value" to value, "scope" to scope, "written" to written),
            "this" to env.thisRef, "prev" to env.prev,
        ))
    }
}

// ═══════════════════════════════════════════════════════════════
// Expression evaluation
// ═══════════════════════════════════════════════════════════════

@Suppress("UNCHECKED_CAST")
internal fun eval(node: Any?, env: ExecutionEnv): Any? {
    if (node !is Map<*, *>) return node
    val n = node as Map<String, Any?>
    return when (n["kind"]) {
        "literal" -> if (n["valueType"] == "string") interp(n["value"] as String, env) else n["value"]
        "scriptVar" -> walkVarPath(env.scriptVars[n["name"]], n["path"] as? List<Map<String, Any?>>)
        "runVar" -> walkVarPath(env.runVars[n["name"]], n["path"] as? List<Map<String, Any?>>)
        "thisRef" -> walkPath(env.thisRef, (n["path"] as? List<String>) ?: emptyList())
        "prevRef" -> {
            var cur: Any? = env.prev
            for (seg in (n["path"] as? List<Map<String, Any?>>) ?: emptyList()) {
                cur = when (seg["type"]) {
                    "field" -> (cur as? Map<*, *>)?.get(seg["name"])
                    else -> { val i = (seg["index"] as? Number)?.toInt() ?: -1; (cur as? List<*>)?.getOrNull(i) }
                }
            }
            cur
        }
        "unary" -> {
            val op = n["op"] as? String ?: "not"
            val v = eval(n["operand"], env)
            when (op) {
                "not" -> !Interpreter.truthy(v)
                "-" -> if (v is Number && v !is Boolean) numNeg(v) else null
                else -> null
            }
        }
        "binary" -> evalBinary(n, env)
        "funcCall" -> evalFunc(n, env)
        "objectLit" -> {
            val out = linkedMapOf<String, Any?>()
            for (e in (n["entries"] as? List<Map<String, Any?>>) ?: emptyList()) out[e["key"] as String] = eval(e["value"], env)
            out
        }
        "arrayLit" -> (n["items"] as? List<*>)?.map { eval(it, env) } ?: emptyList<Any?>()
        else -> null
    }
}

@Suppress("UNCHECKED_CAST")
private fun evalBinary(node: Map<String, Any?>, env: ExecutionEnv): Any? {
    val op = node["op"] as String
    if (op == "and") { val left = eval(node["left"], env); return if (Interpreter.truthy(left)) eval(node["right"], env) else left }
    if (op == "or") { val left = eval(node["left"], env); return if (Interpreter.truthy(left)) left else eval(node["right"], env) }
    val a = eval(node["left"], env); val b = eval(node["right"], env)
    if (a == null || b == null) {
        if (op == "eq") return a == b; if (op == "neq") return a != b; return null
    }
    return try { when (op) {
        "eq" -> numAwareEquals(a, b); "neq" -> !numAwareEquals(a, b)
        "lt" -> numCompare(a, b) < 0; "lte" -> numCompare(a, b) <= 0
        "gt" -> numCompare(a, b) > 0; "gte" -> numCompare(a, b) >= 0
        "+" -> if (a is String || b is String) stringify(a) + stringify(b)
               else if (a is Number && b is Number) numAdd(a, b) else null
        "-" -> if (a is Number && b is Number) numSub(a, b) else null
        "*" -> if (a is Number && b is Number) numMul(a, b) else null
        "/" -> if (a is Number && b is Number) { if ((b as Number).toDouble() == 0.0) null
               else if (a is Double || b is Double) (a as Number).toDouble() / (b as Number).toDouble()
               else Math.floorDiv((a as Number).toInt(), (b as Number).toInt()) } else null
        "%" -> if (a is Number && b is Number) { if ((b as Number).toDouble() == 0.0) null
               else Math.floorMod((a as Number).toInt(), (b as Number).toInt()) } else null
        else -> null
    } } catch (_: Exception) { null }
}

@Suppress("UNCHECKED_CAST")
private fun evalFunc(node: Map<String, Any?>, env: ExecutionEnv): Any? {
    val name = node["name"] as String
    val argsNodes = (node["args"] as? List<*>) ?: emptyList<Any?>()
    if (name in listOf("json", "form")) return if (argsNodes.isNotEmpty()) eval(argsNodes[0], env) else null
    if (name == "schema") { val v = if (argsNodes.isNotEmpty()) eval(argsNodes[0], env) else null; return mapOf("__lace_schema__" to true, "schema" to v) }
    if (name in env.tagCtors) return env.tagCtors[name]!!(argsNodes.map { eval(it, env) })
    return null
}

private fun walkVarPath(value: Any?, path: List<Map<String, Any?>>?): Any? {
    if (path == null) return value
    var cur = value
    for (seg in path) {
        if (cur == null) return null
        cur = when (seg["type"]) {
            "field" -> (cur as? Map<*, *>)?.get(seg["name"])
            else -> { val i = (seg["index"] as? Number)?.toInt() ?: -1; (cur as? List<*>)?.getOrNull(i) }
        }
    }
    return cur
}

private fun walkPath(obj: Any?, path: List<String>): Any? {
    var cur = obj
    for (p in path) cur = (cur as? Map<*, *>)?.get(p)
    return cur
}

// ═══════════════════════════════════════════════════════════════
// String interpolation
// ═══════════════════════════════════════════════════════════════

private fun interpHeaderValue(v: Any?, env: ExecutionEnv, warnings: MutableList<String>): String {
    val value = eval(v, env)
    if (value == null) warnings.add("null value interpolated as \"null\"")
    return stringify(value)
}

private fun interp(s: String, env: ExecutionEnv, warnings: MutableList<String>? = null): String {
    return INTERP_RE.replace(s) { m ->
        val (name, value) = when {
            m.groupValues[1].isNotEmpty() -> { val vn = m.groupValues[1].substring(2); "$$${m.groupValues[1].substring(2)}" to env.runVars[vn] }
            m.groupValues[2].isNotEmpty() -> { val vn = m.groupValues[2].substring(1); "$${m.groupValues[2].substring(1)}" to env.scriptVars[vn] }
            m.groupValues[3].isNotEmpty() -> "$$${m.groupValues[3]}" to env.runVars[m.groupValues[3]]
            else -> "$${m.groupValues[4]}" to env.scriptVars[m.groupValues[4]]
        }
        if (value == null) warnings?.add("null variable '$name' interpolated as \"null\"")
        stringify(value)
    }
}

internal fun stringify(v: Any?): String = when {
    v == null -> "null"
    v is Boolean -> if (v) "true" else "false"
    v is Number -> v.toString()
    v is String -> v
    else -> gson.toJson(v)
}

// ═══════════════════════════════════════════════════════════════
// Utilities
// ═══════════════════════════════════════════════════════════════

private fun jsonable(v: Any?): Any? = when {
    v == null || v is Boolean || v is Number || v is String -> v
    v is List<*> -> v.map { jsonable(it) }
    v is Map<*, *> -> v.mapKeys { it.key.toString() }.mapValues { jsonable(it.value) }
    else -> v.toString()
}

@Suppress("UNCHECKED_CAST")
private fun resolveNode(node: Any?, env: ExecutionEnv): Any? = when {
    node is Map<*, *> -> {
        val m = node as Map<String, Any?>
        if ("kind" in m) eval(m, env)
        else {
            val out = linkedMapOf<String, Any?>()
            for ((k, v) in m) {
                if (k.startsWith("__")) continue
                if (k == "extensions" && v is Map<*, *>) { out["extensions"] = (v as Map<String, Any?>).mapValues { resolveNode(it.value, env) }; continue }
                out[k] = resolveNode(v, env)
            }
            out
        }
    }
    node is List<*> -> node.map { resolveNode(it, env) }
    else -> node
}

private fun numAdd(a: Number, b: Number): Number = if (a is Double || b is Double) a.toDouble() + b.toDouble() else a.toInt() + b.toInt()
private fun numSub(a: Number, b: Number): Number = if (a is Double || b is Double) a.toDouble() - b.toDouble() else a.toInt() - b.toInt()
private fun numMul(a: Number, b: Number): Number = if (a is Double || b is Double) a.toDouble() * b.toDouble() else a.toInt() * b.toInt()
private fun numNeg(v: Number): Number = if (v is Double) -v else -(v.toInt())
