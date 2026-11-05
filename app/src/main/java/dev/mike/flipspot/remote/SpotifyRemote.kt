package dev.mike.flipspot.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UnsupportedFeatureVersionException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.PlaybackSpeed
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import dev.mike.flipspot.BuildConfig
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Wraps the Spotify App Remote SDK: one IPC connection to com.spotify.music, shared by every screen.
 * Works offline with downloaded content. All public methods and listener callbacks run on the main thread.
 *
 * FlipSpotApp calls [start] when the app comes to the foreground and [stop] a while after it leaves.
 */
object SpotifyRemote {
    private const val TAG = "FlipSpot"

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    data class Snapshot(
        val status: Status,
        val state: PlayerState?,
        /** elapsedRealtime when [state] arrived, for interpolating the position between events. */
        val stateAtMs: Long,
        val message: String?,
    ) {
        /** Best estimate of the current position, advancing while playing. */
        fun positionMs(): Long {
            val s = state ?: return 0
            if (s.isPaused) return s.playbackPosition
            val speed = if (s.playbackSpeed > 0f) s.playbackSpeed else 1f
            val pos = s.playbackPosition + ((SystemClock.elapsedRealtime() - stateAtMs) * speed).toLong()
            val dur = s.track?.duration ?: 0
            return if (dur > 0) pos.coerceAtMost(dur) else pos
        }
    }

    fun interface Listener {
        fun onRemoteChanged(snapshot: Snapshot)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var appContext: Context? = null
    private var remote: SpotifyAppRemote? = null
    private var playerSub: Subscription<PlayerState>? = null
    private var contextSub: Subscription<PlayerContext>? = null

    /** What's playing from (playlist, album, collection, show), for "Pin what's playing". */
    var playerContext: PlayerContext? = null
        private set
    private var wanted = false
    private var retryDelayMs = INITIAL_RETRY_MS
    private var pendingPlayUri: String? = null

    var snapshot = Snapshot(Status.DISCONNECTED, null, 0, null)
        private set

    val isConnected: Boolean get() = remote?.isConnected == true

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onRemoteChanged(snapshot)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ---- connection ----

    fun start(context: Context) {
        appContext = context.applicationContext
        wanted = true
        main.removeCallbacks(retryRunnable)
        if (snapshot.status == Status.CONNECTING || isConnected) return
        connect()
    }

    fun stop() {
        wanted = false
        main.removeCallbacks(retryRunnable)
        teardown()
        publish(Status.DISCONNECTED, message = null)
    }

    /** Manual retry (e.g. OK pressed on an error), resetting backoff. */
    fun retry(context: Context) {
        retryDelayMs = INITIAL_RETRY_MS
        teardown()
        snapshot = snapshot.copy(status = Status.DISCONNECTED)
        start(context)
    }

    private val retryRunnable = Runnable { if (wanted && !isConnected) connect() }

    private fun connect() {
        val ctx = appContext ?: return
        if (!SpotifyAppRemote.isSpotifyInstalled(ctx)) {
            publish(Status.FAILED, message = "Spotify app not installed")
            return
        }
        publish(Status.CONNECTING, message = "Connecting to Spotify…")
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true) // first run: consent screen inside Spotify (FlipMouse tap)
            .build()
        Log.i(TAG, "Remote: connecting")
        SpotifyAppRemote.connect(ctx, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                main.post { this@SpotifyRemote.onConnected(appRemote) }
            }

            override fun onFailure(error: Throwable) {
                main.post { this@SpotifyRemote.onFailure(error) }
            }
        })
    }

    private fun onConnected(appRemote: SpotifyAppRemote) {
        if (!wanted) {
            SpotifyAppRemote.disconnect(appRemote)
            return
        }
        Log.i(TAG, "Remote: connected")
        remote = appRemote
        retryDelayMs = INITIAL_RETRY_MS
        publish(Status.CONNECTED, message = null)
        val sub = appRemote.playerApi.subscribeToPlayerState()
        sub.setEventCallback { state ->
            main.post {
                snapshot = Snapshot(Status.CONNECTED, state, SystemClock.elapsedRealtime(), null)
                // Spotify can send one more state with the *old* speed right after a change; don't let it
                // overwrite what we just set.
                val sinceSet = SystemClock.elapsedRealtime() - speedSetAtMs
                if (!state.isPaused && state.playbackSpeed > 0f && sinceSet > SPEED_TRUST_MS) {
                    speedPercent = Math.round(state.playbackSpeed * 100)
                }
                applyPendingSeek(state)
                notifyListeners()
            }
        }
        sub.setErrorCallback { e -> Log.w(TAG, "Remote: player-state subscription error", e) }
        playerSub = sub
        val ctxSub = appRemote.playerApi.subscribeToPlayerContext()
        ctxSub.setEventCallback { c -> main.post { playerContext = c?.takeIf { !it.uri.isNullOrEmpty() } } }
        ctxSub.setErrorCallback { e -> Log.w(TAG, "Remote: player-context subscription error", e) }
        contextSub = ctxSub
        pendingPlayUri?.let { uri ->
            pendingPlayUri = null
            playUri(uri)
        }
    }

    // Called both for initial connect failures and for later disconnects.
    private fun onFailure(error: Throwable) {
        Log.w(TAG, "Remote: failure ${error.javaClass.simpleName}: ${error.message}")
        teardown()
        val (msg, autoRetry) = when (error) {
            is CouldNotFindSpotifyApp -> "Spotify app not found" to false
            is NotLoggedInException -> "Log in to the Spotify app first" to false
            is UserNotAuthorizedException -> "Not authorised: Home > Sign in to Spotify" to false
            is UnsupportedFeatureVersionException -> "Spotify app too old for App Remote" to false
            else -> "Spotify disconnected" to true
        }
        publish(Status.FAILED, message = msg)
        if (wanted && autoRetry) {
            Log.i(TAG, "Remote: retry in ${retryDelayMs} ms")
            main.postDelayed(retryRunnable, retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun teardown() {
        playerSub?.cancel()
        playerSub = null
        contextSub?.cancel()
        contextSub = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
    }

    // ---- commands (no-ops with a log line when not connected) ----

    fun togglePlayPause() {
        val paused = snapshot.state?.isPaused ?: true
        player("togglePlay") { if (paused) it.resume() else it.pause() }
    }

    fun skipNext() = player("skipNext") { it.skipNext() }
    fun skipPrevious() = player("skipPrevious") { it.skipPrevious() }
    fun toggleShuffle() = player("toggleShuffle") { it.toggleShuffle() }
    fun toggleRepeat() = player("toggleRepeat") { it.toggleRepeat() }

    fun seekBy(deltaMs: Long) {
        // Absolute seek from our interpolated position so we can clamp to [0, duration).
        val s = snapshot
        val dur = s.state?.track?.duration ?: 0
        var target = (s.positionMs() + deltaMs).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1000)
        player("seekTo($target)") { it.seekTo(target) }
    }

    /** Play a playlist/album/collection from the top, with shuffle forced on or off first. */
    fun playContext(uri: String, shuffle: Boolean) {
        val r = remote
        if (r == null || !r.isConnected) {
            Log.w(TAG, "Remote: playContext ignored, not connected")
            appContext?.let { start(it) }
            return
        }
        Log.d(TAG, "Remote: setShuffle($shuffle) then play($uri)")
        // Start playback only once shuffle has been applied, so the first track respects it.
        // (player() would replace the result callback, so this chain talks to the API directly.)
        val playNow = Runnable { player("play($uri)") { it.play(uri) } }
        r.playerApi.setShuffle(shuffle)
            .setResultCallback { main.post(playNow) }
            .setErrorCallback { e ->
                Log.w(TAG, "Remote: setShuffle failed (${e.message}), playing anyway")
                main.post(playNow)
            }
    }

    /**
     * Play [contextUri] starting at [index] (so the rest of the playlist follows), shuffle off.
     * Falls back to playing [trackUri] alone if Spotify rejects the index.
     */
    fun playFromIndex(contextUri: String, index: Int, trackUri: String) {
        val r = remote
        if (r == null || !r.isConnected) {
            Log.w(TAG, "Remote: playFromIndex ignored, not connected")
            appContext?.let { start(it) }
            return
        }
        Log.d(TAG, "Remote: skipToIndex($contextUri, $index)")
        r.playerApi.setShuffle(false)
        r.playerApi.skipToIndex(contextUri, index)
            .setResultCallback { Log.d(TAG, "Remote: skipToIndex ok") }
            .setErrorCallback { e ->
                Log.w(TAG, "Remote: skipToIndex failed (${e.message}), playing track alone")
                main.post { player("play($trackUri)") { it.play(trackUri) } }
            }
    }

    fun queue(uri: String) = player("queue($uri)") { it.queue(uri) }

    // Applied once Spotify reports the chapter is playing (audiobook resume): position and speed.
    private data class PendingStart(val uri: String, val positionMs: Long, val speedPercent: Int?)

    private var pendingStart: PendingStart? = null
    private val clearPendingStart = Runnable { pendingStart = null }

    /**
     * Plays [uri] (an audiobook chapter) and makes sure it ends up at [positionMs] and [speedPercent]:
     * Spotify usually resumes chapters itself, but if the first state for this URI is more than 5 s off,
     * seek. Speed is applied after the chapter starts so it doesn't land on the previous item.
     */
    fun playAt(uri: String, positionMs: Long, speedPercent: Int? = null) {
        pendingStart = PendingStart(uri, positionMs, speedPercent)
        main.removeCallbacks(clearPendingStart)
        main.postDelayed(clearPendingStart, 15_000)
        Log.d(TAG, "Remote: playAt($uri, ${positionMs / 1000}s, speed=$speedPercent)")
        playUri(uri)
    }

    private fun applyPendingSeek(state: PlayerState) {
        val p = pendingStart ?: return
        if (state.track?.uri != p.uri) return
        pendingStart = null
        main.removeCallbacks(clearPendingStart)
        if (kotlin.math.abs(state.playbackPosition - p.positionMs) > 5_000) {
            Log.d(TAG, "Remote: resume seek ${state.playbackPosition / 1000}s -> ${p.positionMs / 1000}s")
            player("seekTo(${p.positionMs})") { it.seekTo(p.positionMs) }
        } else {
            Log.d(TAG, "Remote: Spotify resumed at ${state.playbackPosition / 1000}s itself")
        }
        // Always (re)apply: the reported speed can be stale, and the call is cheap.
        p.speedPercent?.let { setSpeed(it) }
    }

    fun setShuffle(on: Boolean) = player("setShuffle($on)") { it.setShuffle(on) }

    /**
     * Last known playback speed in percent, or null if unknown. Spotify doesn't push a fresh PlayerState
     * after a speed change (and paused states can carry a stale speed), so this is updated from our own
     * successful setSpeed calls and only from states that are actually playing.
     */
    var speedPercent: Int? = null
        private set
    private var speedSetAtMs = 0L
    private const val SPEED_TRUST_MS = 4_000L

    /**
     * Playback speed for podcasts/audiobooks, in percent (100 = 1x). The SDK's enum only offers
     * 50/80/100/120/150/200/300, but the wire message carries a plain int, so this sends it directly.
     */
    fun setSpeed(percent: Int, onResult: ((Boolean) -> Unit)? = null) {
        val r = remote
        if (r == null || !r.isConnected) {
            Log.w(TAG, "Remote: setSpeed ignored, not connected")
            onResult?.invoke(false)
            return
        }
        Log.d(TAG, "Remote: setSpeed($percent)")
        r.call("com.spotify.set_playback_speed", PlaybackSpeed(percent), Empty::class.java)
            .setResultCallback {
                Log.d(TAG, "Remote: setSpeed($percent) ok")
                main.post {
                    speedPercent = percent
                    speedSetAtMs = SystemClock.elapsedRealtime()
                    notifyListeners()
                    onResult?.invoke(true)
                }
            }
            .setErrorCallback { e ->
                Log.w(TAG, "Remote: setSpeed($percent) failed: ${e.message}")
                main.post { onResult?.invoke(false) }
            }
    }

    // ---- Spotify's own browse tree (Made for you, Recently played…) via the Content API ----

    /** Top-level browse sections. [cb] gets null on failure. Main thread. */
    fun browseRoot(type: String, cb: (List<ListItem>?) -> Unit) {
        val r = remote ?: return cb(null)
        r.contentApi.getRecommendedContentItems(type)
            .setResultCallback { items -> main.post { cb(items.items?.toList() ?: emptyList()) } }
            .setErrorCallback { e ->
                Log.w(TAG, "Remote: browse root failed: ${e.message}")
                main.post { cb(null) }
            }
    }

    fun browseChildren(item: ListItem, count: Int, cb: (List<ListItem>?) -> Unit) {
        val r = remote ?: return cb(null)
        r.contentApi.getChildrenOfItem(item, count, 0)
            .setResultCallback { items -> main.post { cb(items.items?.toList() ?: emptyList()) } }
            .setErrorCallback { e ->
                Log.w(TAG, "Remote: browse '${item.title}' failed: ${e.message}")
                main.post { cb(null) }
            }
    }

    /** Move playback from another Connect device (e.g. a PC) to this phone. */
    fun switchToPhone(onDone: ((Boolean) -> Unit)? = null) {
        val r = remote
        if (r == null || !r.isConnected) {
            Log.w(TAG, "Remote: switchToPhone ignored, not connected")
            appContext?.let { start(it) }
            onDone?.invoke(false)
            return
        }
        Log.d(TAG, "Remote: switchToPhone")
        r.connectApi.connectSwitchToLocalDevice()
            .setResultCallback {
                Log.d(TAG, "Remote: switchToPhone ok")
                main.post { onDone?.invoke(true) }
            }
            .setErrorCallback { e ->
                main.post {
                    onCommandError("switchToPhone", e)
                    onDone?.invoke(false)
                }
            }
    }

    /** M1 spike: play any spotify: URI (track, playlist, episode, audiobook, chapter). */
    fun playUri(uri: String) {
        if (!isConnected) {
            Log.i(TAG, "Remote: queueing playUri until connected: $uri")
            pendingPlayUri = uri
            appContext?.let { start(it) }
            return
        }
        player("play($uri)") { it.play(uri) }
    }

    private fun player(
        label: String,
        call: (com.spotify.android.appremote.api.PlayerApi) -> CallResult<*>,
    ): Boolean {
        val r = remote
        if (r == null || !r.isConnected) {
            Log.w(TAG, "Remote: $label ignored, not connected")
            appContext?.let { start(it) }
            return false
        }
        Log.d(TAG, "Remote: $label")
        call(r.playerApi)
            .setResultCallback { Log.d(TAG, "Remote: $label ok") }
            .setErrorCallback { e -> main.post { onCommandError(label, e) } }
        return true
    }

    private fun onCommandError(label: String, e: Throwable) {
        Log.w(TAG, "Remote: $label failed ${e.javaClass.simpleName}: ${e.message}")
        snapshot = snapshot.copy(message = "$label failed: ${e.message ?: e.javaClass.simpleName}")
        notifyListeners()
    }

    private fun publish(status: Status, message: String?) {
        snapshot = snapshot.copy(status = status, message = message)
        notifyListeners()
    }

    private fun notifyListeners() {
        val s = snapshot
        for (l in listeners) l.onRemoteChanged(s)
    }

    private const val INITIAL_RETRY_MS = 2_000L
    private const val MAX_RETRY_MS = 30_000L
}
