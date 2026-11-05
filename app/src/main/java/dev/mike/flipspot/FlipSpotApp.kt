package dev.mike.flipspot

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.SpotifyAppRemote
import dev.mike.flipspot.auth.SpotifyAuth
import dev.mike.flipspot.remote.SpotifyRemote
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps the App Remote connection tied to foreground use: connect when the first FlipSpot activity
 * starts, disconnect [IDLE_DISCONNECT_MS] after the last one stops (so screen hops don't reconnect).
 */
class FlipSpotApp : Application() {

    private val main = Handler(Looper.getMainLooper())
    private var started = 0
    private val idleDisconnect = Runnable {
        Log.i(TAG, "App idle in background, disconnecting App Remote")
        SpotifyRemote.stop()
    }

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        SpotifyAppRemote.setDebugMode(debuggable)

        if (!SpotifyAuth.hasClientId()) Log.e(TAG, "SPOTIFY_CLIENT_ID missing from local.properties")

        io.execute {
            if (SpotifyAuth.importTokenFile(this)) {
                Log.i(TAG, "Token import OK; /v1/me = ${SpotifyAuth.whoAmI(this)}")
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    main.removeCallbacks(idleDisconnect)
                    SpotifyRemote.start(this@FlipSpotApp)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) main.postDelayed(idleDisconnect, IDLE_DISCONNECT_MS)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        private const val TAG = "FlipSpot"
        private const val IDLE_DISCONNECT_MS = 60_000L

        /** Single background thread for blocking network/disk work. */
        val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "flipspot-io") }
    }
}
