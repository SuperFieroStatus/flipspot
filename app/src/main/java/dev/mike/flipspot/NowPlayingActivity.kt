package dev.mike.flipspot

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.spotify.protocol.types.Repeat
import dev.mike.flipspot.api.SpotifyWebApi
import dev.mike.flipspot.remote.SpotifyRemote
import dev.mike.flipspot.ui.Audiobooks
import dev.mike.flipspot.ui.KeyAction
import dev.mike.flipspot.ui.KeyActionHandler
import dev.mike.flipspot.ui.KeyRouter
import dev.mike.flipspot.ui.RoutedKey
import dev.mike.flipspot.ui.Shortcuts
import dev.mike.flipspot.ui.say

/**
 * Now Playing. Keys (PLAN.md section 3):
 *   OK / #  play-pause      Left / Right  previous / next
 *   4 / 6   -15 s / +30 s   7 / 9         -5 min / +5 min
 *   5       shuffle         8             repeat
 *   *       queue           Back          up one level
 *   0       search          Left soft     options (pin what's playing, queue, speed)
 *   Up/Down speed 1x/1.2x/1.5x/2x (audiobooks and podcasts only)
 */
class NowPlayingActivity : Activity(), KeyActionHandler, SpotifyRemote.Listener {

    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progress: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var playState: TextView
    private lateinit var flags: TextView
    private lateinit var softCenter: TextView
    private lateinit var softLeft: TextView
    private lateinit var debugKey: TextView
    private var debuggable = false

    // Active Spotify Connect device (Web API). Null device + deviceKnown = nothing active.
    private var device: SpotifyWebApi.Device? = null
    private var deviceKnown = false
    private var deviceFetchAt = 0L
    private var deviceFetchInFlight = false

    private val playingElsewhere: Boolean
        get() = deviceKnown && device?.isThisPhone != true

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderPosition(SpotifyRemote.snapshot)
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        status = findViewById(R.id.np_status)
        title = findViewById(R.id.np_title)
        artist = findViewById(R.id.np_artist)
        progress = findViewById(R.id.np_progress)
        elapsed = findViewById(R.id.np_elapsed)
        total = findViewById(R.id.np_total)
        playState = findViewById(R.id.np_play_state)
        flags = findViewById(R.id.np_flags)
        softCenter = findViewById(R.id.np_soft_center)
        softLeft = findViewById(R.id.np_soft_left)
        debugKey = findViewById(R.id.debug_key)
        debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) debugKey.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        SpotifyRemote.addListener(this)
        ticker.post(tick)
        refreshDevice(force = true)
    }

    override fun onPause() {
        super.onPause()
        SpotifyRemote.removeListener(this)
        ticker.removeCallbacks(tick)
    }

    override fun onRemoteChanged(snapshot: SpotifyRemote.Snapshot) {
        val s = snapshot.state
        val track = s?.track

        renderStatus(snapshot)
        if (snapshot.status == SpotifyRemote.Status.CONNECTED) refreshDevice(force = false)

        if (track == null) {
            title.text = if (snapshot.status == SpotifyRemote.Status.CONNECTED) "Nothing playing" else "Not connected"
            artist.text = if (snapshot.status == SpotifyRemote.Status.FAILED) "OK to retry" else ""
            playState.text = ""
            flags.text = ""
            softCenter.text = if (snapshot.status == SpotifyRemote.Status.FAILED) "Retry" else ""
        } else {
            title.text = track.name ?: "—"
            val named = track.artists?.mapNotNull { it.name?.ifEmpty { null } }?.joinToString(", ")?.ifEmpty { null }
                ?: track.artist?.name?.ifEmpty { null } ?: track.album?.name?.ifEmpty { null }
            artist.text = named ?: bookLabelFor(track.uri) ?: ""
            playState.text = if (s.isPaused) "❚❚ Paused" else "▶ Playing"
            softCenter.text = if (s.isPaused) "Play" else "Pause"
            val opts = s.playbackOptions
            val repeat = when (opts?.repeatMode) {
                Repeat.ONE -> "one"
                Repeat.ALL -> "all"
                else -> "off"
            }
            flags.text = if (isSpoken(track)) {
                // Shuffle means nothing inside a chapter; speed does (Up/Down to change).
                "Speed ${Audiobooks.speedLabel(currentSpeed())}  ·  Repeat $repeat"
            } else {
                "Shuffle ${if (opts?.isShuffling == true) "on" else "off"}  ·  Repeat $repeat"
            }
        }
        renderPosition(snapshot)
    }

    // Audiobook chapters come with no artist; look the book up from the library cache (io thread).
    private val bookLabels = HashMap<String, String?>()

    private fun bookLabelFor(uri: String?): String? {
        if (uri == null || !uri.startsWith("spotify:episode:")) return null
        if (bookLabels.containsKey(uri)) return bookLabels[uri]
        bookLabels[uri] = null // lookup in flight / not found
        val app = applicationContext
        FlipSpotApp.io.execute {
            val label = dev.mike.flipspot.library.Library.bookLabelForChapter(app, uri)
            if (label != null) runOnUiThread {
                bookLabels[uri] = label
                if (!isDestroyed && SpotifyRemote.snapshot.state?.track?.uri == uri) artist.text = label
            }
        }
        return null
    }

    private fun renderStatus(snapshot: SpotifyRemote.Snapshot) {
        status.text = when {
            snapshot.message != null -> snapshot.message
            snapshot.status != SpotifyRemote.Status.CONNECTED -> snapshot.status.name.lowercase()
            !deviceKnown -> "Spotify connected"
            device == null -> "No active device"
            device!!.isThisPhone -> "Playing on this phone"
            else -> "On: ${device!!.name}"
        }
        softLeft.text = if (playingElsewhere) "Play here" else getString(R.string.soft_left)
    }

    /**
     * Looks up the active Connect device; throttled unless [force]. [learnPhone] is only set by the
     * lookup right after a successful switch-to-phone, so a routine refresh racing the handover can't
     * record the PC as "this phone".
     */
    private fun refreshDevice(force: Boolean, learnPhone: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!learnPhone && (deviceFetchInFlight || (!force && now - deviceFetchAt < DEVICE_REFRESH_MS))) return
        deviceFetchInFlight = true
        deviceFetchAt = now
        val app = applicationContext
        FlipSpotApp.io.execute {
            val result = SpotifyWebApi.activeDevice(app)
            runOnUiThread {
                deviceFetchInFlight = false
                if (isDestroyed || !result.ok) return@runOnUiThread
                val d = result.device
                if (learnPhone && d != null && d.type.equals("Smartphone", ignoreCase = true)) {
                    SpotifyWebApi.rememberPhoneDevice(app, d)
                    device = d.copy(isThisPhone = true)
                } else {
                    device = d
                }
                deviceKnown = true
                android.util.Log.d(KeyRouter.TAG, "Device: ${device?.name ?: "none"} (${device?.type}) phone=${device?.isThisPhone}")
                renderStatus(SpotifyRemote.snapshot)
            }
        }
    }

    private fun playHere() {
        say("Moving playback to phone…")
        SpotifyRemote.switchToPhone { ok ->
            if (!ok) return@switchToPhone
            // Give Spotify a moment to hand over, then learn this phone's Connect device id.
            ticker.postDelayed({ if (!isDestroyed) refreshDevice(force = true, learnPhone = true) }, 1500)
        }
    }

    private fun renderPosition(snapshot: SpotifyRemote.Snapshot) {
        val dur = snapshot.state?.track?.duration ?: 0
        if (dur <= 0) {
            elapsed.text = ""
            total.text = ""
            progress.progress = 0
            return
        }
        val pos = snapshot.positionMs()
        elapsed.text = formatTime(pos)
        total.text = formatTime(dur)
        progress.progress = ((pos * 1000) / dur).toInt()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = KeyRouter.dispatch(event, SCREEN, this)
        if (debuggable) debugKey.text = KeyRouter.lastDescription
        return consumed || super.dispatchKeyEvent(event)
    }

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        // Seeks may auto-repeat while held; everything else fires once per press.
        if (repeat && !(key.action == KeyAction.DIGIT && key.digit in SEEK_KEYS)) {
            return key.action != KeyAction.BACK
        }
        when (key.action) {
            KeyAction.OK -> {
                if (SpotifyRemote.snapshot.status == SpotifyRemote.Status.FAILED) {
                    say("Retrying…")
                    SpotifyRemote.retry(this)
                } else {
                    SpotifyRemote.togglePlayPause()
                }
            }
            KeyAction.PLAY_PAUSE -> SpotifyRemote.togglePlayPause()
            KeyAction.LEFT -> SpotifyRemote.skipPrevious()
            KeyAction.RIGHT -> SpotifyRemote.skipNext()
            KeyAction.DIGIT -> when (key.digit) {
                4 -> seek(-15_000)
                6 -> seek(30_000)
                7 -> seek(-5 * 60_000)
                9 -> seek(5 * 60_000)
                5 -> SpotifyRemote.toggleShuffle()
                8 -> SpotifyRemote.toggleRepeat()
                0 -> BrowseActivity.open(this, BrowseActivity.MODE_SEARCH)
                else -> Shortcuts.playPin(this, key.digit) // 1-3 (4-9 are transport keys here)
            }
            KeyAction.STAR -> BrowseActivity.open(this, BrowseActivity.MODE_QUEUE)
            KeyAction.SOFT_LEFT -> if (playingElsewhere) playHere() else showOptions()
            KeyAction.SOFT_RIGHT -> finish() // labelled "Home": back to the menu
            KeyAction.UP -> stepSpeed(+1)
            KeyAction.DOWN -> stepSpeed(-1)
            KeyAction.BACK -> return false
        }
        return true
    }

    /** Left soft key: what you can do with what's playing. */
    private fun showOptions() {
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        val ctx = SpotifyRemote.playerContext
        val ctxUri = ctx?.uri
        if (ctxUri != null) {
            val name = ctx.title?.ifEmpty { null } ?: "this"
            val isBook = ctxUri.startsWith("spotify:show:")
            opts += "Pin “$name” to key…" to { Shortcuts.choosePinKey(this, ctxUri, name, askShuffle = !isBook) }
        }
        opts += "Queue (*)" to { BrowseActivity.open(this, BrowseActivity.MODE_QUEUE) }
        if (isSpoken(SpotifyRemote.snapshot.state?.track)) {
            opts += "Speed (Up/Down)…" to ::chooseSpeed
        }
        opts += "Play on this phone" to ::playHere
        android.app.AlertDialog.Builder(this)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .show()
    }

    private fun chooseSpeed() {
        val speeds = Audiobooks.SPEEDS
        val now = currentSpeed()
        android.app.AlertDialog.Builder(this)
            .setTitle("Speed")
            .setSingleChoiceItems(
                speeds.map { Audiobooks.speedLabel(it) }.toTypedArray(),
                speeds.indexOf(now),
            ) { d, which ->
                d.dismiss()
                val target = speeds[which]
                SpotifyRemote.setSpeed(target) { ok ->
                    if (ok) Audiobooks.savePreferredSpeed(this, target)
                    say(if (ok) Audiobooks.speedLabel(target) else "Spotify refused that speed")
                }
            }
            .show()
    }

    private fun isSpoken(track: com.spotify.protocol.types.Track?): Boolean =
        track != null && (track.isEpisode || track.isPodcast || track.uri?.startsWith("spotify:episode:") == true)

    /** Current speed in percent: what FlipSpot last set / saw while playing, else the saved preference. */
    private fun currentSpeed(): Int = SpotifyRemote.speedPercent ?: Audiobooks.preferredSpeed(this)

    /** Up/Down: 1x <-> 1.2x <-> 1.5x <-> 2x for audiobooks and podcasts (Spotify ignores speed for music). */
    private fun stepSpeed(dir: Int) {
        if (!isSpoken(SpotifyRemote.snapshot.state?.track)) return say("Speed is for audiobooks")
        val speeds = Audiobooks.SPEEDS
        val now = currentSpeed()
        // Nearest step to what's playing now (e.g. if it was set to 0.8x or 3x elsewhere).
        val idx = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - now) } ?: 0
        val target = speeds[(idx + dir).coerceIn(0, speeds.size - 1)]
        if (target == now) return say(Audiobooks.speedLabel(target) + if (dir > 0) " (fastest)" else " (normal)")
        SpotifyRemote.setSpeed(target) { ok ->
            if (ok) {
                Audiobooks.savePreferredSpeed(this, target)
                say(Audiobooks.speedLabel(target))
            } else {
                say("Spotify refused ${Audiobooks.speedLabel(target)}")
            }
        }
    }

    private fun seek(deltaMs: Long) {
        SpotifyRemote.seekBy(deltaMs)
        val secs = deltaMs / 1000
        val label = if (kotlin.math.abs(secs) >= 60) "${secs / 60} min" else "$secs s"
        say(if (deltaMs > 0) "+$label" else label)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private companion object {
        const val SCREEN = "NowPlaying"
        val SEEK_KEYS = setOf(4, 6, 7, 9)
        const val DEVICE_REFRESH_MS = 5_000L
    }
}
