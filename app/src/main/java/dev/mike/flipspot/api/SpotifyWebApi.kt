package dev.mike.flipspot.api

import android.content.Context
import android.os.Build
import android.util.Log
import dev.mike.flipspot.auth.SpotifyAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Web API client. M1 only needs the active Connect device; M2 replaces the plumbing with
 * OkHttp + kotlinx.serialization for the library calls. Blocking: call off the main thread.
 */
object SpotifyWebApi {
    private const val TAG = "FlipSpot"
    private const val TIMEOUT_MS = 5_000
    private const val PREFS = "device"
    private const val KEY_PHONE_ID = "phone_device_id"

    data class Device(val id: String?, val name: String, val type: String, val isThisPhone: Boolean)

    /** Result of a lookup: [device] is null when nothing is active (HTTP 204). */
    data class ActiveDevice(val ok: Boolean, val device: Device?)

    /** GET /v1/me/player. ok=false on network/auth failure (caller should keep the last known value). */
    fun activeDevice(context: Context): ActiveDevice {
        val token = SpotifyAuth.validAccessToken(context) ?: return ActiveDevice(false, null)
        return try {
            val conn = (URL("https://api.spotify.com/v1/me/player").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
            }
            when (val code = conn.responseCode) {
                204 -> ActiveDevice(true, null)
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val d = json.optJSONObject("device") ?: return ActiveDevice(true, null)
                    val id = d.optString("id").ifEmpty { null }
                    val name = d.optString("name", "?")
                    val type = d.optString("type", "?")
                    ActiveDevice(true, Device(id, name, type, isThisPhone(context, id, name, type)))
                }
                else -> {
                    Log.w(TAG, "WebApi: /me/player HTTP $code")
                    ActiveDevice(false, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebApi: /me/player failed (${e.javaClass.simpleName})")
            ActiveDevice(false, null)
        }
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
