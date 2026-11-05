package dev.mike.flipspot.ui

import android.app.Activity
import android.content.Context
import dev.mike.flipspot.FlipSpotApp
import dev.mike.flipspot.library.Book
import dev.mike.flipspot.library.Chapter
import dev.mike.flipspot.library.Library
import dev.mike.flipspot.library.resumeIndex
import dev.mike.flipspot.remote.SpotifyRemote

/** Audiobook playback helpers shared by the browse screens and number-key pins. */
object Audiobooks {

    /**
     * Speeds offered on Now Playing (Up/Down), in percent. Spotify 8.9 only accepts
     * 50/80/100/120/150/200/300 (it rejects 125 and 175 as "Unexpected playback speed").
     */
    val SPEEDS = listOf(100, 120, 150, 200)

    private const val PREFS = "playback"
    private const val KEY_SPEED = "book_speed"

    /** The listener's chosen audiobook speed, re-applied when a chapter starts. */
    fun preferredSpeed(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SPEED, 100)

    fun savePreferredSpeed(ctx: Context, percent: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SPEED, percent).apply()
    }

    fun speedLabel(percent: Int): String = when (percent % 100) {
        0 -> "${percent / 100}×"
        else -> "%.1f×".format(percent / 100f).replace(".0×", "×")
    }

    /** Plays [chapter] from its saved position (or the start if finished/unstarted). */
    fun playChapter(activity: Activity, book: Book, chapter: Chapter) {
        if (!chapter.playable) return activity.say("Chapter not playable")
        Library.setLastBook(activity, book)
        // Turn shuffle off: a shuffled book is nobody's intention.
        SpotifyRemote.setShuffle(false)
        SpotifyRemote.playAt(
            chapter.uri,
            if (chapter.inProgress) chapter.resumeMs else 0L,
            speedPercent = preferredSpeed(activity),
        )
        Shortcuts.openNowPlaying(activity)
    }

    /** Resume where you left off, using [chapters] if already loaded, else fetching them now. */
    fun resume(activity: Activity, book: Book, chapters: List<Chapter>? = null) {
        if (chapters != null && chapters.isNotEmpty()) {
            val c = chapters[chapters.resumeIndex()]
            activity.say("Resuming ${c.name}")
            playChapter(activity, book, c)
            return
        }
        activity.say("Finding your place…")
        val app = activity.applicationContext
        FlipSpotApp.io.execute {
            val fetched = Library.chaptersNow(app, book)
            activity.runOnUiThread {
                if (fetched.isNullOrEmpty()) {
                    // Last resort: let Spotify pick (it may start at an earlier chapter).
                    app.say("Offline: Spotify will pick the chapter")
                    SpotifyRemote.playUri(book.uri)
                    Shortcuts.openNowPlaying(activity)
                } else {
                    resume(activity, book, fetched)
                }
            }
        }
    }

    fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
