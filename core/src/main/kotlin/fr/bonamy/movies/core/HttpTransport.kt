package fr.bonamy.movies.core

import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

internal class HttpStatusException(val status: Int, host: String) : IOException("$host returned HTTP $status")

/** Bounded requests with checked redirects. Cookie/session ownership stays with the caller. */
internal class HttpTransport(http: OkHttpClient = defaultClient(), private val allowLoopback: Boolean = false) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .dns(object : okhttp3.Dns { override fun lookup(host: String): List<InetAddress> = http.dns.lookup(host).also { addresses ->
            if (!allowLoopback && addresses.any { !publicAddress(it) }) throw IOException("Unsupported network destination")
        } }).build()

    fun accepts(url: HttpUrl) = url.isHttps || (allowLoopback && url.host in listOf("localhost", "127.0.0.1"))
    fun text(url: HttpUrl, referer: String): String = text(url, mapOf("Referer" to referer))
    fun text(url: HttpUrl, headers: Map<String, String> = emptyMap()): String = bytes(url, headers).toString(Charsets.UTF_8)
    fun bytes(url: HttpUrl, headers: Map<String, String> = emptyMap(), limit: Int = 8 * 1024 * 1024,
        redirectAllowed: (HttpUrl) -> Boolean = { true }): ByteArray {
        var target = url
        var requestHeaders = headers
        repeat(6) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Request cancelled")
            if (!accepts(target)) throw IOException("Unsupported network destination")
            val request = Request.Builder().url(target).header("User-Agent", USER_AGENT)
            requestHeaders.forEach { (key, value) -> request.header(key, value) }
            client.newCall(request.build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(target::resolve) ?: throw IOException("Invalid redirect")
                    if (!accepts(next) || !redirectAllowed(next)) throw IOException("Unsupported redirect")
                    if (next.host != target.host) requestHeaders = requestHeaders.filterKeys {
                        !it.equals("Authorization", true) && !it.equals("Cookie", true)
                    }
                    target = next
                } else {
                    if (!response.isSuccessful) throw HttpStatusException(response.code, target.host)
                    val body = response.body ?: throw IOException("Empty response")
                    if (body.contentLength() > limit) throw IOException("Response is too large")
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Request cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > limit) throw IOException("Response is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                    return output.toByteArray()
                }
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/128 Safari/537.36"
        fun defaultClient() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
        private fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
            if (bytes.size == 4 && (bytes[0].toInt() and 255) == 100 && (bytes[1].toInt() and 255) in 64..127) return false
            return true
        }
    }
}

internal fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString
    ?: throw IOException("Missing $name")
