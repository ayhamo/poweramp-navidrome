package com.ayham.navidrome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val configured: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val serverVersion: String = "",
    val trustAllCerts: Boolean = false,
    val lastSync: Long = 0,
    val counts: LibraryCounts = LibraryCounts(0, 0, 0, 0, 0, 0),
    val albums: List<AlbumSummary> = emptyList(),
    val removed: List<SongRow> = emptyList(),
    val allOffline: Boolean = false,
    val prefetch: Prefetch = Prefetch.NEXT,
    val cacheLimitMb: Long = 0,
    val useAlbumSuffix: Boolean = false,
    val albumSuffix: String = "",
    val scrobble: Boolean = true,
    val pendingScrobbles: Int = 0,
    val ratings: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
    val streamQuality: StreamQuality = StreamQuality.ORIGINAL,
    val lowerBitrate: Int = 128,
    val connecting: Boolean = false,
    val connectError: String? = null,
    val toast: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val s = App.services(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    val work = s.status

    init {
        // Library or cache changed (sync, finished download, clear), or a job reported progress.
        viewModelScope.launch { s.changes.collect { refresh() } }
        viewModelScope.launch { s.status.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val st = s.settings
                UiState(
                    configured = st.isConfigured,
                    serverUrl = st.serverUrl,
                    username = st.username,
                    serverVersion = st.serverVersion,
                    trustAllCerts = st.trustAllCerts,
                    lastSync = st.lastSync,
                    counts = s.db.counts(),
                    albums = s.db.albumSummaries(),
                    removed = s.db.removedSongs(),
                    allOffline = ("all" to "") in s.db.pins(),
                    prefetch = st.prefetch,
                    cacheLimitMb = st.cacheLimitMb,
                    useAlbumSuffix = st.useAlbumSuffix,
                    albumSuffix = st.albumSuffix,
                    scrobble = s.scrobbler.enabled,
                    pendingScrobbles = s.db.pendingScrobbles().size,
                    ratings = s.scrobbler.ratingsEnabled,
                    wifiOnlyDownloads = st.wifiOnlyDownloads,
                    streamQuality = st.streamQuality,
                    lowerBitrate = st.lowerBitrate,
                )
            }
            // Refreshes overlap; only the library and settings fields come from here, so a toast or
            // connect state set meanwhile is not overwritten with an older copy.
            _state.update { cur -> loaded.copy(connecting = cur.connecting, connectError = cur.connectError, toast = cur.toast) }
        }
    }

    /** Checks the login with a ping before saving anything, then starts the first sync. */
    fun connect(url: String, user: String, password: String, trustAll: Boolean, onConnected: () -> Unit) {
        _state.update { it.copy(connecting = true, connectError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (salt, token) = SubsonicClient.makeToken(password)
                    val info = SubsonicClient(url, user.trim(), salt, token, trustAll).ping()
                    s.settings.serverUrl = SubsonicClient.normalizeUrl(url)
                    s.settings.username = user.trim()
                    s.settings.salt = salt
                    s.settings.token = token
                    s.settings.trustAllCerts = trustAll
                    s.settings.serverVersion = info.label
                }
            }
            _state.update { it.copy(connecting = false, connectError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }) }
            if (result.isSuccess) {
                refresh()
                onConnected()
            }
        }
    }

    fun setScrobble(on: Boolean) {
        s.scrobbler.enabled = on
        refresh()
    }

    fun setRatings(on: Boolean) {
        s.scrobbler.ratingsEnabled = on
        refresh()
    }

    fun setWifiOnly(on: Boolean) {
        s.settings.wifiOnlyDownloads = on
        refresh()
    }

    fun setStreamQuality(q: StreamQuality) {
        s.settings.streamQuality = q
        refresh()
    }

    fun setLowerBitrate(kbps: Int) {
        s.settings.lowerBitrate = kbps
        refresh()
    }

    /** True once after a sync finished: time to ask Poweramp to rescan. */
    fun takeRescanPending(): Boolean = s.rescanPending.getAndSet(false)

    /** Sends plays that waited while offline. */
    fun flushScrobbles() = io { if (s.settings.isConfigured) s.scrobbler.flush() }

    fun setTrustAll(on: Boolean) {
        s.settings.trustAllCerts = on
        refresh()
    }

    fun logout() {
        s.settings.logout()
        refresh()
    }

    fun setAlbumPinned(albumId: String, pinned: Boolean, startDownloads: () -> Unit) =
        io(after = { if (pinned) startDownloads() }) { s.db.setPin("album", albumId, pinned) }

    fun setAllOffline(on: Boolean, startDownloads: () -> Unit) =
        io(after = { if (on) startDownloads() }) { s.db.setPin("all", "", on) }

    fun clearCache(keepOffline: Boolean, onDone: () -> Unit) = io(after = onDone) {
        val freed = s.cache.clear(keepOffline)
        s.sync.notifyChanged()
        _state.update { it.copy(toast = "Freed ${formatBytes(freed)}") }
    }

    fun setPrefetch(p: Prefetch) {
        s.settings.prefetch = p
        refresh()
    }

    fun setCacheLimit(mb: Long) = io {
        s.settings.cacheLimitMb = mb.coerceAtLeast(0)
        s.cache.enforceLimit()
    }

    /** The suffix changes every album name, so every song is marked modified for Poweramp's rescan. */
    fun setAlbumSuffix(on: Boolean, suffix: String, onChanged: () -> Unit) {
        val st = s.settings
        if (st.useAlbumSuffix == on && st.albumSuffix == suffix) return
        st.useAlbumSuffix = on
        st.albumSuffix = suffix
        io(after = onChanged) {
            s.db.touchAllSongs()
            s.sync.notifyChanged()
        }
    }

    fun toastShown() {
        _state.update { it.copy(toast = null) }
    }

    /** Runs [block] off the main thread, then [after] on it (callbacks there may start activities). */
    private fun io(after: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            after()
            refresh()
        }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}
