package dev.mike.flipspot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import dev.mike.flipspot.auth.SpotifyAuth
import dev.mike.flipspot.library.Library
import dev.mike.flipspot.remote.SpotifyRemote
import dev.mike.flipspot.ui.KeyAction
import dev.mike.flipspot.ui.KeyActionHandler
import dev.mike.flipspot.ui.KeyRouter
import dev.mike.flipspot.ui.ListScreen
import dev.mike.flipspot.ui.RoutedKey
import dev.mike.flipspot.ui.Shortcuts
import dev.mike.flipspot.ui.say

class HomeActivity : Activity(), KeyActionHandler, SpotifyRemote.Listener {

    /** [options]: what the left soft key does on this row (null = nothing to offer). */
    private data class MenuItem(val label: String, val options: (() -> Unit)? = null, val run: () -> Unit)

    private var menu: List<MenuItem> = emptyList()

    private lateinit var list: ListScreen
    private lateinit var npTitle: TextView
    private lateinit var npSub: TextView
    private lateinit var debugKey: TextView
    private var debuggable = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        list = findViewById(R.id.list)
        npTitle = findViewById(R.id.np_title)
        npSub = findViewById(R.id.np_sub)
        debugKey = findViewById(R.id.debug_key)
        debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) debugKey.visibility = View.VISIBLE

        list.showFooter = false
        buildMenu(savedInstanceState?.getInt(STATE_SELECTED) ?: 0)
        list.requestFocus()

        val dm = resources.displayMetrics
        Log.i(
            KeyRouter.TAG,
            "HomeActivity created: ${dm.widthPixels}x${dm.heightPixels}px densityDpi=${dm.densityDpi} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString(",")} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}",
        )
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        buildMenu(list.selectedIndex) // sign-in row appears/disappears
        SpotifyRemote.addListener(this)
    }

    override fun onPause() {
        super.onPause()
        SpotifyRemote.removeListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, list.selectedIndex)
    }

    private fun buildMenu(selected: Int) {
        val items = mutableListOf<MenuItem>()
        if (!SpotifyAuth.isSignedIn(this)) items += MenuItem("Sign in to Spotify") { signIn() }
        items += MenuItem("Now Playing") { openNowPlaying() }
        items += MenuItem("Playlists") { BrowseActivity.open(this, BrowseActivity.MODE_PLAYLISTS) }
        items += MenuItem("Audiobooks") { BrowseActivity.open(this, BrowseActivity.MODE_AUDIOBOOKS) }
        items += MenuItem("Liked Songs", options = ::likedSongsOptions) {
            BrowseActivity.open(this, BrowseActivity.MODE_LIKED)
        }
        items += MenuItem("Made for you") { BrowseActivity.open(this, BrowseActivity.MODE_MADE_FOR_YOU) }
        items += MenuItem("Albums") { BrowseActivity.open(this, BrowseActivity.MODE_ALBUMS) }
        items += MenuItem("Search") { say("Search: M4") }
        items += MenuItem("Settings") { say("Settings: M4") }
        val labels = items.map { it.label }
        if (labels != menu.map { it.label }) {
            menu = items
            list.setItems(labels, selected)
        } else {
            menu = items
        }
    }

    // ---- Now Playing strip ----

    override fun onRemoteChanged(snapshot: SpotifyRemote.Snapshot) {
        val s = snapshot.state
        val track = s?.track
        if (track != null) {
            npTitle.text = "${if (s.isPaused) "❚❚" else "▶"} ${track.name ?: ""}"
            npSub.text = track.artist?.name ?: track.album?.name ?: ""
        } else {
            npTitle.text = when (snapshot.status) {
                SpotifyRemote.Status.CONNECTED -> "Nothing playing"
                SpotifyRemote.Status.CONNECTING -> "Connecting…"
                else -> getString(R.string.np_idle)
            }
            npSub.text = snapshot.message ?: getString(R.string.np_idle_sub)
        }
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = KeyRouter.dispatch(event, SCREEN, this)
        if (debuggable) debugKey.text = KeyRouter.lastDescription
        return consumed || super.dispatchKeyEvent(event)
    }

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        // Only navigation keys auto-repeat; everything else fires once per press.
        val repeat = event.repeatCount > 0
        return when (key.action) {
            KeyAction.UP -> { list.move(-1); true }
            KeyAction.DOWN -> { list.move(+1); true }
            KeyAction.LEFT -> { list.page(-1); true }
            KeyAction.RIGHT -> { list.page(+1); true }
            KeyAction.OK -> { if (!repeat) menu.getOrNull(list.selectedIndex)?.run?.invoke(); true }
            KeyAction.SOFT_LEFT -> {
                if (!repeat) {
                    val opts = menu.getOrNull(list.selectedIndex)?.options
                    if (opts != null) opts() else say("No options here")
                }
                true
            }
            KeyAction.SOFT_RIGHT -> { if (!repeat) openNowPlaying(); true }
            KeyAction.PLAY_PAUSE -> { if (!repeat) SpotifyRemote.togglePlayPause(); true }
            KeyAction.DIGIT -> {
                if (!repeat) {
                    if (key.digit == 0) say("Search: M4") else Shortcuts.playPin(this, key.digit)
                }
                true
            }
            KeyAction.STAR -> false // unused on Home
            KeyAction.BACK -> false // let the system finish the activity
        }
    }

    private fun openNowPlaying() = startActivity(Intent(this, NowPlayingActivity::class.java))

    /** Left soft key on "Liked Songs": play / shuffle / pin without opening the 1,700-row list. */
    private fun likedSongsOptions() {
        val app = applicationContext
        FlipSpotApp.io.execute {
            val uid = Library.userId(app) // cached after the first library fetch
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (uid == null) return@runOnUiThread say("Can't reach Spotify")
                val uri = Library.likedSongsUri(uid)
                val opts = listOf<Pair<String, () -> Unit>>(
                    "Play" to { SpotifyRemote.playContext(uri, false); openNowPlaying() },
                    "Shuffle" to { SpotifyRemote.playContext(uri, true); openNowPlaying() },
                    "Pin to key…" to { Shortcuts.choosePinKey(this, uri, "Liked Songs") },
                )
                AlertDialog.Builder(this)
                    .setTitle("Liked Songs")
                    .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
                    .show()
            }
        }
    }

    // ---- auth ----

    private fun signIn() {
        if (!SpotifyAuth.hasClientId()) {
            say("SPOTIFY_CLIENT_ID missing in local.properties")
            return
        }
        // The Flip 2's WebView 83 renders Spotify's consent page blank, so the in-app flow
        // (SpotifyAuth.startSignIn) can't complete here. Consent once on the PC instead; that also
        // authorises App Remote for this account.
        say("Sign in from the PC: scripts\\get-token.ps1")
        Log.i(KeyRouter.TAG, "Auth: in-app sign-in disabled on this device; use scripts/get-token.ps1")
    }

    @Deprecated("Spotify auth library still reports through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SpotifyAuth.REQUEST_CODE) return
        val msg = SpotifyAuth.handleSignInResult(this, resultCode, data)
        say(msg)
        buildMenu(0)
        FlipSpotApp.io.execute {
            val me = SpotifyAuth.whoAmI(applicationContext)
            Log.i(KeyRouter.TAG, "Auth: /v1/me = $me")
            if (me != null) runOnUiThread { say("Signed in as $me") }
        }
        SpotifyRemote.retry(this) // app-remote-control scope is now granted
    }

    /**
     * Debug builds only, for the M1 audiobook spike:
     *   adb shell am start -n dev.mike.flipspot/.HomeActivity --es play_uri spotify:audiobook:<id>
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!debuggable) return
        // Debug: adb shell am start -n dev.mike.flipspot/.HomeActivity --ei speed 125
        intent?.getIntExtra("speed", 0)?.takeIf { it > 0 }?.let { pct ->
            intent.removeExtra("speed")
            SpotifyRemote.setSpeed(pct) { ok ->
                main.postDelayed({
                    Log.i(KeyRouter.TAG, "Speed: asked $pct ok=$ok, player reports ${SpotifyRemote.snapshot.state?.playbackSpeed}")
                }, 1500)
            }
        }
        if (intent?.getBooleanExtra(EXTRA_BROWSE_DUMP, false) == true) {
            intent.removeExtra(EXTRA_BROWSE_DUMP)
            dumpBrowseTree()
        }
        val uri = intent?.getStringExtra(EXTRA_PLAY_URI) ?: return
        intent.removeExtra(EXTRA_PLAY_URI)
        Log.i(KeyRouter.TAG, "Debug: play_uri=$uri")
        SpotifyRemote.playUri(uri)
        openNowPlaying()
    }

    /**
     * Debug: logs Spotify's browse tree two levels deep (tag FlipSpot, "Browse:").
     *   adb shell am start -n dev.mike.flipspot/.HomeActivity --ez browse_dump true
     */
    private fun dumpBrowseTree() {
        SpotifyRemote.browseRoot(com.spotify.android.appremote.api.ContentApi.ContentType.DEFAULT) { root ->
            Log.i(KeyRouter.TAG, "Browse: root = ${root?.size ?: "FAILED"}")
            root?.forEach { section ->
                Log.i(KeyRouter.TAG, "Browse: [${section.title}] ${section.uri} playable=${section.playable} children=${section.hasChildren}")
                if (section.hasChildren) SpotifyRemote.browseChildren(section, 30) { kids ->
                    kids?.forEach { k ->
                        Log.i(KeyRouter.TAG, "Browse:   [${section.title}] > ${k.title} | ${k.subtitle} | ${k.uri} playable=${k.playable}")
                    } ?: Log.i(KeyRouter.TAG, "Browse:   [${section.title}] children FAILED")
                }
            }
        }
    }

    private companion object {
        const val EXTRA_BROWSE_DUMP = "browse_dump"
        const val SCREEN = "Home"
        const val STATE_SELECTED = "selected"
        const val EXTRA_PLAY_URI = "play_uri"
    }
}
