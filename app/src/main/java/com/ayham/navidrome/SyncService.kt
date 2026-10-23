package com.ayham.navidrome

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service for the long jobs: library sync and offline downloads. Android would kill a
 * background download of a whole library otherwise.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pending = AtomicInteger(0)
    private val s: Services get() = App.services(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground("Starting…")
        } catch (e: Exception) { // e.g. Android refuses a foreground start from the background
            s.status.value = WorkStatus(running = false, error = "Could not start: ${e.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_SYNC
        pending.incrementAndGet()
        scope.launch {
            try {
                mutex.withLock {
                    try {
                        when (action) {
                            ACTION_SYNC -> { sync(); downloadPinned() }
                            ACTION_DOWNLOAD -> downloadPinned()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        s.status.value = WorkStatus(running = false, error = e.message ?: e.toString())
                    }
                }
            } finally {
                // Also on cancellation, so the Sync button never stays disabled.
                if (pending.decrementAndGet() == 0) {
                    if (s.status.value.running) s.status.value = s.status.value.copy(running = false)
                    ServiceCompat.stopForeground(this@SyncService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    // stopSelf(startId) is ignored if a newer command arrived meanwhile.
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits dataSync services to 6 hours a day; past that the service must stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        s.status.value = WorkStatus(running = false, error = "Android paused the downloads (daily limit). Tap Download again later.")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sync() {
        val r = s.sync.run { msg -> report(msg) }
        s.rescanPending.set(true) // the screen asks Poweramp to rescan once it's in front
        s.scrobbler.flush()
        s.sync.prefetchFolderCovers { msg -> report(msg) }
        val parts = buildList {
            add("${r.added} new")
            if (r.changed > 0) add("${r.changed} updated")
            if (r.removedKept > 0) add("${r.removedKept} removed on server (kept, cached)")
            if (r.removedDropped > 0) add("${r.removedDropped} removed")
        }
        s.status.value = WorkStatus(running = true, message = "Synced: " + parts.joinToString(", "))
    }

    private suspend fun downloadPinned() {
        val ids = s.db.refreshPinnedFlags()
        if (ids.isEmpty()) {
            s.status.value = s.status.value.copy(running = false)
            return
        }
        if (s.settings.wifiOnlyDownloads && s.cache.isMetered()) {
            s.status.value = WorkStatus(running = false, message = "${ids.size} offline songs wait for Wi-Fi")
            return
        }
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        report("Downloading 0 / ${ids.size}")
        // CacheManager runs two downloads at a time; feed it in slices of two.
        for (chunk in ids.chunked(2)) {
            chunk.map { id ->
                scope.async {
                    if (!s.cache.downloadNow(id)) failed.incrementAndGet()
                    report("Downloading ${done.incrementAndGet()} / ${ids.size}")
                }
            }.awaitAll()
        }
        s.sync.notifyChanged()
        val msg = "Offline: ${ids.size - failed.get()} downloaded" + if (failed.get() > 0) ", ${failed.get()} failed" else ""
        s.status.value = WorkStatus(running = false, message = msg, error = if (failed.get() > 0) msg else null)
    }

    private fun report(msg: String) {
        s.status.value = WorkStatus(running = true, message = msg)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(msg))
    }

    private fun startInForeground(text: String) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "work"
        private const val NOTIFICATION_ID = 1
        const val ACTION_SYNC = "sync"
        const val ACTION_DOWNLOAD = "download"

        fun start(ctx: Context, action: String) {
            App.services(ctx).status.value = WorkStatus(running = true, message = "Starting…")
            ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java).setAction(action))
        }
    }
}
