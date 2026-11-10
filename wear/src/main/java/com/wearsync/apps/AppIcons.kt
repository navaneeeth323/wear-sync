package com.wearsync.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.wearsync.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Icons for iPhone apps. ANCS only gives a bundle ID, so:
 *  1. App Store apps: icon fetched once from Apple's public iTunes lookup API, cached on disk.
 *  2. Built-in Apple apps (not in the App Store): a symbol on a coloured circle.
 *  3. Otherwise / until downloaded: the app's first letter on a coloured circle.
 *
 * [iconFor] never blocks; it returns the best icon available now and starts a download if needed.
 */
object AppIcons {
    private const val TAG = "WearSync/Icons"
    private const val SIZE = 96
    private const val NOT_FOUND_RETRY_MS = 7L * 24 * 60 * 60 * 1000

    private val memory = LruCache<String, Bitmap>(64)
    private val inFlight = mutableSetOf<String>()
    private val io = Executors.newSingleThreadExecutor()

    /** Bumped whenever a new icon lands, so UI can recompose. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val BUILT_IN = mapOf(
        "com.apple.MobileSMS" to (R.drawable.ic_message to 0xFF34C759.toInt()),
        "com.apple.mobilephone" to (R.drawable.ic_call to 0xFF34C759.toInt()),
        "com.apple.facetime" to (R.drawable.ic_videocam to 0xFF34C759.toInt()),
        "com.apple.mobilemail" to (R.drawable.ic_mail to 0xFF1E88E5.toInt()),
        "com.apple.mobilecal" to (R.drawable.ic_event to 0xFFE53935.toInt()),
    )

    fun iconFor(context: Context, appId: String, name: String): Bitmap {
        memory.get(appId)?.let { return it }

        BUILT_IN[appId]?.let { (drawable, color) ->
            return symbolAvatar(context, drawable, color).also { memory.put(appId, it) }
        }

        val file = cacheFile(context, appId)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { return it.also { bmp -> memory.put(appId, bmp) } }
        }

        if (appId.isNotBlank()) fetchAsync(context.applicationContext, appId)
        return letterAvatar(appId, name) // not cached in memory, so the real icon replaces it later
    }

    private fun fetchAsync(context: Context, appId: String) {
        synchronized(inFlight) {
            if (appId in inFlight) return
            val notFoundAt = notFound(context).getLong(appId, 0)
            if (System.currentTimeMillis() - notFoundAt < NOT_FOUND_RETRY_MS) return
            inFlight += appId
        }
        io.execute {
            try {
                when (val artwork = lookupArtwork(appId)) {
                    null -> {
                        Log.i(TAG, "$appId isn't in the App Store; using a letter icon")
                        notFound(context).edit { putLong(appId, System.currentTimeMillis()) }
                    }
                    else -> {
                        val bitmap = download(artwork)?.let(::circleCrop)
                        if (bitmap != null) {
                            cacheFile(context, appId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            memory.put(appId, bitmap)
                            _version.update { it + 1 }
                            Log.i(TAG, "Fetched icon for $appId")
                        }
                    }
                }
            } catch (e: Exception) {
                // Usually no internet on the watch right now; try again next notification.
                Log.w(TAG, "Icon lookup for $appId failed: ${e.message}")
            } finally {
                synchronized(inFlight) { inFlight -= appId }
            }
        }
    }

    /** Artwork URL from the App Store, trying the watch's country first, then the US store. */
    private fun lookupArtwork(appId: String): String? {
        val id = URLEncoder.encode(appId, "UTF-8")
        val countries = listOf(Locale.getDefault().country.lowercase(), "us").filter { it.isNotBlank() }.distinct()
        for (country in countries) {
            val json = get("https://itunes.apple.com/lookup?bundleId=$id&country=$country") ?: continue
            val results = JSONObject(String(json)).optJSONArray("results") ?: continue
            if (results.length() > 0) {
                val app = results.getJSONObject(0)
                return app.optString("artworkUrl100").ifBlank { app.optString("artworkUrl512") }.ifBlank { null }
            }
        }
        return null
    }

    private fun download(url: String): Bitmap? = get(url)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    private fun get(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun circleCrop(src: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, paint)
        return out
    }

    private fun letterAvatar(appId: String, name: String): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(appId) })
        val letter = (name.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercaseChar().toString()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.5f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val y = SIZE / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(letter, SIZE / 2f, y, text)
        return out
    }

    private fun symbolAvatar(context: Context, drawableRes: Int, color: Int): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        ContextCompat.getDrawable(context, drawableRes)?.mutate()?.apply {
            val inset = (SIZE * 0.24f).toInt()
            setBounds(inset, inset, SIZE - inset, SIZE - inset)
            draw(canvas)
        }
        return out
    }

    /** Stable, pleasant colour per app. */
    private fun colorFor(appId: String): Int {
        val palette = intArrayOf(
            0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF8E24AA.toInt(),
            0xFF00897B.toInt(), 0xFF3949AB.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(),
        )
        return palette[abs(appId.hashCode() % palette.size)]
    }

    private fun cacheFile(context: Context, appId: String): File {
        val dir = File(context.cacheDir, "app-icons").apply { mkdirs() }
        return File(dir, appId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
    }

    private fun notFound(context: Context) = context.getSharedPreferences("app_icons_not_found", Context.MODE_PRIVATE)
}
