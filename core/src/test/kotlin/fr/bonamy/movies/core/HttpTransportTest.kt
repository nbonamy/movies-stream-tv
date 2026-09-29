package fr.bonamy.movies.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HttpTransportTest {
    @Test fun boundsUnknownLengthBodiesAndRejectsDisallowedRedirects() {
        MockWebServer().use { server ->
            val http = HttpTransport(allowLoopback = true)
            server.enqueue(MockResponse().setChunkedBody("a".repeat(33), 8))
            assertThrows(IOException::class.java) { http.bytes(server.url("/large"), limit = 32) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.1/private"))
            assertThrows(IOException::class.java) { http.bytes(server.url("/redirect")) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun dropsCredentialsOnCrossHostRedirectsButPreservesPlayerReferer() {
        MockWebServer().use { server ->
            val first = server.url("/first").newBuilder().host("localhost").build()
            val next = server.url("/next").newBuilder().host("127.0.0.1").build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", next))
            server.enqueue(MockResponse().setBody("playlist"))
            assertEquals("playlist", HttpTransport(allowLoopback = true).text(first,
                mapOf("Authorization" to "test-token", "Cookie" to "test-cookie", "Referer" to "https://player.example/")))
            assertEquals("test-token", server.takeRequest().getHeader("Authorization"))
            val redirected = server.takeRequest()
            assertNull(redirected.getHeader("Authorization"))
            assertNull(redirected.getHeader("Cookie"))
            assertEquals("https://player.example/", redirected.getHeader("Referer"))
        }
    }
    @Test fun postsBinaryEnvelopeWithBoundsAndNeverForwardsItOnRedirect() {
        MockWebServer().use { server ->
            val transport = HttpTransport(allowLoopback = true)
            val body = byteArrayOf(0, 2, -1, 32)
            server.enqueue(MockResponse().setBody("reply"))
            assertEquals("reply", transport.postBytes(server.url("/g"), body).toString(Charsets.UTF_8))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("text/plain;charset=UTF-8", request.getHeader("Content-Type"))
            assertArrayEquals(body, request.body.readByteArray())
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/other"))
            assertThrows(IOException::class.java) { transport.postBytes(server.url("/g"), body) }
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setChunkedBody("a".repeat(33), 8))
            assertThrows(IOException::class.java) { transport.postBytes(server.url("/g"), body, limit = 32) }
        }
    }

}
