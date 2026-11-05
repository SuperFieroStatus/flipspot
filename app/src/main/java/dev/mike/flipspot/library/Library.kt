package dev.mike.flipspot.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.mike.flipspot.FlipSpotApp
import dev.mike.flipspot.api.SpotifyWebApi
import dev.mike.flipspot.api.SpotifyWebApi.Result
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(
    val name: String,
    val artists: String,
    val uri: String,
    val durationMs: Long,
    val playable: Boolean,
)

data class Playlist(
    val id: String,
    val name: String,
    val uri: String,
    val ownerName: String,
    val total: Int,
    /** Owned or collaborative: Spotify lets a Development-mode app list its songs. */
    val listable: Boolean,
)

data class Album(
    val id: String,
    val name: String,
    val uri: String,
    val artists: String,
    val tracks: List<Track>,
)

/** One delivery to a screen: cached data first (if any), then the network result. */
data class LibraryUpdate<T>(
    val data: T?,
    val fetchedAtMs: Long,
    val fromNetwork: Boolean,
    /** Null on success. */
    val error: String?,
    /** Spotify returned 403 (e.g. someone else's playlist). */
    val forbidden: Boolean,
    /** No further update follows. */
    val done: Boolean,
)

/**
 * Library browsing data: Web API -> JSON files in filesDir/library -> screens.
 *
 * Used instead of PLAN.md's Room: no annotation processing or extra dependency, and a few thousand rows
 * of JSON parse in well under a second here. Cache is shown immediately; it is refreshed in the background
 * when older than [MAX_AGE_MS] (or on demand), and kept when offline (PLAN.md section 5).
 */
object Library {
    private const val TAG = "FlipSpot"
    const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    // ---- public loaders (callbacks on the main thread) ----

    fun playlists(ctx: Context, force: Boolean, cb: (LibraryUpdate<List<Playlist>>) -> Unit) =
        load(ctx, "playlists", force, ::decodePlaylists, ::fetchPlaylists, cb)

    fun playlistTracks(ctx: Context, playlistId: String, force: Boolean, cb: (LibraryUpdate<List<Track>>) -> Unit) =
        load(ctx, "pl_$playlistId", force, ::decodeTracks, { c -> fetchPlaylistTracks(c, playlistId) }, cb)

    fun liked(ctx: Context, force: Boolean, cb: (LibraryUpdate<List<Track>>) -> Unit) =
        load(ctx, "liked", force, ::decodeTracks, ::fetchLiked, cb)

    fun albums(ctx: Context, force: Boolean, cb: (LibraryUpdate<List<Album>>) -> Unit) =
        load(ctx, "albums", force, ::decodeAlbums, ::fetchAlbums, cb)

    /** Spotify user id (cached), needed for the Liked Songs context URI. Blocking. */
    fun userId(ctx: Context): String? {
        val prefs = ctx.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
        prefs.getString("user_id", null)?.let { return it }
        val r = SpotifyWebApi.get(ctx, "/me")
        val id = (r as? Result.Ok)?.json?.optString("id")?.ifEmpty { null } ?: return null
        prefs.edit().putString("user_id", id).apply()
        return id
    }

    fun likedSongsUri(userId: String) = "spotify:user:$userId:collection"

    // ---- "Made for you": found through App Remote's browse API, so the screen fetches; we only cache ----

    /** Cached Made-for-you mixes and when they were found, or null. Blocking (io thread). */
    fun madeForYouCached(ctx: Context): Pair<List<Playlist>, Long>? {
        val json = readCache(ctx, "made_for_you") ?: return null
        return runCatching { decodePlaylists(json) to json.optLong("fetched_at_ms") }.getOrNull()
    }

    fun saveMadeForYou(ctx: Context, mixes: List<Playlist>) {
        val arr = JSONArray()
        mixes.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("uri", p.uri)
                    .put("owner", p.ownerName).put("total", p.total).put("listable", p.listable),
            )
        }
        writeCache(ctx, "made_for_you", JSONObject().put("items", arr).put("fetched_at_ms", System.currentTimeMillis()))
    }

    /** Deletes every cached file (Settings > clear cache, M4). */
    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    // ---- engine ----

    private class Fetched(val json: JSONObject?, val error: String?, val forbidden: Boolean = false)

    private fun <T> load(
        ctx: Context,
        key: String,
        force: Boolean,
        decode: (JSONObject) -> T,
        fetch: (Context) -> Fetched,
        cb: (LibraryUpdate<T>) -> Unit,
    ) {
        val app = ctx.applicationContext
        FlipSpotApp.io.execute {
            val cachedJson = readCache(app, key)
            val cachedAt = cachedJson?.optLong("fetched_at_ms") ?: 0L
            val cached = cachedJson?.let { runCatching { decode(it) }.getOrNull() }
            val stale = cached == null || force || System.currentTimeMillis() - cachedAt > MAX_AGE_MS
            if (cached != null) {
                post(cb, LibraryUpdate(cached, cachedAt, false, null, false, done = !stale))
            }
            if (!stale) return@execute

            val started = System.currentTimeMillis()
            val f = fetch(app)
            if (f.json != null) {
                f.json.put("fetched_at_ms", System.currentTimeMillis())
                writeCache(app, key, f.json)
                Log.i(TAG, "Library: $key fetched in ${System.currentTimeMillis() - started} ms")
                post(cb, LibraryUpdate(decode(f.json), System.currentTimeMillis(), true, null, false, true))
            } else {
                Log.w(TAG, "Library: $key fetch failed: ${f.error}")
                post(cb, LibraryUpdate(cached, cachedAt, false, f.error, f.forbidden, true))
            }
        }
    }

    private fun <T> post(cb: (LibraryUpdate<T>) -> Unit, u: LibraryUpdate<T>) = main.post { cb(u) }

    private fun describe(r: Result): Fetched = when (r) {
        is Result.NotSignedIn -> Fetched(null, "Not signed in")
        is Result.Network -> Fetched(null, "Offline")
        is Result.Http -> Fetched(null, "Spotify error ${r.code}", forbidden = r.code == 403)
        is Result.Ok -> Fetched(null, "Empty response")
    }

    // ---- fetchers (io thread) ----

    private fun fetchPlaylists(ctx: Context): Fetched {
        val me = userId(ctx)
        val out = JSONArray()
        val err = SpotifyWebApi.forEachPaged(ctx, "/me/playlists?limit=50") { p ->
            val owner = p.optJSONObject("owner")
            val ownerId = owner?.optString("id") ?: ""
            // Size moved from "tracks.total" to "items.total"; accept either.
            val total = (p.optJSONObject("items") ?: p.optJSONObject("tracks"))?.optInt("total") ?: 0
            out.put(
                JSONObject()
                    .put("id", p.optString("id"))
                    .put("name", p.optString("name"))
                    .put("uri", p.optString("uri"))
                    .put("owner", owner?.optString("display_name")?.ifEmpty { null } ?: ownerId)
                    .put("total", total)
                    .put("listable", ownerId == me || p.optBoolean("collaborative")),
            )
        }
        return if (err != null) describe(err) else Fetched(JSONObject().put("items", out), null)
    }

    private fun fetchPlaylistTracks(ctx: Context, id: String): Fetched {
        val out = JSONArray()
        val fields = "total,next,items(is_local,item(type,name,uri,duration_ms,is_playable,artists(name),show(name)))"
        val err = SpotifyWebApi.forEachPaged(ctx, "/playlists/$id/items?limit=100&fields=$fields") { e ->
            // Keep an entry for every position (even removed/local ones) so list index == playlist index,
            // which App Remote's skipToIndex relies on.
            out.put(encodeTrack(e.optJSONObject("item"), e.optBoolean("is_local")))
        }
        return if (err != null) describe(err) else Fetched(JSONObject().put("items", out), null)
    }

    private fun fetchLiked(ctx: Context): Fetched {
        userId(ctx) // warm the cache for the context URI
        val out = JSONArray()
        val fields = "total,next,items(track(name,uri,duration_ms,is_playable,artists(name)))"
        val err = SpotifyWebApi.forEachPaged(ctx, "/me/tracks?limit=50&fields=$fields") { e ->
            out.put(encodeTrack(e.optJSONObject("track"), false))
        }
        return if (err != null) describe(err) else Fetched(JSONObject().put("items", out), null)
    }

    private fun fetchAlbums(ctx: Context): Fetched {
        val out = JSONArray()
        val err = SpotifyWebApi.forEachPaged(ctx, "/me/albums?limit=50") { e ->
            val a = e.optJSONObject("album") ?: return@forEachPaged
            val tracks = JSONArray()
            // /me/albums embeds the first 50 tracks; albums longer than that are truncated here.
            a.optJSONObject("tracks")?.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) tracks.put(encodeTrack(arr.optJSONObject(i), false))
            }
            out.put(
                JSONObject()
                    .put("id", a.optString("id"))
                    .put("name", a.optString("name"))
                    .put("uri", a.optString("uri"))
                    .put("artists", artistNames(a))
                    .put("tracks", tracks),
            )
        }
        return if (err != null) describe(err) else Fetched(JSONObject().put("items", out), null)
    }

    // ---- JSON <-> model ----

    private fun artistNames(o: JSONObject): String {
        val arr = o.optJSONArray("artists") ?: return o.optJSONObject("show")?.optString("name") ?: ""
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.ifEmpty { null } }
            .joinToString(", ")
    }

    private fun encodeTrack(t: JSONObject?, isLocal: Boolean): JSONObject {
        if (t == null) return JSONObject().put("n", "(unavailable)").put("a", "").put("u", "").put("d", 0).put("p", false)
        val uri = t.optString("uri")
        return JSONObject()
            .put("n", t.optString("name").ifEmpty { "(untitled)" })
            .put("a", artistNames(t))
            .put("u", uri)
            .put("d", t.optLong("duration_ms"))
            .put("p", !isLocal && uri.startsWith("spotify:") && t.optBoolean("is_playable", true))
    }

    private fun decodeTracks(o: JSONObject): List<Track> = decodeTrackArray(o.getJSONArray("items"))

    private fun decodeTrackArray(arr: JSONArray): List<Track> = (0 until arr.length()).map { i ->
        val t = arr.getJSONObject(i)
        Track(t.optString("n"), t.optString("a"), t.optString("u"), t.optLong("d"), t.optBoolean("p"))
    }

    private fun decodePlaylists(o: JSONObject): List<Playlist> {
        val arr = o.getJSONArray("items")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Playlist(
                p.optString("id"), p.optString("name"), p.optString("uri"),
                p.optString("owner"), p.optInt("total"), p.optBoolean("listable"),
            )
        }
    }

    private fun decodeAlbums(o: JSONObject): List<Album> {
        val arr = o.getJSONArray("items")
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            Album(
                a.optString("id"), a.optString("name"), a.optString("uri"),
                a.optString("artists"), decodeTrackArray(a.optJSONArray("tracks") ?: JSONArray()),
            )
        }
    }

    // ---- files ----

    private fun dir(ctx: Context) = File(ctx.filesDir, "library").apply { mkdirs() }

    private fun readCache(ctx: Context, key: String): JSONObject? {
        val f = File(dir(ctx), "$key.json")
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText()) }.onFailure { f.delete() }.getOrNull()
    }

    private fun writeCache(ctx: Context, key: String, json: JSONObject) {
        val f = File(dir(ctx), "$key.json")
        val tmp = File(dir(ctx), "$key.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
