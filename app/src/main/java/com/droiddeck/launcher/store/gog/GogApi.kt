package com.droiddeck.launcher.store.gog

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GOG's web APIs, as Heroic uses them: the signed-in account's games (embed.gog.com, with the
 * token gogdl keeps) and a game's art (gamesdb.gog.com, public). Downloads are gogdl's.
 */
object GogApi {
    private const val TAG = "GogApi"
    private const val TIMEOUT_MS = 20_000
    private const val LIBRARY = "https://embed.gog.com/account/getFilteredProducts?mediaType=1&sortBy=title&page="
    private const val GAMESDB = "https://gamesdb.gog.com/platforms/gog/external_releases/"

    /** An owned game; [image] is the store's wide tile. Only games with a Windows build are listed. */
    class Game(val id: String, val title: String, val image: String?)

    class Art(val cover: String?, val header: String?, val hero: String?, val logo: String?)

    /** Every game the account owns that runs on Windows, by title; null when GOG could not be asked. */
    fun library(token: String): List<Game>? {
        val games = ArrayList<Game>()
        var page = 1
        var pages = 1
        while (page <= pages) {
            val body = get(LIBRARY + page, token) ?: return null
            val (items, total) = parseLibraryPage(body) ?: return null
            games += items
            pages = total.coerceAtMost(100)
            page++
        }
        return games.distinctBy { it.id }.sortedBy { it.title.lowercase() }
    }

    /** One page: its games and how many pages there are. */
    internal fun parseLibraryPage(body: String): Pair<List<Game>, Int>? = runCatching {
        val o = JSONObject(body)
        val products = o.optJSONArray("products")
        val games = (0 until (products?.length() ?: 0)).mapNotNull { i ->
            val p = products!!.optJSONObject(i) ?: return@mapNotNull null
            val windows = p.optJSONObject("worksOn")?.optBoolean("Windows") ?: false
            val id = p.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
            if (!windows) return@mapNotNull null
            Game(id, p.optString("title").ifEmpty { id }, image(p.optString("image")))
        }
        games to o.optInt("totalPages", 1).coerceAtLeast(1)
    }.getOrNull()

    /** The store's image ids come protocol-relative and without a size; 392 is its wide tile. */
    internal fun image(raw: String): String? {
        if (raw.isBlank()) return null
        val url = if (raw.startsWith("//")) "https:$raw" else raw
        return if (url.endsWith(".jpg") || url.endsWith(".png")) url else "${url}_392.jpg"
    }

    /** The game's art from GOG's games database, or null when it has none. */
    fun art(id: String): Art? = get(GAMESDB + id, null)?.let(::parseArt)

    internal fun parseArt(body: String): Art? = runCatching {
        val game = JSONObject(body).optJSONObject("game") ?: return@runCatching null
        fun url(key: String, ext: String) = game.optJSONObject(key)?.optString("url_format")
            ?.takeIf { it.isNotEmpty() }?.replace("{formatter}", "")?.replace("{ext}", ext)
        Art(url("vertical_cover", "jpg"), url("horizontal_artwork", "jpg"), url("background", "jpg"), url("logo", "png"))
    }.getOrNull()

    /**
     * Puts the game's art into its folder's .art/ under the names the Added Games art reader looks
     * for first (cover, header, hero, logo), so Steam shows GOG's own art for it. Best effort.
     */
    fun saveArt(id: String, dir: File) {
        val art = art(id) ?: return
        val target = File(dir, ".art").apply { mkdirs() }
        listOf("cover.jpg" to art.cover, "header.jpg" to art.header, "hero.jpg" to art.hero, "logo.png" to art.logo)
            .forEach { (name, url) ->
                val file = File(target, name)
                if (url != null && !file.isFile) download(url, file)
            }
    }

    private fun download(url: String, file: File) {
        var c: HttpURLConnection? = null
        try {
            c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS }
            if (c.responseCode / 100 != 2) return
            val part = File(file.path + ".part")
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            part.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "$url: $e")
        } finally {
            c?.disconnect()
        }
    }

    private fun get(url: String, token: String?): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
            }
            // The URL is logged; the token never is.
            if (c.responseCode / 100 != 2) { Log.w(TAG, "${url.substringBefore('?')} -> HTTP ${c.responseCode}"); null }
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "${url.substringBefore('?')}: ${e.javaClass.simpleName}"); null
        } finally {
            c?.disconnect()
        }
    }
}
