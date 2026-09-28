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
}
