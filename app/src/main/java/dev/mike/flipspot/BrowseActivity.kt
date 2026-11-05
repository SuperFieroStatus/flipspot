package dev.mike.flipspot

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import dev.mike.flipspot.api.SpotifyWebApi
import dev.mike.flipspot.library.Album
import dev.mike.flipspot.library.Book
import dev.mike.flipspot.library.Chapter
import dev.mike.flipspot.library.Library
import dev.mike.flipspot.library.resumeIndex
import dev.mike.flipspot.ui.Audiobooks
import dev.mike.flipspot.library.LibraryUpdate
import dev.mike.flipspot.library.Playlist
import dev.mike.flipspot.library.Track
import dev.mike.flipspot.remote.SpotifyRemote
import dev.mike.flipspot.ui.KeyAction
import dev.mike.flipspot.ui.KeyActionHandler
import dev.mike.flipspot.ui.KeyRouter
import dev.mike.flipspot.ui.ListItem
import dev.mike.flipspot.ui.ListScreen
import dev.mike.flipspot.ui.RoutedKey
import dev.mike.flipspot.ui.Shortcuts
import dev.mike.flipspot.ui.say

/**
 * One activity for every library list (PLAN.md screens 2 + Liked Songs/Albums):
 *   PLAYLISTS -> PLAYLIST (songs)      LIKED (songs)      ALBUMS -> ALBUM (songs)
 *
 * Song lists start with "Play all" / "Shuffle all" rows. OK on a song plays the list from that song.
 * Left soft key: options for the selected row (Play, Shuffle, Add to queue, Pin to key, Refresh).
 */
class BrowseActivity : Activity(), KeyActionHandler {

    /** What a list row stands for. */
    private sealed class Row {
        abstract val item: ListItem

        class Action(override val item: ListItem, val run: () -> Unit) : Row()
        class OpenPlaylist(val playlist: Playlist) : Row() {
            override val item = ListItem(
                playlist.name,
                if (playlist.total > 0) "${playlist.total} songs · ${playlist.ownerName}" else playlist.ownerName,
            )
        }
        /** A Spotify-made mix (Discover Weekly, Daily Mix…): no song list available, OK plays it. */
        class Mix(val playlist: Playlist) : Row() {
            override val item = ListItem(playlist.name, playlist.ownerName)
        }
        class OpenAlbum(val album: Album) : Row() {
            override val item = ListItem(album.name, album.artists)
        }
        class Song(val track: Track, val index: Int) : Row() {
            override val item = ListItem(track.name, track.artists, dim = !track.playable)
        }
        class OpenBook(val book: Book) : Row() {
            override val item = ListItem(book.name, "${book.authors} · ${book.totalChapters} ch")
        }
        class ChapterRow(val chapter: Chapter) : Row() {
            override val item = ListItem(
                (if (chapter.inProgress) "▶ " else "") + chapter.name,
                when {
                    chapter.inProgress ->
                        "${Audiobooks.formatTime(chapter.resumeMs)} of ${Audiobooks.formatTime(chapter.durationMs)}"
                    chapter.fullyPlayed -> "✓ played · ${Audiobooks.formatTime(chapter.durationMs)}"
                    else -> Audiobooks.formatTime(chapter.durationMs)
                },
                dim = chapter.fullyPlayed || !chapter.playable,
            )
        }
    }

    /** The book shown in BOOK mode (rebuilt from extras). */
    private val book: Book? by lazy {
        val id = intent.getStringExtra(EXTRA_ID) ?: return@lazy null
        Book(
            id, intent.getStringExtra(EXTRA_NAME) ?: "", intent.getStringExtra(EXTRA_URI) ?: "",
            intent.getStringExtra(EXTRA_AUTHORS) ?: "", intent.getIntExtra(EXTRA_CHAPTERS, 0),
        )
    }
    private var chapters: List<Chapter> = emptyList()

    private lateinit var mode: String
    private lateinit var list: ListScreen
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var debugKey: TextView
    private var debuggable = false

    private var rows: List<Row> = emptyList()
    /** Playable context for song lists (playlist/album/liked collection URI). */
    private var contextUri: String? = null
    private var contextName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        list = findViewById(R.id.list)
        title = findViewById(R.id.browse_title)
        status = findViewById(R.id.browse_status)
        debugKey = findViewById(R.id.debug_key)
        debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) debugKey.visibility = View.VISIBLE

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PLAYLISTS
        contextName = intent.getStringExtra(EXTRA_NAME) ?: ""
        contextUri = intent.getStringExtra(EXTRA_URI)
        title.text = when (mode) {
            MODE_PLAYLISTS -> "Playlists"
            MODE_LIKED -> "Liked Songs"
            MODE_ALBUMS -> "Albums"
            MODE_MADE_FOR_YOU -> "Made for you"
            MODE_AUDIOBOOKS -> "Audiobooks"
            else -> contextName
        }
        list.setEmptyText("Loading…")
        list.requestFocus()
        load(force = false)
    }

    // ---- loading ----

    private fun load(force: Boolean) {
        setStatus("Updating…")
        when (mode) {
            MODE_PLAYLISTS -> Library.playlists(this, force) { u ->
                show(u) { pls -> pls.map { Row.OpenPlaylist(it) } }
            }
            MODE_PLAYLIST -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                if (!intent.getBooleanExtra(EXTRA_LISTABLE, true)) {
                    // Someone else's playlist: Spotify would answer 403, so don't ask.
                    showForbiddenPlaylist()
                    setStatus(null)
                    return
                }
                Library.playlistTracks(this, id, force) { u ->
                    if (u.forbidden && u.data == null) {
                        showForbiddenPlaylist()
                        setStatus(null)
                    } else {
                        show(u) { songRows(it) }
                    }
                }
            }
            MODE_LIKED -> {
                val app = applicationContext
                FlipSpotApp.io.execute {
                    val uid = Library.userId(app)
                    runOnUiThread { if (uid != null) contextUri = Library.likedSongsUri(uid) }
                }
                Library.liked(this, force) { u -> show(u) { songRows(it) } }
            }
            MODE_ALBUMS -> Library.albums(this, force) { u ->
                show(u) { albums -> albums.map { Row.OpenAlbum(it) } }
            }
            MODE_ALBUM -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                Library.albums(this, force) { u ->
                    show(u) { albums -> songRows(albums.firstOrNull { it.id == id }?.tracks ?: emptyList()) }
                }
            }
            MODE_MADE_FOR_YOU -> loadMadeForYou()
            MODE_AUDIOBOOKS -> Library.audiobooks(this, force) { u ->
                show(u) { books -> books.map { Row.OpenBook(it) } }
            }
            MODE_BOOK -> {
                val b = book ?: return
                Library.chapters(this, b, force) { u ->
                    val first = rows.isEmpty()
                    show(u) { chs ->
                        chapters = chs
                        bookRows(b, chs)
                    }
                    // Start on the chapter you're in, so OK continues it.
                    if (first && chapters.isNotEmpty()) {
                        list.select(BOOK_HEAD_ROWS + chapters.resumeIndex())
                    }
                }
            }
        }
    }

    private fun bookRows(b: Book, chs: List<Chapter>): List<Row> {
        val resumeLabel = if (chs.isEmpty()) "▶ Resume" else {
            val c = chs[chs.resumeIndex()]
            "▶ Resume: ${c.name}" + if (c.inProgress) " (${Audiobooks.formatTime(c.resumeMs)})" else ""
        }
        val head = listOf<Row>(Row.Action(ListItem(resumeLabel, b.authors)) { Audiobooks.resume(this, b, chapters) })
        return head + chs.map { Row.ChapterRow(it) }
    }

    /**
     * Spotify-made mixes aren't in /me/playlists and the Web API won't read them for a Development-mode
     * app, but the Spotify app's own browse tree (App Remote Content API) lists them with their URIs.
     * Shows the cached list first, then refreshes from the browse tree when connected.
     */
    private fun loadMadeForYou() {
        val app = applicationContext
        FlipSpotApp.io.execute {
            val cached = Library.madeForYouCached(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (cached != null && rows.isEmpty()) showMixes(cached.first)
                if (!SpotifyRemote.isConnected) {
                    setStatus(if (cached != null) "Spotify not connected · copy from ${ago(cached.second)}" else "Spotify not connected")
                    if (cached == null) list.setEmptyText("Connect to Spotify, then Options > Refresh")
                    return@runOnUiThread
                }
                setStatus("Updating…")
                collectMixes { mixes ->
                    if (isDestroyed) return@collectMixes
                    if (mixes == null) {
                        setStatus(if (cached != null) "Couldn't refresh · copy from ${ago(cached.second)}" else "Couldn't load from Spotify")
                        return@collectMixes
                    }
                    FlipSpotApp.io.execute { Library.saveMadeForYou(app, mixes) }
                    showMixes(mixes)
                    setStatus(null)
                }
            }
        }
    }

    private fun showMixes(mixes: List<Playlist>) {
        val keep = list.selectedIndex
        rows = mixes.map { Row.Mix(it) }
        list.setListItems(rows.map { it.item }, keep)
        if (rows.isEmpty()) list.setEmptyText("No mixes found")
    }

    /** Walks every browse section once and keeps the user's personal mixes, Discover Weekly first. */
    private fun collectMixes(done: (List<Playlist>?) -> Unit) {
        SpotifyRemote.browseRoot(com.spotify.android.appremote.api.ContentApi.ContentType.DEFAULT) { root ->
            val sections = root?.filter { it.hasChildren }
            if (sections.isNullOrEmpty()) return@browseRoot done(if (root == null) null else emptyList())
            val found = LinkedHashMap<String, com.spotify.protocol.types.ListItem>()
            var pending = sections.size
            for (s in sections) {
                SpotifyRemote.browseChildren(s, 30) { kids ->
                    kids?.filter { isPersonalMix(it.uri) }?.forEach { found.putIfAbsent(it.uri, it) }
                    if (--pending == 0) {
                        done(
                            found.values
                                .sortedWith(compareBy({ mixRank(it.title) }, { it.title }))
                                .map { k ->
                                    Playlist(
                                        id = k.uri.substringAfterLast(':'), name = k.title ?: "?", uri = k.uri,
                                        ownerName = k.subtitle?.take(80) ?: "Made for you", total = 0, listable = false,
                                    )
                                },
                        )
                    }
                }
            }
        }
    }

    // Spotify's personalised playlists: 37i9dQZEVX… (Discover Weekly, Release Radar) and 37i9dQZF1E…
    // (Daily Mixes, DJ, artist/decade radios and mixes). Editorial playlists (37i9dQZF1DX…) are skipped.
    private fun isPersonalMix(uri: String?): Boolean =
        uri != null && (uri.startsWith("spotify:playlist:37i9dQZEVX") || uri.startsWith("spotify:playlist:37i9dQZF1E"))

    private fun mixRank(title: String?): Int = when {
        title == null -> 9
        title.equals("Discover Weekly", true) -> 0
        title.equals("Release Radar", true) -> 1
        title.startsWith("Daily Mix", true) -> 2
        title.equals("DJ", true) -> 3
        else -> 4
    }

    private fun <T> show(u: LibraryUpdate<T>, toRows: (T) -> List<Row>) {
        if (isDestroyed) return
        u.data?.let { data ->
            val keep = list.selectedIndex
            rows = toRows(data)
            list.setListItems(rows.map { it.item }, keep)
            if (rows.isEmpty()) list.setEmptyText("Nothing here")
        }
        setStatus(
            when {
                !u.done -> "Updating…"
                u.error != null && u.data != null -> "${u.error} · showing copy from ${ago(u.fetchedAtMs)}"
                u.error != null -> u.error
                else -> null
            },
        )
        if (u.data == null && u.error != null) list.setEmptyText(u.error)
    }

    private fun songRows(tracks: List<Track>): List<Row> {
        val head = listOf(
            Row.Action(ListItem("▶ Play all", "${tracks.size} songs")) { playAll(shuffle = false) },
            Row.Action(ListItem("⤮ Shuffle all", "${tracks.size} songs")) { playAll(shuffle = true) },
        )
        return head + tracks.mapIndexed { i, t -> Row.Song(t, i) }
    }

    private fun showForbiddenPlaylist() {
        rows = listOf(
            Row.Action(ListItem("▶ Play playlist")) { playAll(shuffle = false) },
            Row.Action(ListItem("⤮ Shuffle playlist")) { playAll(shuffle = true) },
            Row.Action(ListItem("Song list hidden by Spotify", "Not your playlist: play only", dim = true)) {
                say("Spotify only lets FlipSpot list your own playlists")
            },
        )
        list.setListItems(rows.map { it.item })
    }

    private fun setStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun ago(ms: Long): String {
        val mins = (System.currentTimeMillis() - ms) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (24 * 60)} days ago"
        }
    }

    // ---- actions ----

    private fun playAll(shuffle: Boolean) {
        val uri = contextUri ?: return say("Still loading…")
        SpotifyRemote.playContext(uri, shuffle)
        Shortcuts.openNowPlaying(this)
    }

    private fun playSong(row: Row.Song) {
        if (!row.track.playable) return say("Not playable on Spotify")
        val ctx = contextUri
        when {
            ctx == null -> SpotifyRemote.playUri(row.track.uri)
            // App Remote ignores the index for the Liked Songs collection: start it via the Web API.
            mode == MODE_LIKED -> playLikedFrom(ctx, row)
            else -> SpotifyRemote.playFromIndex(ctx, row.index, row.track.uri)
        }
        Shortcuts.openNowPlaying(this)
    }

    private fun playLikedFrom(ctx: String, row: Row.Song) {
        val following = rows.filterIsInstance<Row.Song>()
            .filter { it.index >= row.index && it.track.playable }
            .map { it.track.uri }
        val app = applicationContext
        SpotifyRemote.setShuffle(false)
        FlipSpotApp.io.execute {
            val err = SpotifyWebApi.playFrom(app, ctx, row.track.uri, following)
            if (err != null) {
                runOnUiThread {
                    app.say("$err: playing song alone")
                    SpotifyRemote.playUri(row.track.uri)
                }
            }
        }
    }

    private fun activate(row: Row) {
        when (row) {
            is Row.Action -> row.run()
            is Row.OpenPlaylist -> {
                val p = row.playlist
                startActivity(
                    makeIntent(this, MODE_PLAYLIST, p.id, p.name, p.uri).putExtra(EXTRA_LISTABLE, p.listable),
                )
            }
            is Row.Mix -> play(row.playlist.uri, false)
            is Row.OpenBook -> openBook(this, row.book)
            is Row.ChapterRow -> book?.let { Audiobooks.playChapter(this, it, row.chapter) }
            is Row.OpenAlbum -> open(this, MODE_ALBUM, row.album.id, row.album.name, row.album.uri)
            is Row.Song -> playSong(row)
        }
    }

    private fun showOptions(row: Row?) {
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        when (row) {
            is Row.OpenPlaylist -> {
                val p = row.playlist
                opts += "Play" to { play(p.uri, false) }
                opts += "Shuffle" to { play(p.uri, true) }
                opts += "Pin to key…" to { Shortcuts.choosePinKey(this, p.uri, p.name) }
            }
            is Row.OpenBook -> {
                val b = row.book
                opts += "Resume" to { Audiobooks.resume(this, b) }
                opts += "Chapters" to { openBook(this, b) }
                opts += "Pin to key…" to { Shortcuts.choosePinKey(this, b.uri, b.name, askShuffle = false) }
            }
            is Row.ChapterRow -> {
                val b = book
                if (b != null) {
                    opts += "Play from saved spot" to { Audiobooks.playChapter(this, b, row.chapter) }
                    opts += "Play from start" to {
                        Audiobooks.playChapter(this, b, row.chapter.copy(fullyPlayed = false, resumeMs = 0))
                    }
                    opts += "Pin this book to key…" to {
                        Shortcuts.choosePinKey(this, b.uri, b.name, askShuffle = false)
                    }
                }
            }
            is Row.Mix -> {
                val p = row.playlist
                opts += "Play" to { play(p.uri, false) }
                opts += "Shuffle" to { play(p.uri, true) }
                opts += "Pin to key…" to { Shortcuts.choosePinKey(this, p.uri, p.name) }
            }
            is Row.OpenAlbum -> {
                val a = row.album
                opts += "Play" to { play(a.uri, false) }
                opts += "Shuffle" to { play(a.uri, true) }
                opts += "Pin to key…" to { Shortcuts.choosePinKey(this, a.uri, a.name) }
            }
            is Row.Song -> {
                opts += "Play from here" to { playSong(row) }
                if (row.track.playable) opts += "Add to queue" to {
                    SpotifyRemote.queue(row.track.uri)
                    say("Queued")
                }
            }
            else -> Unit
        }
        val ctx = contextUri
        if (ctx != null && mode in setOf(MODE_PLAYLIST, MODE_LIKED, MODE_ALBUM)) {
            opts += "Pin “${title.text}” to key…" to { Shortcuts.choosePinKey(this, ctx, title.text.toString()) }
        }
        opts += "Refresh list" to { load(force = true) }
        AlertDialog.Builder(this)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .show()
    }

    private fun play(uri: String, shuffle: Boolean) {
        SpotifyRemote.playContext(uri, shuffle)
        Shortcuts.openNowPlaying(this)
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = KeyRouter.dispatch(event, SCREEN, this)
        if (debuggable) debugKey.text = KeyRouter.lastDescription
        return consumed || super.dispatchKeyEvent(event)
    }

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (key.action) {
            KeyAction.UP -> list.move(-1)
            KeyAction.DOWN -> list.move(+1)
            KeyAction.LEFT -> list.page(-1)
            KeyAction.RIGHT -> list.page(+1)
            KeyAction.BACK -> return false
            else -> if (!repeat) when (key.action) {
                KeyAction.OK -> rows.getOrNull(list.selectedIndex)?.let(::activate)
                KeyAction.SOFT_LEFT -> showOptions(rows.getOrNull(list.selectedIndex))
                KeyAction.SOFT_RIGHT -> Shortcuts.openNowPlaying(this)
                KeyAction.PLAY_PAUSE -> SpotifyRemote.togglePlayPause()
                KeyAction.DIGIT -> if (key.digit == 0) say("Search: M4") else Shortcuts.playPin(this, key.digit)
                KeyAction.STAR -> return false
                else -> Unit
            }
        }
        return true
    }

    companion object {
        private const val SCREEN = "Browse"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URI = "uri"

        const val MODE_PLAYLISTS = "playlists"
        const val MODE_PLAYLIST = "playlist"
        const val MODE_LIKED = "liked"
        const val MODE_ALBUMS = "albums"
        const val MODE_ALBUM = "album"
        const val MODE_MADE_FOR_YOU = "made_for_you"
        const val MODE_AUDIOBOOKS = "audiobooks"
        const val MODE_BOOK = "book"
        private const val EXTRA_AUTHORS = "authors"
        private const val EXTRA_CHAPTERS = "chapters"
        /** Rows above the chapter list in BOOK mode ("Resume"). */
        private const val BOOK_HEAD_ROWS = 1

        fun openBook(ctx: Context, b: Book) {
            ctx.startActivity(
                makeIntent(ctx, MODE_BOOK, b.id, b.name, b.uri)
                    .putExtra(EXTRA_AUTHORS, b.authors)
                    .putExtra(EXTRA_CHAPTERS, b.totalChapters),
            )
        }

        private const val EXTRA_LISTABLE = "listable"

        fun open(ctx: Context, mode: String, id: String? = null, name: String? = null, uri: String? = null) {
            ctx.startActivity(makeIntent(ctx, mode, id, name, uri))
        }

        private fun makeIntent(ctx: Context, mode: String, id: String?, name: String?, uri: String?): Intent =
            Intent(ctx, BrowseActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_URI, uri)
    }
}
