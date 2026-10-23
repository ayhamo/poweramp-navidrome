package com.ayham.navidrome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import com.maxmpz.poweramp.player.PowerampAPI
import java.util.concurrent.Executors

/**
 * Sends Poweramp plays of Navidrome songs back to Navidrome (which forwards them to Last.fm and
 * ListenBrainz when linked there).
 *
 * Poweramp delivers TRACK_CHANGED_EXPLICIT and STATUS_CHANGED_EXPLICIT to [PowerampEventReceiver]
 * even when this app is not running. The process can die between events, so the state of the
 * current song lives in prefs. A play counts after half the song or 4 minutes of actual playing
 * time (pauses excluded), the Last.fm rule; songs under 30 s never count.
 *
 * Plays go into a queue table first and are sent from there, so offline plays are sent later.
 */
class Scrobbler(private val s: Services) {
    private val p = s.ctx.getSharedPreferences("scrobble", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit { putBoolean("enabled", v) }

    /** Send Poweramp likes and ratings to Navidrome. */
    var ratingsEnabled: Boolean
        get() = p.getBoolean("ratings", true)
        set(v) = p.edit { putBoolean("ratings", v) }

    @Synchronized
    fun onTrackChanged(track: Bundle?, ts: Long) {
        // The receiver is exported (Poweramp sends without a permission), so only songs we know count.
        val songId = track?.getString(PowerampAPI.Track.PATH)?.let { songIdFromPath(it) }?.takeIf { s.db.song(it) != null }
        if (songId != null) track!!.getInt(PowerampAPI.Track.RATING, -1).takeIf { it >= 0 }?.let { syncRating(songId, it) }
        // Poweramp may repeat the event for the same song (e.g. resuming after a stop, or a rating
        // change): keep counting. Unless the song already played through: then it is a replay
        // (repeat one, or the same song again) and counts as a new play.
        if (songId != null && songId == p.getString("songId", null) && !playedThrough(ts)) return
        finishCurrent(ts)
        val durationMs = track?.getInt(PowerampAPI.Track.DURATION_MS)?.toLong()?.takeIf { it > 0 }
            ?: ((track?.getInt(PowerampAPI.Track.DURATION) ?: 0) * 1000L)
        p.edit {
            putString("songId", songId)
            putLong("startedAt", ts)
            putLong("durationMs", durationMs)
            putLong("playedMs", 0)
            putLong("playingSince", ts) // a status event corrects this if Poweramp is paused
        }
        if (songId != null && enabled) {
            Log.i(TAG, "now playing $songId")
            runCatching { s.client()?.scrobble(songId, ts, submission = false) }
                .onFailure { Log.w(TAG, "now playing failed: $it") }
        }
        flush()
    }

    @Synchronized
    fun onStatusChanged(state: Int, paused: Boolean, ts: Long) {
        val since = p.getLong("playingSince", 0)
        if (paused || state != PowerampAPI.STATE_PLAYING) {
            if (since > 0) p.edit {
                putLong("playedMs", p.getLong("playedMs", 0) + (ts - since).coerceAtLeast(0))
                putLong("playingSince", 0)
            }
            if (state == PowerampAPI.STATE_STOPPED) {
                finishCurrent(ts)
                p.edit { remove("songId") }
                flush()
            }
        } else if (since == 0L) {
            p.edit { putLong("playingSince", ts) }
        }
    }

    /**
     * Poweramp: 0 none, 1 unlike, 2-4 stars, 5 like. Navidrome: a heart (favourite) and 1-5 stars.
     * Like = heart + 5 stars; any other value = that many stars and no heart; 0 clears both.
     *
     * Only a change from the rating last seen for the song is sent, never the first sighting: Poweramp
     * starts every Navidrome song at 0, and sending that would wipe hearts set elsewhere.
     */
    private fun syncRating(songId: String, rating: Int) {
        val last = s.db.lastRating(songId)
        if (last == null || !ratingsEnabled) {
            s.db.saveRating(songId, rating)
            return
        }
        if (last == rating) return
        try {
            val client = s.client() ?: return
            client.setRating(songId, rating)
            client.setStarred(songId, rating == PowerampAPI.Track.RATING_LIKE)
            s.db.saveRating(songId, rating) // saved only after success, so an offline change is retried next time
            Log.i(TAG, "rating $songId $last -> $rating")
        } catch (e: Exception) {
            Log.w(TAG, "rating $songId not sent: $e")
        }
    }

    private fun playedMs(ts: Long): Long {
        val since = p.getLong("playingSince", 0)
        return p.getLong("playedMs", 0) + if (since > 0) (ts - since).coerceAtLeast(0) else 0
    }

    private fun playedThrough(ts: Long): Boolean {
        val duration = p.getLong("durationMs", 0)
        return duration > 0 && playedMs(ts) >= duration * 9 / 10
    }

    /** Queues the current song if it was played long enough. */
    private fun finishCurrent(ts: Long) {
        val songId = p.getString("songId", null) ?: return
        val played = playedMs(ts)
        val duration = p.getLong("durationMs", 0)
        p.edit { remove("songId") }
        val needed = minOf(duration / 2, 4 * 60_000L)
        if (!enabled || duration < 30_000 || played < needed) {
            Log.i(TAG, "not counted: $songId played ${played / 1000}s of ${duration / 1000}s")
            return
        }
        s.db.queueScrobble(songId, p.getLong("startedAt", ts))
        Log.i(TAG, "queued $songId (${played / 1000}s of ${duration / 1000}s)")
    }

    /**
     * Sends queued plays, oldest first; stops at the first network error and keeps the rest.
     * Synchronized: two overlapping flushes (app resume + a Poweramp event) would send the same play twice.
     */
    @Synchronized
    fun flush() {
        val client = s.client() ?: return
        for ((rowId, songId, time) in s.db.pendingScrobbles()) {
            try {
                client.scrobble(songId, time, submission = true)
                s.db.deleteScrobble(rowId)
            } catch (e: SubsonicException) {
                Log.w(TAG, "server refused $songId: $e")
                s.db.deleteScrobble(rowId) // e.g. song deleted on the server: retrying won't help
            } catch (e: Exception) {
                Log.w(TAG, "offline, ${s.db.pendingScrobbles().size} plays wait: $e")
                break
            }
        }
        s.changed()
    }

    /** "@<authority>/…/tr%3A<songId>" → songId; null for local files and other providers. */
    private fun songIdFromPath(path: String): String? {
        if (!path.startsWith("@" + s.authority + "/")) return null
        val doc = Uri.decode(path.trimEnd('/').substringAfterLast('/'))
        return doc.takeIf { it.startsWith(NavidromeProvider.TRACK) }?.removePrefix(NavidromeProvider.TRACK)
    }

    companion object {
        private const val TAG = "Scrobbler"
    }
}

/**
 * Receives Poweramp's explicit track and status events, also while this app is not running.
 * The work (network calls with long timeouts) runs on a worker without goAsync(): holding the
 * broadcast open through a slow network risks a broadcast ANR, which would kill this process and
 * with it the provider Poweramp is streaming from. While Poweramp plays, it keeps this process
 * alive through the provider anyway.
 */
class PowerampEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = App.services(context)
        val ts = intent.getLongExtra(PowerampAPI.EXTRA_TIMESTAMP, System.currentTimeMillis())
        worker.execute {
            try {
                when (intent.action) {
                    PowerampAPI.ACTION_TRACK_CHANGED_EXPLICIT ->
                        s.scrobbler.onTrackChanged(intent.getBundleExtra(PowerampAPI.EXTRA_TRACK), ts)
                    PowerampAPI.ACTION_STATUS_CHANGED_EXPLICIT ->
                        s.scrobbler.onStatusChanged(
                            intent.getIntExtra(PowerampAPI.EXTRA_STATE, PowerampAPI.STATE_NO_STATE),
                            intent.getBooleanExtra(PowerampAPI.EXTRA_PAUSED, true),
                            ts,
                        )
                }
            } catch (e: Exception) {
                Log.w("PowerampEventReceiver", "event failed", e)
            }
        }
    }

    companion object {
        /** One thread keeps events in order. */
        private val worker = Executors.newSingleThreadExecutor()
    }
}
