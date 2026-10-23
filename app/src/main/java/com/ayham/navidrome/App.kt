package com.ayham.navidrome

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared singletons. Content providers are created before Application.onCreate, so everything is
 * reached through [App.services] instead of being built in onCreate.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SyncService.CHANNEL, "Sync and downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        @Volatile private var instance: Services? = null

        fun services(ctx: Context): Services =
            instance ?: synchronized(this) {
                instance ?: Services(ctx.applicationContext).also { instance = it }
            }
    }
}

/** Progress shown on the main screen while the foreground service works. */
data class WorkStatus(
    val running: Boolean = false,
    val message: String = "",
    val error: String? = null,
)

class Services(val ctx: Context) {
    val settings = Settings(ctx)
    val db = LibraryDb(ctx)
    val cache = CacheManager(this)
    val covers = CoverCache(this)
    val lyrics = LyricsRepo(this)
    val sync = SyncManager(this)
    val scrobbler = Scrobbler(this)
    val status = MutableStateFlow(WorkStatus())

    /** Bumped whenever the library or cache changes, so the screen refreshes on its own. */
    val changes = MutableStateFlow(0L)

    fun changed() = changes.update { it + 1 }

    /** Set after a sync; the main screen sends Poweramp's rescan (Poweramp ignores it from the background). */
    val rescanPending = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        // Opening the database runs pending upgrades; one may need a Poweramp rescan.
        db.writableDatabase
        if (db.resendTags) rescanPending.set(true)
    }

    @Volatile private var clientKey: String? = null
    @Volatile private var cachedClient: SubsonicClient? = null

    /** Client for the saved server, rebuilt when the settings change. Null until a server is set up. */
    fun client(): SubsonicClient? {
        val s = settings
        if (s.serverUrl.isBlank() || s.token.isBlank()) return null
        val key = "${s.serverUrl}|${s.username}|${s.salt}|${s.token}|${s.trustAllCerts}"
        cachedClient?.let { if (clientKey == key) return it }
        return synchronized(this) {
            SubsonicClient(s.serverUrl, s.username, s.salt, s.token, s.trustAllCerts).also {
                cachedClient = it
                clientKey = key
            }
        }
    }

    val authority: String get() = ctx.packageName + ".provider"
}
