package fr.bonamy.movies.core

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** Isolated research app: fresh Kotlin resolution, no browser state or WASM execution. */
class ProofActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var label: TextView
    private lateinit var videoView: PlayerView
    private var firstFrame = false
    private var kind = "movie"
    private var finished = false
    private var begin = 0L
    private var samples = 0
    private var previousPosition = -1L
    private var advancingSamples = 0
    private var worker: Thread? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        kind = intent.getStringExtra("kind") ?: "movie"
        require(kind == "movie" || kind == "series")
        val view = PlayerView(this).apply { useController = false }; videoView = view
        label = TextView(this).apply { textSize = 15f; setTextColor(-1); setBackgroundColor(0xb0000000.toInt()); setPadding(12, 8, 12, 8) }
        val root = FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, -1)); addView(label, FrameLayout.LayoutParams(-2, -2)) }
        setContentView(root)
        java.io.File(filesDir, "$kind-result.txt").writeText("")
        report("RESOLVING $kind with Kotlin/JCA")
        worker = Thread {
            try {
                val result = resolve(kind)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    report("AUTHENTICATED_RESPONSE $kind")
                    val http = DefaultHttpDataSource.Factory().setUserAgent(HttpTransport.USER_AGENT)
                        .setDefaultRequestProperties(mapOf("Origin" to "https://cinejoy.pk", "Referer" to "https://cinejoy.pk/"))
                        .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
                    val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
                    player = p
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setForceHighestSupportedBitrate(true).build()
                    p.addAnalyticsListener(object : AnalyticsListener {
                        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                            firstFrame = true
                            report("FIRST_FRAME $kind")
                        }
                    })
                    p.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            finished = true
                            // Do not log exception messages or stack traces containing transient media URLs.
                            val causes = generateSequence<Throwable>(error) { it.cause }.map { it.javaClass.simpleName }.joinToString(" > ")
                            report("FAIL $kind ${error.errorCodeName}: $causes")
                        }
                    })
                    view.player = p
                    p.setMediaItem(MediaItem.Builder().setUri(result).setMimeType("application/x-mpegURL").build(), 60000)
                    p.prepare(); p.play()
                    begin = android.os.SystemClock.elapsedRealtime()
                    handler.post(ticker)
                }
            } catch (e: Exception) {
                runOnUiThread { report("FAIL resolve $kind ${e.javaClass.simpleName}: ${e.message?.takeIf { !it.contains("http") } ?: "details omitted"}") }
            }
        }.apply { start() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (finished) return
            samples++
            val pos = p.currentPosition
            if (previousPosition >= 0 && pos - previousPosition > 400) advancingSamples++
            previousPosition = pos
            val counters = p.videoDecoderCounters
            counters?.ensureUpdated()
            val frames = counters?.renderedOutputBufferCount ?: 0
            val format = p.videoFormat
            val status = "$kind position=$pos duration=${p.duration} frames=$frames resolution=${format?.width}x${format?.height} advancing=$advancingSamples"
            if (firstFrame && pos >= 70000 && frames >= 100 && advancingSamples >= 8 && p.isPlaying) {
                report("PASS $status")
                finished = true
                handler.postDelayed({
                    val surface = videoView.videoSurfaceView as android.view.SurfaceView
                    val bitmap = android.graphics.Bitmap.createBitmap(surface.width, surface.height, android.graphics.Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(surface, bitmap, { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            java.io.File(filesDir, "$kind-proof.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            java.io.File(filesDir, "$kind-result.txt").appendText("SCREENSHOT $kind captured\n")
                            Log.i("CinejoyProof", "SCREENSHOT $kind captured")
                        } else report("FAIL screenshot $kind status=$result")
                        bitmap.recycle()
                        p.pause()
                    }, handler)
                }, 250)
                return
            }
            if (samples % 5 == 0) report("PROGRESS $status")
            if (android.os.SystemClock.elapsedRealtime() - begin > 90000) {
                report("FAIL timeout $status")
                finished = true
                return
            }
            handler.postDelayed(this, 1000)
        }
    }
    private fun report(message: String) {
        label.text = "Cinejoy native proof\n$message"
        Log.i("CinejoyProof", message)
        java.io.File(filesDir, "$kind-result.txt").appendText(message + "\n")
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker?.interrupt()
        player?.release()
        super.onDestroy()
    }
    private fun resolve(kind: String): String {
        val http = HttpTransport()
        val headers = mapOf("Origin" to "https://cinejoy.pk", "Referer" to "https://cinejoy.pk/")
        val wasm = http.bytes("https://api.wing.st/crush.wasm".toHttpUrl(), headers, 256 * 1024, redirectAllowed = { it.host == "api.wing.st" })
        val digest = MessageDigest.getInstance("SHA-256").digest(wasm).joinToString("") { "%02x".format(it) }
        require(digest == "40c923580779e2a850fc5ab3f0046be565ed717883cf4547ed3a87de2450bdcf") { "Provider module changed; review key/version before using" }
        val key = publicKeyFromData(wasm)
        val crypto = WingCrypto(key, 2)
        val payload = if (kind == "movie") """{"tmdb":"348","imdb":"tt0078748","year":"1979","title":"Alien"}"""
            else """{"tmdb":"108978","imdb":"tt9288030","year":"2022","title":"Reacher","season":"2","episode":"3"}"""
        val clear = """{"path":"/Nebula/$kind","payload":$payload}""".toByteArray()
        val sealed = crypto.seal(clear)
        val response = http.bytes("https://api.wing.st/g".toHttpUrl(), headers, 1024 * 1024, postBody = crypto.requestBody(sealed))
        val envelope = JsonParser.parseString(crypto.decryptResponse(response, sealed).toString(Charsets.UTF_8)).asJsonObject
        require(envelope["status"].asInt == 200) { "Upstream logical status ${envelope["status"].asInt}" }
        val stream = envelope["data"].asJsonObject["stream"].asJsonArray.first().asJsonObject
        require(stream["type"].asString == "hls") { "Expected HLS" }
        val url = stream["playlist"].asString.toHttpUrl()
        require(url.isHttps) { "Expected HTTPS media" }
        val master = http.text(url, headers)
        require(master.startsWith("#EXTM3U")) { "Expected playlist" }
        return url.toString()
    }
    private fun publicKeyFromData(bytes: ByteArray): ByteArray {
        var pos = 8
        fun uint(): Int {
            var value = 0
            for (shift in 0..28 step 7) {
                val b = bytes[pos++].toInt() and 255
                value = value or ((b and 127) shl shift)
                if (b and 128 == 0) return value
            }
            error("Invalid WASM integer")
        }
        while (pos < bytes.size) {
            val section = bytes[pos++].toInt() and 255
            val size = uint()
            val end = pos + size
            require(end <= bytes.size)
            if (section == 11) {
                repeat(uint()) {
                    require(uint() == 0) { "Unexpected data segment" }
                    require(bytes[pos++].toInt() == 0x41)
                    val offset = uint()
                    require(bytes[pos++].toInt() == 0x0b)
                    val length = uint()
                    val keyOffset = 1052336 - offset
                    if (keyOffset >= 0 && keyOffset + 65 <= length) return bytes.copyOfRange(pos + keyOffset, pos + keyOffset + 65)
                    pos += length
                }
            }
            pos = end
        }
        error("Public key data not found")
    }
}
