package com.ayham.navidrome

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The server answered and refused (a Subsonic error such as "not found"); retrying won't help. HTTP and network errors stay plain IOExceptions. */
class SubsonicException(message: String) : IOException(message)

data class ServerInfo(val type: String, val serverVersion: String, val apiVersion: String) {
    val label: String get() = listOf(type, serverVersion).filter { it.isNotBlank() }.joinToString(" ")
}

data class Artist(val id: String, val name: String, val coverArt: String?)

data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String,
    val coverArt: String?,
    val year: Int,
)

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val albumArtist: String,
    val track: Int,
    val disc: Int,
    val year: Int,
    val genre: String,
    val durationMs: Long,
    val size: Long,
    val suffix: String,
    val contentType: String,
    val bitRate: Int,
    val bitDepth: Int,
    val coverArt: String?,
) {
    /** Changes when anything Poweramp shows changes; used to bump LAST_MODIFIED. */
    val metaHash: String
        get() = listOf(title, artist, album, albumArtist, track, disc, year, genre, durationMs, size, suffix, coverArt)
            .joinToString("\u0001").hashCode().toString(16)
}

data class Playlist(val id: String, val name: String, val changed: String, val songIds: List<String>)

data class Radio(val id: String, val name: String, val url: String)

data class Lyrics(val synced: String?, val plain: String?) {
    val isEmpty: Boolean get() = synced.isNullOrBlank() && plain.isNullOrBlank()
}

/**
 * Minimal Subsonic / OpenSubsonic client with token auth (u, t = md5(password + s), s).
 * Every call is blocking; run it off the main thread.
 */
class SubsonicClient(
    serverUrl: String,
    private val user: String,
    private val salt: String,
    private val token: String,
    trustAll: Boolean,
) {
    private val base: HttpUrl = normalizeUrl(serverUrl).toHttpUrl()

    val http: OkHttpClient = httpClient(trustAll)

    fun url(method: String, vararg params: Pair<String, Any>): HttpUrl =
        base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(method)
            .addQueryParameter("u", user)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v.toString()) } }
            .build()

    private fun call(method: String, vararg params: Pair<String, Any>): JSONObject {
        val req = Request.Builder().url(url(method, *params)).build()
        http.newCall(req).execute().use { resp ->
            // A proxy's 502 while Navidrome restarts is temporary: not a SubsonicException.
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $method")
            val body = resp.body?.string() ?: throw IOException("Empty response for $method")
            return checkOk(JSONObject(body).getJSONObject("subsonic-response"), method)
        }
    }

    private fun checkOk(root: JSONObject, method: String): JSONObject {
        if (root.optString("status") != "ok") {
            val err = root.optJSONObject("error")
            throw SubsonicException(err?.optString("message") ?: "Server error in $method")
        }
        return root
    }

    /**
     * Subsonic reports errors (song gone, bad token) as HTTP 200 with a JSON or XML body. For audio
     * that body must never be taken for the file: throw instead and close the response.
     */
    private fun audio(resp: Response, what: String): Response {
        val type = resp.header("Content-Type").orEmpty()
        if (resp.isSuccessful && (type.contains("json") || type.contains("xml"))) {
            val body = resp.use { it.body?.string().orEmpty() }
            runCatching { JSONObject(body).getJSONObject("subsonic-response") }.getOrNull()?.let { checkOk(it, what) }
            throw SubsonicException("Server sent $type instead of audio for $what")
        }
        return resp
    }

    fun ping(): ServerInfo {
        val r = call("ping")
        return ServerInfo(
            type = r.optString("type", "subsonic"),
            serverVersion = r.optString("serverVersion", ""),
            apiVersion = r.optString("version", ""),
        )
    }

    fun artists(): List<Artist> {
        val out = ArrayList<Artist>()
        val indexes = call("getArtists").optJSONObject("artists")?.optJSONArray("index") ?: return out
        for (i in 0 until indexes.length()) {
            val list = indexes.getJSONObject(i).optJSONArray("artist") ?: continue
            for (j in 0 until list.length()) {
                val a = list.getJSONObject(j)
                out += Artist(a.getString("id"), a.optString("name"), a.coverArtId())
            }
        }
        return out
    }

    fun albums(): List<Album> {
        val out = ArrayList<Album>()
        var offset = 0
        while (true) {
            val page = call("getAlbumList2", "type" to "alphabeticalByName", "size" to PAGE, "offset" to offset)
                .optJSONObject("albumList2")?.optJSONArray("album") ?: break
            for (i in 0 until page.length()) {
                val a = page.getJSONObject(i)
                out += Album(
                    id = a.getString("id"),
                    name = a.optString("name", a.optString("album")),
                    artist = a.optString("artist"),
                    artistId = a.optString("artistId"),
                    coverArt = a.coverArtId(),
                    year = a.optInt("year"),
                )
            }
            if (page.length() < PAGE) break
            offset += PAGE
        }
        return out
    }

    /** Every song, via search3 with an empty query (Navidrome returns the whole library, paged). */
    fun songs(onProgress: (Int) -> Unit = {}): List<Song> {
        val out = ArrayList<Song>()
        var offset = 0
        while (true) {
            val page = call(
                "search3", "query" to "", "artistCount" to 0, "albumCount" to 0,
                "songCount" to PAGE, "songOffset" to offset,
            ).optJSONObject("searchResult3")?.optJSONArray("song") ?: break
            for (i in 0 until page.length()) out += parseSong(page.getJSONObject(i))
            onProgress(out.size)
            if (page.length() < PAGE) break
            offset += PAGE
        }
        return out
    }

    private fun parseSong(s: JSONObject) = Song(
        id = s.getString("id"),
        title = s.optString("title"),
        artist = s.optString("artist"),
        album = s.optString("album"),
        albumId = s.optString("albumId"),
        artistId = s.optString("artistId"),
        albumArtist = s.optString("displayAlbumArtist").ifBlank { s.optString("albumArtist") },
        track = s.optInt("track"),
        disc = s.optInt("discNumber"),
        year = s.optInt("year"),
        genre = s.optString("genre"),
        durationMs = s.optLong("duration") * 1000,
        size = s.optLong("size"),
        suffix = s.optString("suffix"),
        contentType = s.optString("contentType"),
        bitRate = s.optInt("bitRate"),
        bitDepth = s.optInt("bitDepth"),
        coverArt = s.coverArtId(),
    )

    /** Every playlist with its song ids in order (one extra request per playlist). */
    fun playlists(): List<Playlist> {
        val list = call("getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val id = p.getString("id")
            val entries = call("getPlaylist", "id" to id).optJSONObject("playlist")?.optJSONArray("entry") ?: JSONArray()
            Playlist(
                id = id,
                name = p.optString("name"),
                changed = p.optString("changed"),
                songIds = (0 until entries.length()).map { entries.getJSONObject(it).getString("id") },
            )
        }
    }

    fun radios(): List<Radio> {
        val list = call("getInternetRadioStations").optJSONObject("internetRadioStations")
            ?.optJSONArray("internetRadioStation") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val r = list.getJSONObject(i)
            Radio(r.getString("id"), r.optString("name"), r.optString("streamUrl"))
        }
    }

    /** Navidrome favourite (heart). */
    fun setStarred(id: String, starred: Boolean) {
        call(if (starred) "star" else "unstar", "id" to id)
    }

    /** 1..5 stars, 0 removes the rating. */
    fun setRating(id: String, rating: Int) {
        call("setRating", "id" to id, "rating" to rating)
    }

    /**
     * [submission] false = "now playing" (shown live in Navidrome), true = a finished play (play count,
     * Last.fm / ListenBrainz forwarding). [time] is when the song started, in ms.
     */
    fun scrobble(id: String, time: Long, submission: Boolean) {
        call("scrobble", "id" to id, "time" to time, "submission" to submission)
    }

    fun coverArt(id: String, size: Int): ByteArray? {
        val req = Request.Builder().url(url("getCoverArt", "id" to id, "size" to size)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type") ?: ""
            if (!type.startsWith("image/")) return null
            return resp.body?.bytes()
        }
    }

    /** A smaller Opus copy made by Navidrome on the fly (for mobile data). Not seekable, size unknown. */
    fun streamTranscoded(id: String, maxBitRate: Int): Response {
        val req = Request.Builder()
            .url(url("stream", "id" to id, "format" to "opus", "maxBitRate" to maxBitRate))
            .build()
        return audio(http.newCall(req).execute(), "stream $id")
    }

    /** The original file (format=raw keeps sizes exact and seeking possible), optionally from [from] bytes. */
    fun stream(id: String, from: Long = 0): Response {
        val req = Request.Builder()
            .url(url("stream", "id" to id, "format" to "raw"))
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        return audio(http.newCall(req).execute(), "stream $id")
    }

    /** Synced lyrics as LRC when the server has them, else plain text. */
    fun lyrics(songId: String, artist: String, title: String): Lyrics {
        try {
            val list = call("getLyricsBySongId", "id" to songId)
                .optJSONObject("lyricsList")?.optJSONArray("structuredLyrics")
            if (list != null && list.length() > 0) {
                var synced: String? = null
                var plain: String? = null
                for (i in 0 until list.length()) {
                    val l = list.getJSONObject(i)
                    val lines = l.optJSONArray("line") ?: JSONArray()
                    if (l.optBoolean("synced") && synced == null) synced = toLrc(lines, l.optLong("offset"))
                    if (!l.optBoolean("synced") && plain == null) plain = toPlain(lines)
                }
                if (synced != null || plain != null) return Lyrics(synced, plain)
            }
        } catch (_: SubsonicException) {
            // Not an OpenSubsonic server: fall back to the classic endpoint below.
        }
        val text = call("getLyrics", "artist" to artist, "title" to title)
            .optJSONObject("lyrics")?.optString("value").orEmpty()
        return Lyrics(null, text.ifBlank { null })
    }

    private fun toLrc(lines: JSONArray, offsetMs: Long): String = buildString {
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            val ms = (l.optLong("start") + offsetMs).coerceAtLeast(0)
            append("[%02d:%02d.%02d]".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10))
            append(l.optString("value")).append('\n')
        }
    }

    private fun toPlain(lines: JSONArray): String =
        (0 until lines.length()).joinToString("\n") { lines.getJSONObject(it).optString("value") }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "PowerampNavidrome"
        private const val PAGE = 500

        fun normalizeUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return "$u/"
        }

        /** Returns (salt, token) for Subsonic token auth. */
        fun makeToken(password: String): Pair<String, String> {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val salt = bytes.joinToString("") { "%02x".format(it) }
            val md5 = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray())
            return salt to md5.joinToString("") { "%02x".format(it) }
        }

        fun httpClient(trustAll: Boolean): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
            if (trustAll) {
                // Only when the user turns on "Accept untrusted HTTPS certificates" (self-signed home servers).
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
                b.sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }
            }
            return b.build()
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

/**
 * Navidrome adds a cache-busting suffix to cover ids ("mf-<id>_<hex>") that changes when the item's
 * annotation changes, e.g. after a play. Without it the id is stable, the server accepts it, and a
 * sync no longer counts played songs as changed or caches the same cover again under a new id.
 */
private fun JSONObject.coverArtId(): String? = optStringOrNull("coverArt")?.replace(COVER_SUFFIX, "")

private val COVER_SUFFIX = Regex("_[0-9a-f]{8,}$")
