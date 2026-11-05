package dev.mike.flipspot.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import dev.mike.flipspot.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sign-in and token lifecycle.
 *
 * - In-app: Spotify auth library -> native consent screen inside the Spotify app (PKCE handled by the
 *   library; needs one FlipMouse tap on "Agree"). Returns access + refresh token.
 * - Fallback: scripts/get-token.ps1 runs PKCE in a desktop browser and `adb push`es token.json to
 *   getExternalFilesDir(null); [importTokenFile] picks it up on launch and deletes it.
 * - Refresh: plain PKCE refresh (no client secret) against accounts.spotify.com.
 *
 * Network calls here are blocking: call them off the main thread. 5 s timeouts per PLAN.md.
 */
object SpotifyAuth {
    private const val TAG = "FlipSpot"
    const val REQUEST_CODE = 0x5107

    val SCOPES = arrayOf(
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "app-remote-control",
        "streaming",
    )

    const val TOKEN_FILE = "token.json"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val TIMEOUT_MS = 5_000

    fun hasClientId(): Boolean = BuildConfig.SPOTIFY_CLIENT_ID.isNotEmpty()

    fun isSignedIn(context: Context): Boolean = TokenStore.load(context) != null

    /** Starts the in-app consent flow. Result arrives in the activity's onActivityResult. */
    fun startSignIn(activity: Activity) {
        val request = AuthorizationRequest.Builder(
            BuildConfig.SPOTIFY_CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            BuildConfig.SPOTIFY_REDIRECT_URI,
        ).setScopes(SCOPES).build()
        Log.i(TAG, "Auth: opening Spotify login activity")
        AuthorizationClient.openLoginActivity(activity, REQUEST_CODE, request)
    }

    /** Returns a user-facing message describing the outcome. */
    fun handleSignInResult(context: Context, resultCode: Int, data: Intent?): String {
        val response = AuthorizationClient.getResponse(resultCode, data)
        Log.i(TAG, "Auth: response type=${response.type} error=${response.error} " +
            "hasAccess=${response.accessToken != null} hasRefresh=${response.refreshToken != null} " +
            "expiresIn=${response.expiresIn}")
        return when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                TokenStore.save(
                    context,
                    Tokens(
                        accessToken = response.accessToken!!,
                        refreshToken = response.refreshToken,
                        expiresAtMs = System.currentTimeMillis() + response.expiresIn * 1000L,
                        scope = SCOPES.joinToString(" "),
                    ),
                )
                if (response.refreshToken != null) "Signed in" else "Signed in (no refresh token: re-auth in 1 h)"
            }
            AuthorizationResponse.Type.CANCELLED -> "Sign-in cancelled"
            AuthorizationResponse.Type.ERROR -> "Sign-in failed: ${response.error}"
            else -> "Sign-in failed (${response.type})"
        }
    }

    /**
     * Imports token.json pushed by scripts/get-token.ps1 (Spotify token-endpoint JSON, plus optional
     * obtained_at_ms). Deletes the file afterwards because it holds a refresh token.
     */
    fun importTokenFile(context: Context): Boolean {
        val file = File(context.getExternalFilesDir(null) ?: return false, TOKEN_FILE)
        if (!file.exists()) return false
        return try {
            val json = JSONObject(file.readText())
            val obtainedAt = json.optLong("obtained_at_ms", System.currentTimeMillis())
            TokenStore.save(
                context,
                Tokens(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token").ifEmpty { null },
                    expiresAtMs = obtainedAt + json.optLong("expires_in", 3600) * 1000L,
                    scope = json.optString("scope").ifEmpty { null },
                ),
            )
            Log.i(TAG, "Auth: imported $TOKEN_FILE (scope=${json.optString("scope")})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auth: could not import $TOKEN_FILE", e)
            false
        } finally {
            if (!file.delete()) Log.w(TAG, "Auth: could not delete $TOKEN_FILE")
        }
    }

    /** A non-expired access token, refreshing if needed. Null if signed out or refresh failed. */
    @Synchronized
    fun validAccessToken(context: Context): String? {
        val current = TokenStore.load(context) ?: return null
        if (!current.isExpired()) return current.accessToken
        val refresh = current.refreshToken ?: run {
            Log.w(TAG, "Auth: access token expired and no refresh token; sign in again")
            return null
        }
        return try {
            val json = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                ),
            )
            val fresh = Tokens(
                accessToken = json.getString("access_token"),
                // Spotify may rotate the refresh token; keep the old one if it doesn't.
                refreshToken = json.optString("refresh_token").ifEmpty { refresh },
                expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L,
                scope = json.optString("scope").ifEmpty { current.scope },
            )
            TokenStore.save(context, fresh)
            Log.i(TAG, "Auth: refreshed access token")
            fresh.accessToken
        } catch (e: HttpError) {
            Log.e(TAG, "Auth: refresh failed HTTP ${e.code}: ${e.body}")
            if (e.code == 400) TokenStore.clear(context) // invalid_grant: refresh token revoked
            null
        } catch (e: Exception) {
            Log.w(TAG, "Auth: refresh failed (offline?)", e)
            null
        }
    }

    /** Sanity check that the token works: GET /v1/me. Returns "display name (product)" or null. */
    fun whoAmI(context: Context): String? {
        val token = validAccessToken(context) ?: return null
        return try {
            val conn = (URL("https://api.spotify.com/v1/me").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "Auth: /v1/me HTTP $code")
                return null
            }
            val me = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            "${me.optString("display_name", me.optString("id"))} (${me.optString("product", "?")})"
        } catch (e: Exception) {
            Log.w(TAG, "Auth: /v1/me failed", e)
            null
        }
    }

    fun signOut(context: Context) = TokenStore.clear(context)

    private class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code, text)
        return JSONObject(text)
    }
}
