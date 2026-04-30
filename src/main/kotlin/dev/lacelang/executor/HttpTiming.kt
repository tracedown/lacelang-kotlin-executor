package dev.lacelang.executor

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

/**
 * HTTP client with per-phase timing (dns / connect / tls / ttfb / transfer).
 * Uses OkHttp with EventListener for timing breakdown.
 *
 * Does NOT follow redirects or manage cookies — the executor handles those.
 */

data class Timings(
    var dnsMs: Int = 0,
    var connectMs: Int = 0,
    var tlsMs: Int = 0,
    var ttfbMs: Int = 0,
    var transferMs: Int = 0,
    var responseTimeMs: Int = 0,
)

data class DnsMeta(
    val resolvedIps: List<String> = emptyList(),
    val resolvedIp: String? = null,
)

data class TlsMeta(
    val protocol: String = "",
    val cipher: String = "",
    val alpn: String? = null,
    val certificate: Map<String, Any?>? = null,
)

data class HttpResponse(
    val status: Int,
    val statusText: String,
    val headers: Map<String, Any>, // String or List<String>
    val body: ByteArray,
    val timings: Timings,
    val finalUrl: String,
    val dns: DnsMeta = DnsMeta(),
    val tls: TlsMeta? = null,
)

data class HttpResult(
    val response: HttpResponse? = null,
    val error: String? = null,
    val timedOut: Boolean = false,
    val timings: Timings = Timings(),
)

private fun msElapsed(startNanos: Long): Int =
    maxOf(0, ((System.nanoTime() - startNanos) / 1_000_000).toInt())

fun probeTlsVerify(url: String, timeoutS: Float) {
    if (!url.startsWith("https://")) return
    val parsed = url.toHttpUrlOrNull() ?: return
    val client = OkHttpClient.Builder()
        .connectTimeout((timeoutS * 1000).toLong(), TimeUnit.MILLISECONDS)
        .readTimeout((timeoutS * 1000).toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .build()
    val request = Request.Builder().url(parsed).method("HEAD", null).build()
    try {
        client.newCall(request).execute().close()
    } catch (_: SSLHandshakeException) {
        throw SSLHandshakeException("TLS certificate invalid")
    } catch (_: IOException) {
        // Non-TLS error — not our concern
    }
}

fun sendRequest(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray?,
    timeoutS: Float,
    verifyTls: Boolean = true,
): HttpResult {
    val parsed = url.toHttpUrlOrNull()
    if (parsed == null) return HttpResult(error = "invalid URL: $url")

    val scheme = parsed.scheme
    if (scheme != "http" && scheme != "https") {
        return HttpResult(error = "unsupported scheme: '$scheme'")
    }

    val timings = Timings()
    val dnsMeta = MutableDnsMeta()
    val tlsMeta = MutableTlsMeta()
    val callStartNanos = System.nanoTime()

    val listener = TimingEventListener(timings, dnsMeta, tlsMeta, callStartNanos)

    // Pre-populate DNS metadata for IP literals (OkHttp skips DNS events for these)
    val host = parsed.host
    if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
        dnsMeta.resolvedIps = listOf(host)
        dnsMeta.resolvedIp = host
    }

    val clientBuilder = OkHttpClient.Builder()
        .connectTimeout((timeoutS * 1000).toLong(), TimeUnit.MILLISECONDS)
        .readTimeout((timeoutS * 1000).toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout((timeoutS * 1000).toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .eventListener(listener)

    if (!verifyTls) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), null)
        clientBuilder.sslSocketFactory(sslContext.socketFactory, trustAll)
        clientBuilder.hostnameVerifier { _, _ -> true }
    }

    val client = clientBuilder.build()

    val upperMethod = method.uppercase()
    val needsBody = upperMethod in listOf("POST", "PUT", "PATCH", "DELETE")
    val requestBody = if (body != null && needsBody) {
        val ct = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/octet-stream"
        body.toRequestBody(ct.toMediaTypeOrNull())
    } else if (body == null && needsBody) {
        // OkHttp requires a body for POST/PUT/PATCH — send empty
        ByteArray(0).toRequestBody(null)
    } else null

    val reqBuilder = Request.Builder().url(parsed)
    val headersBuilder = Headers.Builder()
    for ((k, v) in headers) headersBuilder.add(k, v)
    reqBuilder.headers(headersBuilder.build())
    reqBuilder.method(upperMethod, requestBody)

    return try {
        val response = client.newCall(reqBuilder.build()).execute()
        val responseBody = response.body?.bytes() ?: ByteArray(0)
        val tDone = System.nanoTime()
        timings.transferMs = maxOf(0, ((tDone - (listener.responseHeadersEndNanos ?: tDone)) / 1_000_000).toInt())
        timings.responseTimeMs = ((tDone - callStartNanos) / 1_000_000).toInt()

        // Collect headers, preserving multi-value
        val collected = linkedMapOf<String, Any>()
        for (i in 0 until response.headers.size) {
            val name = response.headers.name(i).lowercase()
            val value = response.headers.value(i)
            val existing = collected[name]
            if (existing != null) {
                if (existing is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (existing as MutableList<String>).add(value)
                } else {
                    collected[name] = mutableListOf(existing as String, value)
                }
            } else {
                collected[name] = value
            }
        }

        // Extract TLS metadata from connection
        val handshake = response.handshake
        val tlsResult = if (handshake != null) {
            val cert = handshake.peerCertificates.firstOrNull() as? X509Certificate
            TlsMeta(
                protocol = handshake.tlsVersion.javaName,
                cipher = handshake.cipherSuite.javaName,
                alpn = null,
                certificate = cert?.let { formatCertificate(it) },
            )
        } else tlsMeta.toTlsMeta()

        response.close()

        HttpResult(
            response = HttpResponse(
                status = response.code,
                statusText = response.message,
                headers = collected,
                body = responseBody,
                timings = timings,
                finalUrl = url,
                dns = dnsMeta.toDnsMeta(),
                tls = tlsResult,
            ),
            timings = timings,
        )
    } catch (_: java.net.SocketTimeoutException) {
        HttpResult(timedOut = true, timings = timings, error = "request timed out")
    } catch (_: java.io.InterruptedIOException) {
        HttpResult(timedOut = true, timings = timings, error = "request timed out")
    } catch (e: SSLHandshakeException) {
        HttpResult(error = "TLS handshake failed: ${e.message}", timings = timings)
    } catch (e: IOException) {
        HttpResult(error = e.message ?: "connection error", timings = timings)
    }
}

// ── TLS certificate formatting ────────────────────────────────────

internal fun formatCertificate(cert: X509Certificate): Map<String, Any?> {
    val subjectCN = extractCN(cert.subjectX500Principal.name)
    val issuerCN = extractCN(cert.issuerX500Principal.name)
    val san = cert.subjectAlternativeNames?.map { entry ->
        val type = entry[0] as Int
        val value = entry[1]
        val label = when (type) { 2 -> "DNS"; 7 -> "IP"; else -> "other" }
        "$label:$value"
    } ?: emptyList()

    return mapOf(
        "subject" to mapOf("cn" to subjectCN),
        "subjectAltNames" to san,
        "issuer" to mapOf("cn" to issuerCN),
        "notBefore" to cert.notBefore.toInstant().toString(),
        "notAfter" to cert.notAfter.toInstant().toString(),
    )
}

private fun extractCN(dn: String): String? {
    val match = Regex("""CN=([^,]+)""").find(dn)
    return match?.groupValues?.get(1)?.trim()
}

// ── EventListener for timing ──────────────────────────────────────

private class MutableDnsMeta {
    var resolvedIps: List<String> = emptyList()
    var resolvedIp: String? = null
    fun toDnsMeta() = DnsMeta(resolvedIps, resolvedIp)
}

private class MutableTlsMeta {
    var protocol: String = ""
    var cipher: String = ""
    var alpn: String? = null
    var certificate: Map<String, Any?>? = null
    fun toTlsMeta(): TlsMeta? = if (protocol.isEmpty() && cipher.isEmpty()) null
        else TlsMeta(protocol, cipher, alpn, certificate)
}

private class TimingEventListener(
    private val timings: Timings,
    private val dnsMeta: MutableDnsMeta,
    private val tlsMeta: MutableTlsMeta,
    private val callStartNanos: Long,
) : EventListener() {
    private var dnsStartNanos = 0L
    private var connectStartNanos = 0L
    private var secureConnectStartNanos = 0L
    var responseHeadersEndNanos: Long? = null
        private set

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartNanos = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        timings.dnsMs = msElapsed(dnsStartNanos)
        dnsMeta.resolvedIps = inetAddressList.map { it.hostAddress ?: "" }
        dnsMeta.resolvedIp = inetAddressList.firstOrNull()?.hostAddress
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartNanos = System.nanoTime()
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        timings.connectMs = msElapsed(connectStartNanos)
    }

    override fun secureConnectStart(call: Call) {
        secureConnectStartNanos = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        timings.tlsMs = msElapsed(secureConnectStartNanos)
        if (handshake != null) {
            tlsMeta.protocol = handshake.tlsVersion.javaName
            tlsMeta.cipher = handshake.cipherSuite.javaName
            val cert = handshake.peerCertificates.firstOrNull() as? X509Certificate
            if (cert != null) tlsMeta.certificate = formatCertificate(cert)
        }
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val now = System.nanoTime()
        responseHeadersEndNanos = now
        timings.ttfbMs = ((now - callStartNanos) / 1_000_000).toInt()
    }
}
