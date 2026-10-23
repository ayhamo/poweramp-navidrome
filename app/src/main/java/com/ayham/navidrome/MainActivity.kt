package com.ayham.navidrome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(vm, Actions(this))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        vm.flushScrobbles()
        if (vm.takeRescanPending()) PowerampBridge.rescan(this) // a sync finished while we were away
    }

    /** UI actions that need the Activity (Poweramp intents, services, permissions). */
    inner class Actions(private val a: MainActivity) {
        val powerampInstalled get() = PowerampBridge.isInstalled(a)
        val batteryExempt get() = PowerampBridge.isIgnoringBatteryOptimizations(a)
        fun sync() = SyncService.start(a, SyncService.ACTION_SYNC)
        fun downloadOffline() = SyncService.start(a, SyncService.ACTION_DOWNLOAD)
        fun rescan() = PowerampBridge.rescan(a)
        fun musicFolders() = PowerampBridge.openMusicFolders(a)
        fun battery() = PowerampBridge.requestBatteryExemption(a)
        fun toast(msg: String) = Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
        fun askNotifications() {
            if (Build.VERSION.SDK_INT >= 33 &&
                a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// Dark Material palette matching the "Navidrome en Poweramp" screenshot.
private val Colors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF0B2A6B),
    background = Color(0xFF11131A),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF11131A),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF262A35),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceContainerHighest = Color(0xFF262A35),
    outline = Color(0xFF8F909A),
    error = Color(0xFFFFB4AB),
)

@Composable
private fun MainScreen(vm: MainViewModel, act: MainActivity.Actions) {
    val st by vm.state.collectAsState()
    val work by vm.work.collectAsState()
    var showAlbums by remember { mutableStateOf(false) }

    LaunchedEffect(st.toast) {
        st.toast?.let { act.toast(it); vm.toastShown() }
    }
    // A sync just finished on screen: let Poweramp pick up the changes without a second tap.
    LaunchedEffect(work.running) {
        if (!work.running && vm.takeRescanPending()) act.rescan()
    }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Navidrome in Poweramp", fontSize = 34.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(10.dp))
            Text(
                "This app publishes your Subsonic library as a music folder. Poweramp plays each song " +
                    "straight from your NAS and keeps what you play on the phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!st.configured) {
            item { LoginCard(st, vm, act) }
        } else {
            item { ConnectedCard(st, work) }
            item {
                SwitchRow("Accept untrusted HTTPS certificates", st.trustAllCerts) { vm.setTrustAll(it) }
                SwitchRow("Send plays to Navidrome (scrobble)", st.scrobble) { vm.setScrobble(it) }
                SwitchRow("Send likes and ratings to Navidrome", st.ratings) { vm.setRatings(it) }
            }
            item {
                PillButton("Sync library", filled = true, enabled = !work.running) { act.askNotifications(); act.sync() }
                Spacer(Modifier.height(10.dp))
                PillButton("Ask Poweramp to rescan") { act.rescan() }
                Spacer(Modifier.height(10.dp))
                if (act.powerampInstalled) {
                    PillButton("Add to Poweramp Music Folders") { act.musicFolders() }
                    Spacer(Modifier.height(10.dp))
                }
                if (!act.batteryExempt) {
                    PillButton("Disable battery optimisation") { act.battery() }
                    Spacer(Modifier.height(10.dp))
                }
                PillButton("Log out") { vm.logout() }
            }
            item { QualityCard(st, vm) }
            item { OfflineCard(st, vm, act, showAlbums) { showAlbums = !showAlbums } }
            if (showAlbums) {
                items(st.albums, key = { it.id }) { album -> AlbumRow(album) { pinned -> vm.setAlbumPinned(album.id, pinned) { act.downloadOffline() } } }
            }
            item { SeparateCard(st, vm, act) }
            if (st.removed.isNotEmpty()) item { RemovedCard(st.removed) }
        }
        item { HowToCard() }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LoginCard(st: UiState, vm: MainViewModel, act: MainActivity.Actions) {
    var url by remember { mutableStateOf(st.serverUrl) }
    var user by remember { mutableStateOf(st.username) }
    var pass by remember { mutableStateOf("") }
    var trust by remember { mutableStateOf(st.trustAllCerts) }
    InfoCard {
        Text("Connect to your server", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server address") },
            placeholder = { Text("http://192.168.0.10:4533") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("User") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        SwitchRow("Accept untrusted HTTPS certificates", trust) { trust = it }
        st.connectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PillButton(if (st.connecting) "Connecting…" else "Connect", filled = true,
            enabled = !st.connecting && url.isNotBlank() && user.isNotBlank() && pass.isNotEmpty()) {
            vm.connect(url, user, pass, trust) { act.askNotifications(); act.sync() }
        }
    }
}

@Composable
private fun ConnectedCard(st: UiState, work: WorkStatus) {
    InfoCard {
        Text("Connected", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
        if (st.serverVersion.isNotBlank()) Text(st.serverVersion, style = MaterialTheme.typography.titleLarge)
        Text(st.serverUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("User: ${st.username}", style = MaterialTheme.typography.titleLarge)
        val c = st.counts
        Text("${c.artists} artists · ${c.albums} albums · ${c.songs} songs", style = MaterialTheme.typography.titleLarge)
        if (c.playlists + c.radios > 0) {
            Text("${c.playlists} playlists · ${c.radios} radio stations", style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            if (st.lastSync > 0) "Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(st.lastSync))
            else "Not synced yet",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (st.pendingScrobbles > 0) {
            Text("${st.pendingScrobbles} plays waiting to be sent", style = MaterialTheme.typography.bodyMedium)
        }
        if (work.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(work.message, style = MaterialTheme.typography.bodyMedium)
        } else {
            work.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ?: work.message.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun QualityCard(st: UiState, vm: MainViewModel) {
    InfoCard {
        Text("Streaming quality", style = MaterialTheme.typography.headlineSmall)
        Text(
            "For songs not on the phone yet. Lower quality is a temporary Opus copy and is never kept: " +
                "the cache always holds the original files.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StreamQuality.entries.forEachIndexed { i, q ->
                SegmentedButton(selected = st.streamQuality == q, onClick = { vm.setStreamQuality(q) },
                    shape = SegmentedButtonDefaults.itemShape(i, StreamQuality.entries.size)) { Text(q.label, fontSize = 13.sp) }
            }
        }
        if (st.streamQuality != StreamQuality.ORIGINAL) {
            val rates = listOf(96, 128, 192)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                rates.forEachIndexed { i, kbps ->
                    SegmentedButton(selected = st.lowerBitrate == kbps, onClick = { vm.setLowerBitrate(kbps) },
                        shape = SegmentedButtonDefaults.itemShape(i, rates.size)) { Text("Opus $kbps kbps", fontSize = 13.sp) }
                }
            }
        }
        SwitchRow("Offline downloads only on Wi-Fi", st.wifiOnlyDownloads) { vm.setWifiOnly(it) }
    }
}

@Composable
private fun OfflineCard(st: UiState, vm: MainViewModel, act: MainActivity.Actions, albumsShown: Boolean, toggleAlbums: () -> Unit) {
    var limit by remember(st.cacheLimitMb) { mutableStateOf(if (st.cacheLimitMb > 0) st.cacheLimitMb.toString() else "") }
    InfoCard {
        Text("Offline and cache", style = MaterialTheme.typography.headlineSmall)
        Text("${st.counts.cachedSongs} songs on the phone · ${formatBytes(st.counts.cachedBytes)}", style = MaterialTheme.typography.bodyLarge)
        Text("Download ahead while playing (keeps crossfade smooth)", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Prefetch.entries.forEachIndexed { i, p ->
                SegmentedButton(selected = st.prefetch == p, onClick = { vm.setPrefetch(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, Prefetch.entries.size)) { Text(p.label, fontSize = 13.sp) }
            }
        }
        SwitchRow("Keep the whole library offline", st.allOffline) { on -> vm.setAllOffline(on) { act.downloadOffline() } }
        PillButton(if (albumsShown) "Hide albums" else "Choose albums to keep offline (${st.albums.count { it.pinned }})") { toggleAlbums() }
        OutlinedTextField(
            limit, { limit = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
            label = { Text("Cache limit in MB (empty = never delete automatically)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        PillButton("Save limit") { vm.setCacheLimit(limit.toLongOrNull() ?: 0) }
        PillButton("Clear cache (keep offline songs)") { vm.clearCache(true) { act.rescan() } }
        PillButton("Clear everything") { vm.clearCache(false) { act.rescan() } }
    }
}

@Composable
private fun AlbumRow(a: AlbumSummary, onPin: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPin(!a.pinned) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(a.pinned, onCheckedChange = onPin)
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${a.artist} · ${a.cached}/${a.songs} on phone", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SeparateCard(st: UiState, vm: MainViewModel, act: MainActivity.Actions) {
    var suffix by remember(st.albumSuffix) { mutableStateOf(st.albumSuffix) }
    InfoCard {
        Text("Keep apart from local songs", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Poweramp merges albums with the same name. Untick your local music folder in Poweramp, or add a " +
                "suffix so Navidrome albums show separately.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Add suffix to album names", st.useAlbumSuffix) { on -> vm.setAlbumSuffix(on, suffix) { act.rescan() } }
        if (st.useAlbumSuffix) {
            OutlinedTextField(suffix, { suffix = it }, Modifier.fillMaxWidth(), label = { Text("Suffix") }, singleLine = true)
            PillButton("Apply suffix") { vm.setAlbumSuffix(true, suffix) { act.rescan() } }
        }
    }
}

@Composable
private fun RemovedCard(removed: List<SongRow>) {
    InfoCard {
        Text("Removed from the server (${removed.size})", style = MaterialTheme.typography.headlineSmall)
        Text("Still playable from the phone until you clear the cache.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (r in removed.take(50)) Text("${r.song.artist} – ${r.song.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (removed.size > 50) Text("…and ${removed.size - 50} more", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HowToCard() {
    InfoCard {
        Text("How to use it in Poweramp", style = MaterialTheme.typography.headlineSmall)
        val steps = listOf(
            "Connect and sync here, and wait until it finishes.",
            "Tap \"Add to Poweramp Music Folders\" and tick \"Navidrome\".",
            "Poweramp scans it: songs appear under Navidrome in Folders, and in Albums and Artists.",
            "After changes on the server, sync here: Poweramp rescans by itself once this screen is open.",
        )
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}.", Modifier.width(26.dp), style = MaterialTheme.typography.bodyLarge)
                Text(s, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, onChange)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val mod = Modifier.fillMaxWidth().height(52.dp)
    if (filled) {
        Button(onClick, mod, enabled = enabled, colors = ButtonDefaults.buttonColors()) {
            Text(text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
        }
    } else {
        OutlinedButton(onClick, mod, enabled = enabled) {
            Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
