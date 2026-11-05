package dev.mike.flipspot.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import dev.mike.flipspot.FlipSpotApp
import dev.mike.flipspot.NowPlayingActivity
import dev.mike.flipspot.library.Library
import dev.mike.flipspot.library.Pins
import dev.mike.flipspot.remote.SpotifyRemote

/** Actions shared by every screen: pins on 1-9, jumping to Now Playing. */
object Shortcuts {

    fun openNowPlaying(activity: Activity) {
        if (activity !is NowPlayingActivity) {
            activity.startActivity(Intent(activity, NowPlayingActivity::class.java))
        }
    }

    /** Plays whatever is pinned to [digit] (keeps the current shuffle setting) and opens Now Playing. */
    fun playPin(activity: Activity, digit: Int) {
        val pin = Pins.get(activity, digit)
        if (pin == null) {
            activity.say("Nothing on $digit. Options > Pin to key")
            return
        }
        if (pin.uri.startsWith("spotify:show:")) {
            // Audiobook: resume at the right chapter and position, not wherever Spotify starts the show.
            val app = activity.applicationContext
            FlipSpotApp.io.execute {
                val book = Library.bookByUri(app, pin.uri)
                activity.runOnUiThread {
                    if (book != null) {
                        Audiobooks.resume(activity, book)
                    } else {
                        SpotifyRemote.playUri(pin.uri)
                        openNowPlaying(activity)
                    }
                }
            }
            return
        }
        val shuffle = pin.shuffle
        activity.say("$digit: ${pin.name}${if (shuffle == true) " (shuffled)" else ""}")
        if (shuffle == null) SpotifyRemote.playUri(pin.uri) else SpotifyRemote.playContext(pin.uri, shuffle)
        openNowPlaying(activity)
    }

    /** "Pin to key": choose 1-9 (showing what each holds), then in order or shuffled. */
    fun choosePinKey(activity: Activity, uri: String, name: String, askShuffle: Boolean = true) {
        val labels = (1..9).map { d -> "$d: ${Pins.get(activity, d)?.name ?: "—"}" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Pin “$name” to")
            .setItems(labels) { _, which ->
                val digit = which + 1
                if (!askShuffle) {
                    Pins.set(activity, digit, Pins.Pin(uri, name))
                    activity.say("Pinned to $digit")
                    return@setItems
                }
                AlertDialog.Builder(activity)
                    .setTitle("When $digit is pressed")
                    .setItems(arrayOf("Play in order", "Play shuffled")) { _, mode ->
                        Pins.set(activity, digit, Pins.Pin(uri, name, shuffle = mode == 1))
                        activity.say("Pinned to $digit")
                    }
                    .show()
            }
            .show()
    }
}
