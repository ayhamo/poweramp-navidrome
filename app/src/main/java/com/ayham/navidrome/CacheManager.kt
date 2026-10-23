package com.ayham.navidrome

import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import okhttp3.Response
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Song files on the phone, in app-private storage (other apps such as Namida never see them).
 *
 * Poweramp always gets a seekable file descriptor:
 *  - cached song: the real file, so it behaves exactly like local music (crossfade, gapless, seeking);
 *  - not cached yet: a proxy descriptor. A [Download] writes the file from the start while reads are
 *    served from it; a seek far past the downloaded part is answered with its own Range request.
 *
 * Nothing is evicted unless the user sets a size limit. Songs removed from the server keep their file.
 */
class CacheManager(private val s: Services) {

    private val dir = File(s.ctx.filesDir, "music").apply { mkdirs() }
    private val active = ConcurrentHashMap<String, Download>()

    /** Songs Poweramp is about to play. */
    private val urgent: ExecutorService = Executors.newCachedThreadPool()

    /** Prefetch and offline downloads, two at a time. */
    private val background: ExecutorService = Executors.newFixedThreadPool(2)

    fun finalFile(song: Song) = File(dir, safeName(song.id) + "." + song.suffix.ifBlank { "audio" })

    /** Temporary lower-quality copies; never counted as cached, the newest [LOW_KEEP] are kept. */
    private val lowDir = File(s.ctx.cacheDir, "lowq").apply { mkdirs() }
    private val lowActive = ConcurrentHashMap<String, Future<File?>>()

    fun isMetered(): Boolean = s.ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    private fun useLowerQuality(): Boolean = when (s.settings.streamQuality) {
        StreamQuality.ORIGINAL -> false
        StreamQuality.LOWER_ON_MOBILE -> isMetered()
        StreamQuality.LOWER_ALWAYS -> true
    }

    // ---------------------------------------------------------------- Poweramp entry point

    fun open(songId: String): ParcelFileDescriptor {
        val row = s.db.song(songId) ?: throw FileNotFoundException(songId)
        s.db.touchPlayed(songId)
        val entry = s.db.cacheEntry(songId)
        if (entry != null && entry.complete && File(entry.file).isFile) {
            // The server has a newer file: keep playing the old one, swap it in once downloaded.
            if (entry.stale && !row.removed) start(row.song, replace = true, urgent = false)
            prefetchAfter(row.song)
            return ParcelFileDescriptor.open(File(entry.file), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (row.removed) throw FileNotFoundException("$songId is no longer on the server and was not cached")
        if (useLowerQuality()) {
            val low = startLow(row.song, urgent = true)
            prefetchAfter(row.song)
            return openLow(row.song, low)
        }
        val dl = start(row.song, replace = false, urgent = true)
        prefetchAfter(row.song)
        return openProxy(dl)
    }

    // ---------------------------------------------------------------- lower quality

    private fun lowFile(song: Song) = File(lowDir, safeName(song.id) + "_" + s.settings.lowerBitrate + ".opus")

    /**
     * Navidrome's Opus copy has no known size and can't be range-requested, so it is downloaded whole
     * (a few MB, seconds on mobile data) and then handed over as a real, seekable file.
     */
    private fun startLow(song: Song, urgent: Boolean): Future<File?> {
        val file = lowFile(song)
        if (file.isFile && file.length() > 0) return java.util.concurrent.CompletableFuture.completedFuture(file)
        return lowActive.computeIfAbsent(song.id) {
            (if (urgent) this.urgent else background).submit<File?> {
                try {
                    val client = s.client() ?: throw IOException("Not connected")
                    val tmp = File(lowDir, file.name + ".tmp")
                    client.streamTranscoded(song.id, s.settings.lowerBitrate).use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out, 64 * 1024) }
                    }
                    if (!tmp.renameTo(file)) throw IOException("rename failed")
                    pruneLow()
                    file
                } catch (e: Exception) {
                    Log.w(TAG, "lower quality ${song.id} failed: $e")
                    null
                } finally {
                    lowActive.remove(song.id)
                }
            }
        }
    }

    private fun openLow(song: Song, job: Future<File?>): ParcelFileDescriptor {
        val file = try {
            job.get(LOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        } ?: throw FileNotFoundException("Lower quality copy failed for ${song.id}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun pruneLow() {
        val files = lowDir.listFiles { f -> f.name.endsWith(".opus") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(LOW_KEEP).forEach { it.delete() }
    }

    private fun prefetchAfter(song: Song) {
        val count = when (s.settings.prefetch) {
            Prefetch.OFF -> return
            Prefetch.NEXT -> 1
            Prefetch.ALBUM -> Int.MAX_VALUE
        }
        val list = s.db.albumSongs(song.albumId).filter { !it.removed }.map { it.song }
        val idx = list.indexOfFirst { it.id == song.id }
        if (idx < 0) return
        val low = useLowerQuality()
        for ((i, next) in list.drop(idx + 1).take(count).withIndex()) {
            if (isComplete(next.id)) continue
            if (low) startLow(next, urgent = i == 0) else start(next, replace = false, urgent = i == 0)
        }
    }

    private fun isComplete(songId: String): Boolean {
        val e = s.db.cacheEntry(songId) ?: return false
        return e.complete && !e.stale && File(e.file).isFile
    }

    // ---------------------------------------------------------------- downloads

    /** Starts (or joins) the download of [song]. [urgent] downloads skip the background queue. */
    fun start(song: Song, replace: Boolean, urgent: Boolean): Download {
        val dl = synchronized(active) {
            active[song.id]?.takeIf { !it.failed } ?: Download(song, replace).also { active[song.id] = it }
        }
        if (urgent) this.urgent.execute(dl) else background.execute(dl)
        return dl
    }

    /** Offline downloads: blocks until the song is fully on the phone. */
    fun downloadNow(songId: String): Boolean {
        if (isComplete(songId)) return true
        val row = s.db.song(songId) ?: return false
        if (row.removed) return false
        val stale = s.db.cacheEntry(songId)?.stale == true
        val dl = start(row.song, replace = stale, urgent = false)
        dl.awaitDone()
        return !dl.failed
    }

    inner class Download(val song: Song, private val replace: Boolean) : Runnable {
        private val claimed = AtomicBoolean(false)
        private val lock = Object()
        // The expected size is in the name: a part left from an older version of the file is never resumed.
        val part = File(dir, safeName(song.id) + "_" + song.size + if (replace) ".new.part" else ".part")

        @Volatile var total: Long = song.size
        @Volatile var written: Long = 0
        @Volatile var done = false
        @Volatile var failed = false
        /** The server has answered the stream request (so it is reachable and the size is real). */
        @Volatile private var responded = false
        @Volatile private var failures = 0

        init {
            if (!part.exists()) part.createNewFile()
            written = part.length().coerceAtMost(if (total > 0) total else Long.MAX_VALUE)
        }

        override fun run() {
            if (!claimed.compareAndSet(false, true)) return
            var attempt = 0
            while (true) {
                try {
                    transfer()
                    finish()
                    return
                } catch (e: Exception) {
                    attempt++
                    Log.w(TAG, "download ${song.id} attempt $attempt failed: $e")
                    synchronized(lock) {
                        failures = attempt
                        lock.notifyAll()
                    }
                    if (attempt >= 4) {
                        fail()
                        return
                    }
                    Thread.sleep(1000L * attempt)
                }
            }
        }

        private fun transfer() {
            if (total > 0 && written >= total) return
            val client = s.client() ?: throw IOException("Not connected")
            var from = written
            client.stream(song.id, from).use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                if (from > 0 && resp.code != 206) { // server ignored Range: start over
                    from = 0
                    written = 0
                }
                val body = resp.body ?: throw IOException("No body")
                val len = body.contentLength()
                if (from > 0 && len > 0 && from + len != total) {
                    // The file changed on the server since this part started: the old head and new tail
                    // must not be joined. Start over (the retry loop calls transfer() again).
                    synchronized(lock) { written = 0 }
                    RandomAccessFile(part, "rw").use { it.setLength(0) }
                    throw IOException("size of ${song.id} changed during a resumed download, restarting")
                }
                if (len > 0 && from + len != total) {
                    if (total > 0) Log.w(TAG, "size of ${song.id} changed from $total to ${from + len}")
                    total = from + len
                }
                synchronized(lock) { responded = true; lock.notifyAll() }
                RandomAccessFile(part, "rw").use { raf ->
                    if (from == 0L) raf.setLength(0)
                    raf.seek(from)
                    val buf = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        synchronized(lock) {
                            written += n
                            lock.notifyAll()
                        }
                    }
                }
                if (len < 0) { // chunked response: the end of the stream is the size
                    total = written
                }
            }
            if (written < total) throw IOException("Short download: $written of $total")
        }

        private fun finish() {
            val target = finalFile(song)
            val old = s.db.cacheEntry(song.id)?.file?.takeIf { it.isNotEmpty() && it != target.path }
            if (!part.renameTo(target)) throw IOException("rename failed for $target")
            s.db.markComplete(song.id, target.path, total)
            // A replace that changed the format (mp3 -> flac) leaves the old file under another name.
            if (old != null) File(old).delete()
            synchronized(lock) {
                done = true
                lock.notifyAll()
            }
            active.remove(song.id, this)
            enforceLimit()
            s.changed()
            // Covers too, so an offline song still shows its artwork.
            for (id in listOfNotNull(song.coverArt, s.db.albumCoverArt(song.albumId)).distinct()) s.covers.file(id)
        }

        private fun fail() {
            synchronized(lock) {
                failed = true
                lock.notifyAll()
            }
            active.remove(song.id, this)
            // Keep partial data for a resume, but not the empty placeholder of an offline attempt.
            if (part.length() == 0L) part.delete()
        }

        /**
         * True once the server answered. False after two failed attempts (offline) or a timeout, so
         * Poweramp gets an error at once and skips the song instead of stalling on reads.
         */
        fun awaitReady(timeoutMs: Long): Boolean {
            waitUntil(timeoutMs) { responded || failures >= 2 }
            return responded || done
        }

        /** True once bytes up to [end] are on disk. */
        fun awaitBytes(end: Long, timeoutMs: Long): Boolean = waitUntil(timeoutMs) { written >= end }

        fun awaitDone() {
            synchronized(lock) { while (!done && !failed) lock.wait() }
        }

        private fun waitUntil(timeoutMs: Long, ok: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(lock) {
                while (!ok()) {
                    if (failed || done) return ok()
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return false
                    lock.wait(left)
                }
                return true
            }
        }
    }

    // ---------------------------------------------------------------- proxy descriptor

    private fun openProxy(dl: Download): ParcelFileDescriptor {
        if (!dl.awaitReady(OPEN_TIMEOUT_MS)) throw FileNotFoundException("Server not reachable for ${dl.song.id}")
        val finished = finalFile(dl.song)
        if (dl.done && finished.isFile) return ParcelFileDescriptor.open(finished, ParcelFileDescriptor.MODE_READ_ONLY)
        val thread = HandlerThread("pfd-${dl.song.id}").apply { start() }
        val sm = s.ctx.getSystemService(StorageManager::class.java)
        return try {
            sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, ProxyReader(dl, thread), Handler(thread.looper))
        } catch (e: IOException) {
            thread.quitSafely()
            // The download may have finished (and renamed its part file) in the meantime.
            if (finished.isFile && dl.done) return ParcelFileDescriptor.open(finished, ParcelFileDescriptor.MODE_READ_ONLY)
            throw FileNotFoundException(e.message)
        }
    }

    /** Runs on its own HandlerThread, so reads for two songs (crossfade) never block each other. */
    private inner class ProxyReader(private val dl: Download, private val thread: HandlerThread) : ProxyFileDescriptorCallback() {
        // Opened now: the descriptor stays valid after the finished part file is renamed.
        private val local = RandomAccessFile(dl.part, "r")
        private var remote: Response? = null
        private var remoteStream: InputStream? = null
        private var remotePos = -1L

        override fun onGetSize(): Long = dl.total

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            val total = dl.total
            if (offset >= total) return 0
            val n = minOf(size.toLong(), total - offset).toInt()
            val end = offset + n
            try {
                val nearDownload = offset <= dl.written + NEAR_BYTES
                if (dl.written >= end || (nearDownload && dl.awaitBytes(end, READ_TIMEOUT_MS))) {
                    return readLocal(offset, data, n)
                }
                return readRemote(offset, data, n)
            } catch (e: Exception) {
                Log.w(TAG, "read ${dl.song.id} @$offset failed: $e")
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        private fun readLocal(offset: Long, data: ByteArray, n: Int): Int {
            local.seek(offset)
            local.readFully(data, 0, n)
            return n
        }

        /** A seek far ahead of the download: stream that region directly instead of waiting. */
        private fun readRemote(offset: Long, data: ByteArray, n: Int): Int {
            if (remotePos != offset || remoteStream == null) {
                closeRemote()
                val client = s.client() ?: throw IOException("Not connected")
                val resp = client.stream(dl.song.id, offset)
                if (resp.code != 206) {
                    resp.close()
                    throw IOException("Range not supported (HTTP ${resp.code})")
                }
                remote = resp
                remoteStream = resp.body!!.byteStream()
                remotePos = offset
            }
            val input = remoteStream!!
            var read = 0
            while (read < n) {
                val r = input.read(data, read, n - read)
                if (r < 0) break
                read += r
            }
            remotePos += read
            return read
        }

        private fun closeRemote() {
            runCatching { remote?.close() }
            remote = null
            remoteStream = null
            remotePos = -1
        }

        override fun onRelease() {
            runCatching { local.close() }
            closeRemote()
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- housekeeping

    /** Only when the user set a limit: drop the least recently played songs that are not kept offline. */
    fun enforceLimit() {
        val limit = s.settings.cacheLimitMb * 1024 * 1024
        if (limit <= 0) return
        val entries = s.db.allCacheEntries().filter { it.complete }
        var used = entries.sumOf { it.bytes }
        if (used <= limit) return
        for (e in entries.filter { !it.pinned && !active.containsKey(it.songId) }.sortedBy { it.lastPlayed }) {
            if (used <= limit) break
            File(e.file).delete()
            s.db.deleteCacheEntry(e.songId)
            used -= e.bytes
        }
        s.db.dropRemovedUncached()
    }

    /** Deletes cached files. With [keepOffline], songs kept offline stay. Returns bytes freed. */
    fun clear(keepOffline: Boolean): Long {
        var freed = 0L
        for (e in s.db.allCacheEntries()) {
            if (active.containsKey(e.songId)) continue
            if (keepOffline && e.pinned) continue
            val f = File(e.file)
            if (e.complete && f.isFile) freed += f.length()
            f.delete()
            s.db.deleteCacheEntry(e.songId)
        }
        if (!keepOffline) {
            for ((kind, id) in s.db.pins()) s.db.setPin(kind, id, false)
        }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { f ->
            if (active.values.none { it.part == f }) f.delete()
        }
        s.db.dropRemovedUncached()
        return freed
    }

    companion object {
        private const val TAG = "CacheManager"

        /** Reads this close to the download point wait for it instead of opening a second connection. */
        private const val NEAR_BYTES = 2L * 1024 * 1024
        private const val READ_TIMEOUT_MS = 20_000L
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val LOW_TIMEOUT_MS = 30_000L
        private const val LOW_KEEP = 30

        fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
