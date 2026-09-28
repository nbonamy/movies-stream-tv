package fr.bonamy.movies.core

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RemoteSearchServerTest {
    @Test fun `phone form delivers decoded queries and rejects invalid requests`() {
        val queries = LinkedBlockingQueue<String>()
        val server = RemoteSearchServer({ queries.add(it) }, 0, 0, "<html><!--STATUS--></html>", null)
        val url = server.start("127.0.0.1")
        fun request(path: String, method: String = "GET"): Pair<Int, String> {
            val connection = URL(url + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            return try {
                val status = connection.responseCode
                status to (if (status >= 400) connection.errorStream else connection.inputStream)
                    .bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
        }
        try {
            assertEquals(200, request("/").first)
            val query = " Amélie & <friends> "
            val response = request("/search?q=" + URLEncoder.encode(query, "UTF-8"))
            assertEquals(200, response.first)
            assertEquals("Amélie & <friends>", queries.poll(2, TimeUnit.SECONDS))
            assertTrue(response.second.contains("Amélie &amp; &lt;friends&gt;"))
            assertEquals(400, request("/search?q=a").first)
            assertEquals(400, request("/search").first)
            assertEquals(404, request("/missing").first)
            assertEquals(405, request("/search?q=hello", "POST").first)
            assertTrue(queries.isEmpty())
            assertEquals(200, request("/search?q=" + "x".repeat(250)).first)
            assertEquals(200, queries.poll(2, TimeUnit.SECONDS)?.length)
        } finally { server.stop() }
    }

    @Test fun `occupied port falls back and can be reused after stop`() {
        java.net.ServerSocket(0).use { occupied ->
            val port = occupied.localPort
            val server = RemoteSearchServer({}, port, port + 1, "page", null)
            try {
                assertEquals("http://127.0.0.1:${port + 1}", server.start("127.0.0.1"))
                server.stop()
                assertEquals("http://127.0.0.1:${port + 1}", server.start("127.0.0.1"))
                val connection = URL("http://127.0.0.1:${port + 1}/").openConnection()
                connection.readTimeout = 2000
                assertEquals("page", connection.getInputStream().bufferedReader().use { it.readText() })
            } finally { server.stop() }
        }
    }
}
