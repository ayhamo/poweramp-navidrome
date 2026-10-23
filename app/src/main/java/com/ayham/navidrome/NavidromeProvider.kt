package com.ayham.navidrome

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import com.maxmpz.poweramp.player.TrackProviderConsts
import java.io.FileNotFoundException

/**
 * Poweramp track provider. Tree: Navidrome → artist → album → songs, plus Navidrome → Playlists →
 * one .m3u8 per Navidrome playlist and Radio.m3u8 for the internet radio stations.
 *
 * Document ids: "root", "ar:<artistId>", "al:<albumId>", "tr:<songId>", "pls", "pl:<playlistId>",
 * "radio". Navidrome keeps song ids when a file is retagged, so Poweramp's play counts and playlists
 * survive tag changes.
 *
 * Every query is answered from the local database; only covers, lyrics and audio touch the network.
 */
class NavidromeProvider : DocumentsProvider() {

    private val s: Services get() = App.services(context!!)

    override fun onCreate(): Boolean = true

    // ---------------------------------------------------------------- roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_PROJECTION)
        if (!s.settings.isConfigured) return c
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "navidrome")
            add(Root.COLUMN_TITLE, context!!.getString(R.string.root_title))
            add(Root.COLUMN_SUMMARY, s.settings.serverVersion.ifBlank { s.settings.serverUrl })
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
        }
        return c
    }

    // ---------------------------------------------------------------- documents

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        try {
            when {
                documentId == ROOT_DOC -> {
                    val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
                    folderRow(c, ROOT_DOC, context!!.getString(R.string.root_title), s.db.rootModified(), false, TrackProviderConsts.FLAG_HAS_SUBDIRS)
                    return c
                }
                documentId.startsWith(ARTIST) -> {
                    val f = s.db.artistFolder(documentId.removePrefix(ARTIST)) ?: throw FileNotFoundException(documentId)
                    val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
                    folderRow(c, documentId, f.name, f.modified, true, TrackProviderConsts.FLAG_HAS_SUBDIRS)
                    return c
                }
                documentId.startsWith(ALBUM) -> {
                    val (f, _) = s.db.albumFolder(documentId.removePrefix(ALBUM)) ?: throw FileNotFoundException(documentId)
                    val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
                    folderRow(c, documentId, albumName(f.name), f.modified, true, TrackProviderConsts.FLAG_NO_SUBDIRS)
                    return c
                }
                documentId.startsWith(TRACK) -> {
                    val row = s.db.song(documentId.removePrefix(TRACK)) ?: throw FileNotFoundException(documentId)
                    val proj = projection ?: TRACK_PROJECTION
                    val c = MatrixCursor(proj)
                    trackRow(c, row, proj)
                    return c
                }
                documentId == PLAYLISTS_DIR -> {
                    val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
                    folderRow(c, PLAYLISTS_DIR, PLAYLISTS_NAME, playlistsModified(), false, TrackProviderConsts.FLAG_NO_SUBDIRS)
                    return c
                }
                documentId.startsWith(PLAYLIST) -> {
                    val p = s.db.playlist(documentId.removePrefix(PLAYLIST)) ?: throw FileNotFoundException(documentId)
                    val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
                    playlistRow(c, documentId, p.name, playlistModified(p))
                    return c
                }
                documentId == RADIO_DOC -> {
                    val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
                    playlistRow(c, RADIO_DOC, RADIO_NAME, radioModified())
                    return c
                }
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "queryDocument $documentId", e)
        }
        throw FileNotFoundException(documentId)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        // Poweramp's hierarchy scan asks for DURATION but not TITLE; tags come later from queryDocument,
        // only for tracks whose modified time moved. Poweramp also resets the title of every other
        // track of a re-listed album, whatever this cursor holds (leaving DURATION out makes it drop
        // the tracks), so LibraryDb moves the modified time of a whole album when any song in it changes.
        val proj = projection ?: TRACK_PROJECTION
        val c = MatrixCursor(proj)
        try {
            when {
                parentDocumentId == ROOT_DOC -> {
                    if (hasPlaylists()) {
                        folderRow(c, PLAYLISTS_DIR, PLAYLISTS_NAME, playlistsModified(), false, TrackProviderConsts.FLAG_NO_SUBDIRS)
                    }
                    for (f in s.db.artistFolders()) folderRow(c, ARTIST + f.id, f.name, f.modified, true, TrackProviderConsts.FLAG_HAS_SUBDIRS)
                }
                parentDocumentId == PLAYLISTS_DIR -> {
                    for (p in s.db.playlists()) playlistRow(c, PLAYLIST + p.id, p.name, playlistModified(p))
                    if (s.db.radios().isNotEmpty()) playlistRow(c, RADIO_DOC, RADIO_NAME, radioModified())
                }
                parentDocumentId.startsWith(ARTIST) ->
                    for (f in s.db.albumFolders(parentDocumentId.removePrefix(ARTIST)))
                        folderRow(c, ALBUM + f.id, albumName(f.name), f.modified, true, TrackProviderConsts.FLAG_NO_SUBDIRS)
                parentDocumentId.startsWith(ALBUM) ->
                    for (row in s.db.albumSongs(parentDocumentId.removePrefix(ALBUM))) trackRow(c, row, proj)
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryChildDocuments $parentDocumentId", e)
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        when {
            parentDocumentId == documentId -> true
            parentDocumentId == ROOT_DOC -> documentId.startsWith(ARTIST) || documentId.startsWith(ALBUM) ||
                documentId.startsWith(TRACK) || documentId == PLAYLISTS_DIR || documentId.startsWith(PLAYLIST) || documentId == RADIO_DOC
            parentDocumentId == PLAYLISTS_DIR -> documentId.startsWith(PLAYLIST) || documentId == RADIO_DOC
            parentDocumentId.startsWith(ARTIST) -> {
                val artistId = parentDocumentId.removePrefix(ARTIST)
                when {
                    documentId.startsWith(ALBUM) -> s.db.albumFolder(documentId.removePrefix(ALBUM))?.second == artistId
                    documentId.startsWith(TRACK) -> {
                        val albumId = s.db.song(documentId.removePrefix(TRACK))?.song?.albumId
                        albumId != null && s.db.albumFolder(albumId)?.second == artistId
                    }
                    else -> false
                }
            }
            parentDocumentId.startsWith(ALBUM) ->
                documentId.startsWith(TRACK) && s.db.song(documentId.removePrefix(TRACK))?.song?.albumId == parentDocumentId.removePrefix(ALBUM)
            else -> false
        }
    } catch (e: Exception) {
        Log.e(TAG, "isChildDocument $parentDocumentId $documentId", e)
        false
    }

    private fun folderRow(c: MatrixCursor, id: String, name: String, modified: Long, thumbnail: Boolean, extraFlags: Int) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_DISPLAY_NAME, name.ifBlank { "Unknown" })
            add(Document.COLUMN_LAST_MODIFIED, modified)
            add(Document.COLUMN_FLAGS, if (thumbnail) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
            add(TrackProviderConsts.COLUMN_FLAGS, extraFlags)
        }
    }

    private fun playlistRow(c: MatrixCursor, id: String, name: String, modified: Long) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_MIME_TYPE, "audio/mpegurl")
            add(Document.COLUMN_DISPLAY_NAME, name.replace('/', '_') + ".m3u8")
            add(Document.COLUMN_LAST_MODIFIED, modified)
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun hasPlaylists() = s.db.playlists().isNotEmpty() || s.db.radios().isNotEmpty()

    /** Moves when the playlist changed on the server or any of its songs changed (e.g. album suffix). */
    // The app update time is a floor: an update that changes how playlists are written makes Poweramp re-read them.
    private val appUpdated: Long by lazy { context!!.packageManager.getPackageInfo(context!!.packageName, 0).lastUpdateTime }

    private fun playlistModified(p: LibraryDb.PlaylistRow): Long =
        maxOf(p.modified, appUpdated, s.db.playlistSongs(p.id).maxOfOrNull { it.modified } ?: 0)

    private fun radioModified(): Long = maxOf(s.db.radiosModified(), appUpdated)

    private fun playlistsModified(): Long =
        maxOf(s.db.playlists().maxOfOrNull { playlistModified(it) } ?: 0, s.db.rootModified(), radioModified())

    /**
     * Poweramp keeps each provider track's path as "@authority/root/<encoded track doc id>" (its
     * folder_files.file_path) and resolves a playlist entry by that path, relative to the playlist
     * ("@authority/root/pl%3A<id>"). So an entry is just "tr%3A<songId>". The readme's
     * "folder name/file name" form and "al%3A<albumId>/<file name>" both stay unresolved
     * (checked in folders.db, build 1031). Radio entries are plain stream URLs.
     */
    private fun playlistText(documentId: String): String = buildString {
        appendLine("#EXTM3U")
        if (documentId == RADIO_DOC) {
            for (r in s.db.radios()) {
                appendLine("#EXTINF:-1," + r.name)
                appendLine(r.url)
            }
        } else {
            for (row in s.db.playlistSongs(documentId.removePrefix(PLAYLIST))) {
                val song = row.song
                appendLine("#EXTINF:" + song.durationMs / 1000 + "," + song.artist + " - " + song.title)
                appendLine(Uri.encode(TRACK + song.id))
            }
        }
    }

    private fun trackRow(c: MatrixCursor, row: SongRow, projection: Array<out String>) {
        val song = row.song
        val r = c.newRow()
        r.add(Document.COLUMN_DOCUMENT_ID, TRACK + song.id)
        r.add(Document.COLUMN_MIME_TYPE, mimeType(song))
        r.add(Document.COLUMN_DISPLAY_NAME, displayName(song))
        r.add(Document.COLUMN_LAST_MODIFIED, row.modified)
        r.add(Document.COLUMN_SIZE, song.size)
        r.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
        // Disc-aware order inside the album folder.
        r.add(TrackProviderConsts.COLUMN_TRACK_ALT, song.disc.coerceAtLeast(1) * 1000 + song.track)

        // With TITLE and DURATION present Poweramp takes our tags instead of reading the file.
        r.add(MediaStore.MediaColumns.TITLE, song.title)
        r.add(MediaStore.Audio.AudioColumns.DURATION, song.durationMs)
        r.add(MediaStore.Audio.AudioColumns.ARTIST, song.artist)
        r.add(MediaStore.Audio.AudioColumns.ALBUM, albumName(song.album))
        if (song.year > 0) r.add(MediaStore.Audio.AudioColumns.YEAR, song.year)
        r.add(TrackProviderConsts.COLUMN_ALBUM_ARTIST, song.albumArtist.ifBlank { song.artist })
        if (song.genre.isNotBlank()) r.add(TrackProviderConsts.COLUMN_GENRE, song.genre)
        if (song.track > 0) r.add(MediaStore.Audio.AudioColumns.TRACK, song.track)
        if (song.bitDepth > 0) r.add(TrackProviderConsts.COLUMN_BITS_PER_SAMPLE, song.bitDepth)
        // Empty wave: otherwise Poweramp reads every file during the scan to draw one.
        if (TrackProviderConsts.COLUMN_TRACK_WAVE in projection) r.add(TrackProviderConsts.COLUMN_TRACK_WAVE, ByteArray(0))
        r.add(TrackProviderConsts.COLUMN_FLAGS, if (row.hasLyrics != 0) TrackProviderConsts.FLAG_HAS_LYRICS else 0)

        // Poweramp asks for lyrics only when its lyrics or info screen is open.
        val wantsLyrics = TrackProviderConsts.COLUMN_TRACK_LYRICS in projection ||
            TrackProviderConsts.COLUMN_TRACK_LYRICS_SYNCED in projection
        if (wantsLyrics) {
            val lyrics = s.lyrics.get(row)
            if (lyrics != null) {
                r.add(TrackProviderConsts.COLUMN_TRACK_LYRICS_SYNCED, lyrics.synced)
                r.add(TrackProviderConsts.COLUMN_TRACK_LYRICS, lyrics.plain ?: lyrics.synced)
            }
        }
    }

    private fun albumName(name: String): String =
        if (s.settings.useAlbumSuffix) name + s.settings.albumSuffix else name

    private fun displayName(song: Song): String {
        val num = if (song.track > 0) "%02d - ".format(song.track) else ""
        val ext = song.suffix.ifBlank { "audio" }
        return (num + song.title).replace('/', '_') + "." + ext
    }

    private fun mimeType(song: Song): String = song.contentType.ifBlank {
        when (song.suffix.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "opus", "ogg", "oga" -> "audio/ogg"
            "m4a", "mp4", "aac", "alac" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "audio/*"
        }
    }

    // ---------------------------------------------------------------- files

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (ParcelFileDescriptor.parseMode(mode) and ParcelFileDescriptor.MODE_WRITE_ONLY != 0) {
            throw UnsupportedOperationException("Read only")
        }
        signal?.throwIfCanceled()
        if (documentId.startsWith(PLAYLIST) || documentId == RADIO_DOC) {
            // One file per open, unlinked once open, so parallel scan threads never read a half-written copy.
            val dir = java.io.File(context!!.cacheDir, "playlists").apply { mkdirs() }
            val file = java.io.File.createTempFile(CacheManager.safeName(documentId), ".m3u8", dir)
            try {
                file.writeText(playlistText(documentId))
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                file.delete()
            }
        }
        if (!documentId.startsWith(TRACK)) throw FileNotFoundException(documentId)
        return s.cache.open(documentId.removePrefix(TRACK))
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        val coverIds = when {
            // The album cover is the fallback, e.g. offline when only the album's cover is cached.
            documentId.startsWith(TRACK) -> s.db.song(documentId.removePrefix(TRACK))?.song
                ?.let { listOfNotNull(it.coverArt, s.db.albumCoverArt(it.albumId)).distinct() }.orEmpty()
            documentId.startsWith(ALBUM) -> listOfNotNull(s.db.albumCoverArt(documentId.removePrefix(ALBUM)))
            documentId.startsWith(ARTIST) -> listOfNotNull(s.db.artistCoverArt(documentId.removePrefix(ARTIST)))
            else -> emptyList()
        }
        val file = coverIds.firstNotNullOfOrNull { s.covers.cached(it) }
            ?: coverIds.firstNotNullOfOrNull { s.covers.file(it) }
            ?: throw FileNotFoundException(documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    // ---------------------------------------------------------------- Poweramp calls

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val res = super.call(method, arg, extras)
        if (res != null) return res
        return when (method) {
            // Poweramp waits on this, so answer at once; the library is synced from the app.
            TrackProviderConsts.CALL_RESCAN -> Bundle()
            TrackProviderConsts.CALL_GET_DIR_METADATA -> Bundle()
            else -> null
        }
    }

    companion object {
        private const val TAG = "NavidromeProvider"
        const val ROOT_DOC = "root"
        const val ARTIST = "ar:"
        const val ALBUM = "al:"
        const val TRACK = "tr:"
        const val PLAYLISTS_DIR = "pls"
        const val PLAYLIST = "pl:"
        const val RADIO_DOC = "radio"
        private const val PLAYLISTS_NAME = "Playlists"
        private const val RADIO_NAME = "Radio"

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_DOCUMENT_ID,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
            TrackProviderConsts.COLUMN_FLAGS,
        )

        private val TRACK_PROJECTION = DOCUMENT_PROJECTION + arrayOf(
            MediaStore.MediaColumns.TITLE, MediaStore.Audio.AudioColumns.DURATION, MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM, MediaStore.Audio.AudioColumns.YEAR, TrackProviderConsts.COLUMN_ALBUM_ARTIST,
            TrackProviderConsts.COLUMN_GENRE, MediaStore.Audio.AudioColumns.TRACK, TrackProviderConsts.COLUMN_TRACK_ALT,
            TrackProviderConsts.COLUMN_BITS_PER_SAMPLE,
        )
    }
}
