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
                notifyListeners()
            }
        }
        sub.setErrorCallback { e -> Log.w(TAG, "Remote: player-state subscription error", e) }
        playerSub = sub
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
