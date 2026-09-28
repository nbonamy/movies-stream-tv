package fr.bonamy.movies

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.bonamy.movies.core.RemoteSearchServer
import java.io.ByteArrayOutputStream

/** Owns the phone search endpoint for the visible activity, including its QR shortcut. */
internal class RemoteSearchController(context: Context, private val onSearch: (String) -> Unit) {
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var server: RemoteSearchServer? = null
    private var address: String? = null
    private val qr = ImageView(context).apply {
        contentDescription = "Scan to search from your phone"
        setBackgroundResource(R.drawable.remote_search_qr_frame)
        setPadding(dp(5), dp(5), dp(5), dp(5))
    }
    private val addressLabel = TextView(context).apply {
        textSize = 11f
        setSingleLine(true)
        setTextColor(0xffb8c2ce.toInt())
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, 0)
    }
    val shortcut = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        // Match Music's QR size; the URL measures independently below it.
        addView(qr, LinearLayout.LayoutParams(dp(72), dp(72)))
        addView(addressLabel, LinearLayout.LayoutParams(-2, -2))
    }
    private val page = context.assets.open("remote/index.html").bufferedReader().use { it.readText() }
    private val icon = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).let { bitmap ->
        context.getDrawable(R.mipmap.ic_launcher)?.apply {
            setBounds(0, 0, 192, 192)
            draw(Canvas(bitmap))
        }
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    fun start() {
        val currentAddress = RemoteSearchAddress.find()
        if (server != null && address == currentAddress) return
        stop()
        if (currentAddress == null) return
        val candidate = RemoteSearchServer({ query ->
            handler.post { if (server != null) onSearch(query) }
        }, page, icon)
        try {
            val url = candidate.start(currentAddress)
            qr.setImageBitmap(QrCodeBitmap.create(url, dp(72)))
            addressLabel.text = url
            address = currentAddress
            server = candidate
            shortcut.visibility = View.VISIBLE
        } catch (error: Exception) {
            candidate.stop()
            Log.w("RemoteSearch", "Unable to start phone search", error)
        }
    }

    fun stop() {
        server?.stop()
        server = null
        address = null
        handler.removeCallbacksAndMessages(null)
        shortcut.visibility = View.GONE
    }

    private fun dp(value: Int) = (value * density).toInt()
}
