package com.ayham.navidrome

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import java.io.File

/** Album covers, fetched once at Poweramp's preferred 1024 px and kept for offline use. */
class CoverCache(private val s: Services) {
    private val dir = File(s.ctx.filesDir, "covers").apply { mkdirs() }

    /** The cover if it is already on the phone; never touches the network. */
    fun cached(coverArtId: String): File? =
        File(dir, CacheManager.safeName(coverArtId) + ".img").takeIf { it.isFile && it.length() > 0 }

    fun file(coverArtId: String): File? {
        cached(coverArtId)?.let { return it }
        val f = File(dir, CacheManager.safeName(coverArtId) + ".img")
        val bytes = try {
            s.client()?.coverArt(coverArtId, 1024)
        } catch (e: Exception) {
            Log.w("CoverCache", "cover $coverArtId: $e")
            null
        } ?: return null
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        return f
    }
}

/** Lyrics are fetched the first time Poweramp asks for them and then served from the database. */
class LyricsRepo(private val s: Services) {
    fun get(row: SongRow): Lyrics? {
        s.db.lyrics(row.song.id)?.let { return it }
        if (row.hasLyrics == 0) return null
        return try {
            val l = s.client()?.lyrics(row.song.id, row.song.artist, row.song.title) ?: return null
            s.db.saveLyrics(row.song.id, l)
            l
        } catch (e: Exception) {
            Log.w("LyricsRepo", "lyrics ${row.song.id}: $e")
            null
        }
    }
}

class SyncManager(private val s: Services) {
    /** Full library sync. Throws on network or auth errors. */
    fun run(progress: (String) -> Unit): LibraryDb.SyncResult {
        val c = s.client() ?: throw IllegalStateException("No server set up")
        progress("Contacting server…")
        s.settings.serverVersion = c.ping().label
        progress("Loading artists…")
        val artists = c.artists()
        progress("Loading albums…")
        val albums = c.albums()
        val songs = c.songs { n -> progress("Loading songs… $n") }
        progress("Loading playlists…")
        val playlists = c.playlists()
        val radios = runCatching { c.radios() }.getOrDefault(emptyList())
        progress("Saving…")
        val result = s.db.applySync(artists, albums, songs, s.db.completeCacheIds())
        s.db.applyPlaylists(playlists, radios)
        s.settings.lastSync = System.currentTimeMillis()
        notifyChanged()
        return result
    }

    /** Album and artist folder covers, so Poweramp's folder views keep their artwork offline. */
    fun prefetchFolderCovers(progress: (String) -> Unit) {
        val ids = s.db.folderCoverIds()
        ids.forEachIndexed { i, id ->
            if (i % 10 == 0) progress("Covers… $i / ${ids.size}")
            s.covers.file(id)
        }
    }

    /** Tells Poweramp (and the Android file picker) that our folders changed. */
    fun notifyChanged() {
        s.ctx.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(s.authority, NavidromeProvider.ROOT_DOC), null,
        )
        s.changed()
    }
}

/** Calls into Poweramp. Poweramp ignores scan requests from the background, so these run from the UI. */
object PowerampBridge {
    fun isInstalled(a: Activity): Boolean = PowerampAPIHelper.getPowerampPackageName(a) != null

    /**
     * Rescans only our provider ("@authority/"), in fast mode. resolvePlaylists makes Poweramp link
     * playlist entries again, in case a playlist was parsed before its tracks were in the library.
     */
    fun rescan(a: Activity) {
        val pkg = PowerampAPIHelper.getPowerampPackageName(a) ?: return
        val intent = Intent(PowerampAPI.Scanner.ACTION_SCAN_DIRS)
            .putExtra(PowerampAPI.Scanner.EXTRA_CAUSE, a.packageName + " rescan")
            .putExtra(PowerampAPI.Scanner.EXTRA_PATH, "@" + App.services(a).authority + "/")
            .putExtra(PowerampAPI.Scanner.EXTRA_RESOLVE_PLAYLISTS, true)
            .putExtra(PowerampAPI.EXTRA_PACKAGE, a.packageName)
            .setComponent(ComponentName(pkg, PowerampAPI.API_ACTIVITY_NAME))
        runCatching { a.startActivity(intent) }.onFailure { Log.w("PowerampBridge", "rescan", it) }
    }

    /** Opens Poweramp's Music Folders screen, where the user ticks "Navidrome". */
    fun openMusicFolders(a: Activity) {
        val pkg = PowerampAPIHelper.getPowerampPackageName(a) ?: return
        val intent = Intent()
            .setComponent(ComponentName(pkg, PowerampAPI.ACTIVITY_SETTINGS))
            .putExtra(PowerampAPI.Settings.EXTRA_OPEN_PATH, "library/music_folders_button")
            .putExtra(PowerampAPI.Settings.EXTRA_NO_BACKSTACK, true)
            .putExtra(PowerampAPI.EXTRA_PACKAGE, a.packageName)
        runCatching { a.startActivity(intent) }.onFailure { Log.w("PowerampBridge", "music folders", it) }
    }

    fun isIgnoringBatteryOptimizations(a: Activity): Boolean =
        a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName)

    fun requestBatteryExemption(a: Activity) {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName))
        runCatching { a.startActivity(intent) }
            .onFailure { runCatching { a.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }
}
