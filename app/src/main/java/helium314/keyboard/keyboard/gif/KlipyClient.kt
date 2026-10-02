// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One GIF result. [preview] is a small (possibly animated webp) image for the grid, [full] the GIF that gets sent. */
data class KlipyGif(
    val slug: String,
    val title: String,
    val preview: Media,
    val full: Media,
) {
    data class Media(val url: String, val width: Int, val height: Int)
}

data class KlipyPage(val gifs: List<KlipyGif>, val page: Int, val hasNext: Boolean)

/**
 * Minimal client for the KLIPY GIF API (https://docs.klipy.com).
 * All functions block, call them from a background thread.
 */
object KlipyClient {
    private const val TAG = "KlipyClient"
    private const val BASE_URL = "https://api.klipy.com/api/v1/"
    private const val PER_PAGE = 50
    private const val TIMEOUT_MS = 10_000

    val apiKey: String get() = BuildConfig.KLIPY_API_KEY
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /** Trending GIFs if [query] is blank, search results otherwise. */
    fun fetch(query: String, page: Int): KlipyPage {
        val q = query.trim()
        val params = mutableMapOf("page" to page.toString(), "per_page" to PER_PAGE.toString())
        val endpoint = if (q.isEmpty()) "trending" else {
            params["q"] = q
            "search"
        }
        val json = JSONObject(String(httpGet(apiUrl(endpoint, params)), Charsets.UTF_8))
        if (!json.optBoolean("result", false)) throw IOException("KLIPY returned result=false")
        val data = json.getJSONObject("data")
        val items = data.optJSONArray("data")
        val gifs = ArrayList<KlipyGif>()
        if (items != null) {
            for (i in 0 until items.length()) {
                parseGif(items.optJSONObject(i) ?: continue)?.let { gifs.add(it) }
            }
        }
        return KlipyPage(gifs, data.optInt("current_page", page), data.optBoolean("has_next", false))
    }

    /** Tells KLIPY a GIF was sent (improves their ranking). Failures are ignored. */
    fun registerShare(gif: KlipyGif, query: String) {
        try {
            val connection = URL(apiUrl("share/${enc(gif.slug)}", emptyMap())).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("q", query).toString().toByteArray()) }
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            Log.i(TAG, "share trigger failed", e)
        }
    }

    fun download(url: String): ByteArray = httpGet(url)

    fun downloadTo(url: String, file: File) {
        val bytes = httpGet(url)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun apiUrl(endpoint: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "$BASE_URL${enc(apiKey)}/gifs/$endpoint" + if (query.isEmpty()) "" else "?$query"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                return out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGif(item: JSONObject): KlipyGif? {
        if (item.optString("type", "gif") != "gif") return null // skip ads and other content
        val slug = item.optString("slug").takeIf { it.isNotEmpty() } ?: return null
        val file = item.optJSONObject("file") ?: return null
        val preview = media(file, "sm", "webp") ?: media(file, "sm", "gif")
            ?: media(file, "xs", "gif") ?: media(file, "md", "gif") ?: return null
        val full = media(file, "md", "gif") ?: media(file, "sm", "gif") ?: media(file, "hd", "gif") ?: return null
        return KlipyGif(slug, item.optString("title"), preview, full)
    }

    private fun media(file: JSONObject, quality: String, format: String): KlipyGif.Media? {
        val m = file.optJSONObject(quality)?.optJSONObject(format) ?: return null
        val url = m.optString("url").takeIf { it.startsWith("https://") } ?: return null
        val w = m.optInt("width", 0)
        val h = m.optInt("height", 0)
        if (w <= 0 || h <= 0) return null
        return KlipyGif.Media(url, w, h)
    }
}
