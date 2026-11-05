package dev.mike.flipspot.api

import android.content.Context
import android.os.Build
import android.util.Log
import dev.mike.flipspot.auth.SpotifyAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small Web API client on HttpURLConnection + org.json (both in the platform, so no APK cost).
 * Blocking: call off the main thread. 5 s timeouts, one retry after a 401 (token refresh) or 429.
 *
 * Endpoint notes, probed 2026-11-04 against a Development-mode app:
 * - Playlist contents: GET /playlists/{id}/items (entries under "item"). The old /tracks is 403.
 * - Playlists owned by other users: /items returns 403 (Development-mode restriction). They can
 *   still be played by URI through App Remote.
 * - Playlist objects report their size under "items.total" (formerly "tracks.total").
 */
object SpotifyWebApi {
    private const val TAG = "FlipSpot"
    private const val BASE = "https://api.spotify.com/v1"
    private const val TIMEOUT_MS = 5_000
    private const val PREFS = "device"
    private const val KEY_PHONE_ID = "phone_device_id"

    sealed class Result {
        data class Ok(val json: JSONObject?) : Result() // null body for 204
        object NotSignedIn : Result()
        data class Http(val code: Int, val message: String) : Result()
        data class Network(val error: Exception) : Result()
    }

    /** GET [pathOrUrl] (relative to /v1, or an absolute "next" URL from a paging object). */
    fun get(context: Context, pathOrUrl: String): Result = request(context, "GET", pathOrUrl, null)

    /** PUT with a JSON body (player commands). */
    fun put(context: Context, path: String, body: JSONObject): Result = request(context, "PUT", path, body)

    private fun request(context: Context, method: String, pathOrUrl: String, body: JSONObject?): Result {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else BASE + pathOrUrl
        var retried = false
        var freshConnection = false
        while (true) {
            val token = SpotifyAuth.validAccessToken(context) ?: return Result.NotSignedIn
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $token")
                    // Retry path: don't reuse a pooled keep-alive socket (see catch below).
                    if (freshConnection) setRequestProperty("Connection", "close")
                }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                when {
                    code == 204 -> return Result.Ok(null)
                    code in 200..299 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        return Result.Ok(if (body.isBlank()) null else JSONObject(body))
                    }
                    code == 401 && !retried -> {
                        Log.w(TAG, "WebApi: 401 on $url, refreshing token")
                        SpotifyAuth.invalidateAccessToken(context)
                        retried = true
                    }
                    code == 429 && !retried -> {
                        val wait = (conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 2).coerceIn(1, 10)
                        Log.w(TAG, "WebApi: 429, retrying in ${wait}s")
                        Thread.sleep(wait * 1000)
                        retried = true
                    }
                    else -> {
                        val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        Log.w(TAG, "WebApi: HTTP $code on $url $err")
                        return Result.Http(code, err)
                    }
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "WebApi: $url failed (${e.javaClass.simpleName}: ${e.message})")
                // A pooled keep-alive connection can go stale while idle and then hang until the read
                // timeout (seen on device with a healthy network). Retry once on a new connection.
                if (!freshConnection && e !is java.net.UnknownHostException) {
                    freshConnection = true
                    continue
                }
                return Result.Network(e)
            } catch (e: Exception) {
                Log.w(TAG, "WebApi: $url failed (${e.javaClass.simpleName}: ${e.message})")
                return Result.Network(e)
            }
        }
    }

    /**
     * Follows "next" links from [firstPath], passing each page's "items" entries to [onItem].
     * Returns null on success, or the failing [Result].
     */
    fun forEachPaged(context: Context, firstPath: String, onItem: (JSONObject) -> Unit): Result? {
        var next: String? = firstPath
        while (next != null) {
            val r = get(context, next)
            if (r !is Result.Ok) return r
            val page = r.json ?: return null
            val items = page.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) items.optJSONObject(i)?.let(onItem)
            next = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return null
    }

    // ---- playback via Web API (where App Remote falls short) ----

    /**
     * Starts [contextUri] at [trackUri] on the active device. App Remote's skipToIndex ignores the index
     * for the Liked Songs collection (verified on device), so Liked Songs uses this instead.
     * If Spotify rejects an offset into that context, plays [followingUris] (the song and those after it)
     * as a plain list. Returns null on success or a short error.
     */
    fun playFrom(context: Context, contextUri: String, trackUri: String, followingUris: List<String>): String? {
        val withContext = put(
            context, "/me/player/play",
            JSONObject().put("context_uri", contextUri).put("offset", JSONObject().put("uri", trackUri)),
        )
        if (withContext is Result.Ok) {
            Log.i(TAG, "WebApi: play $contextUri from $trackUri ok")
            return null
        }
        Log.w(TAG, "WebApi: context+offset play failed ($withContext), trying uri list")
        if (withContext is Result.Http && withContext.code == 404) return "No active Spotify device"
        val arr = org.json.JSONArray()
        followingUris.take(100).forEach { arr.put(it) }
        return when (val r = put(context, "/me/player/play", JSONObject().put("uris", arr))) {
            is Result.Ok -> {
                Log.i(TAG, "WebApi: play ${arr.length()} uris ok")
                null
            }
            is Result.Http -> if (r.message.contains("PREMIUM", ignoreCase = true)) "Needs Spotify Premium" else "Spotify error ${r.code}"
            is Result.Network -> "Offline"
            is Result.NotSignedIn -> "Not signed in"
        }
    }

    // ---- active Connect device (Now Playing "Play here") ----

    data class Device(val id: String?, val name: String, val type: String, val isThisPhone: Boolean)

    /** Result of a lookup: [device] is null when nothing is active (HTTP 204). */
    data class ActiveDevice(val ok: Boolean, val device: Device?)

    /** GET /v1/me/player. ok=false on network/auth failure (caller should keep the last known value). */
    fun activeDevice(context: Context): ActiveDevice {
        val r = get(context, "/me/player")
        if (r !is Result.Ok) return ActiveDevice(false, null)
        val d = r.json?.optJSONObject("device") ?: return ActiveDevice(true, null)
        val id = d.optString("id").ifEmpty { null }
        val name = d.optString("name", "?")
        val type = d.optString("type", "?")
        return ActiveDevice(true, Device(id, name, type, isThisPhone(context, id, name, type)))
    }

    /**
     * After we switch playback to the phone, the active device *is* the phone: remember its Connect id
     * so later lookups don't depend on guessing from the name.
     */
    fun rememberPhoneDevice(context: Context, device: Device?) {
        val id = device?.id ?: return
        prefs(context).edit().putString(KEY_PHONE_ID, id).apply()
        Log.i(TAG, "WebApi: phone's Connect device is '${device.name}' (${device.type})")
    }

    private fun isThisPhone(context: Context, id: String?, name: String, type: String): Boolean {
        val known = prefs(context).getString(KEY_PHONE_ID, null)
        if (known != null) return id == known
        // Before the first switch: Spotify usually names the local device after the model.
        return type.equals("Smartphone", ignoreCase = true) && name.contains(Build.MODEL, ignoreCase = true)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
