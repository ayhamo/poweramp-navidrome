package com.ayham.navidrome

import android.content.Context
import androidx.core.content.edit

/**
 * Saved in app-private prefs. The password itself is never stored: only the Subsonic salt and
 * md5(password + salt) token, which is all the API needs.
 */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("serverUrl", "")!!
        set(v) = p.edit { putString("serverUrl", v) }
    var username: String
        get() = p.getString("username", "")!!
        set(v) = p.edit { putString("username", v) }
    var salt: String
        get() = p.getString("salt", "")!!
        set(v) = p.edit { putString("salt", v) }
    var token: String
        get() = p.getString("token", "")!!
        set(v) = p.edit { putString("token", v) }
    var trustAllCerts: Boolean
        get() = p.getBoolean("trustAllCerts", false)
        set(v) = p.edit { putBoolean("trustAllCerts", v) }

    /** Server line shown on the main card, e.g. "navidrome 0.64.2". */
    var serverVersion: String
        get() = p.getString("serverVersion", "")!!
        set(v) = p.edit { putString("serverVersion", v) }
    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) = p.edit { putLong("lastSync", v) }

    /** Adds [albumSuffix] to album names so Navidrome albums never merge with local copies. */
    var useAlbumSuffix: Boolean
        get() = p.getBoolean("useAlbumSuffix", false)
        set(v) = p.edit { putBoolean("useAlbumSuffix", v) }
    var albumSuffix: String
        get() = p.getString("albumSuffix", " · ND")!!
        set(v) = p.edit { putString("albumSuffix", v) }

    /** 0 = never evict automatically (the default the user asked for). */
    var cacheLimitMb: Long
        get() = p.getLong("cacheLimitMb", 0)
        set(v) = p.edit { putLong("cacheLimitMb", v) }

    var prefetch: Prefetch
        get() = Prefetch.entries.getOrElse(p.getInt("prefetch", Prefetch.NEXT.ordinal)) { Prefetch.NEXT }
        set(v) = p.edit { putInt("prefetch", v.ordinal) }

    /** Offline downloads wait for Wi-Fi (an unmetered network). */
    var wifiOnlyDownloads: Boolean
        get() = p.getBoolean("wifiOnlyDownloads", true)
        set(v) = p.edit { putBoolean("wifiOnlyDownloads", v) }

    var streamQuality: StreamQuality
        get() = StreamQuality.entries.getOrElse(p.getInt("streamQuality", 0)) { StreamQuality.ORIGINAL }
        set(v) = p.edit { putInt("streamQuality", v.ordinal) }

    /** Opus bitrate in kbps for [StreamQuality.LOWER_ON_MOBILE] / [StreamQuality.LOWER_ALWAYS]. */
    var lowerBitrate: Int
        get() = p.getInt("lowerBitrate", 128)
        set(v) = p.edit { putInt("lowerBitrate", v) }

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    fun logout() = p.edit {
        remove("token"); remove("salt"); remove("serverVersion"); remove("lastSync")
    }
}

/**
 * Quality of songs that are not on the phone yet. Lower quality is a temporary Opus copy: it is never
 * kept in the cache, so the cache only ever holds the original files.
 */
enum class StreamQuality(val label: String) {
    ORIGINAL("Original"),
    LOWER_ON_MOBILE("Lower on mobile data"),
    LOWER_ALWAYS("Lower always"),
}

/** What to download ahead when a song starts, so crossfade never waits on the network. */
enum class Prefetch(val label: String) {
    OFF("Off"),
    NEXT("Next song"),
    ALBUM("Rest of the album"),
}
