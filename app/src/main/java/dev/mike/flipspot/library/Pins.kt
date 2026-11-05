package dev.mike.flipspot.library

import android.content.Context
import org.json.JSONObject

/** Number-key shortcuts 1-9: each holds a playable context (playlist, album, liked songs, audiobook). */
object Pins {
    /** [shuffle]: true/false forces shuffle on/off when played; null leaves Spotify's setting alone. */
    data class Pin(val uri: String, val name: String, val shuffle: Boolean? = null)

    private const val PREFS = "pins"

    fun get(ctx: Context, digit: Int): Pin? {
        val raw = prefs(ctx).getString(key(digit), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Pin(
                o.getString("uri"),
                o.getString("name"),
                if (o.has("shuffle")) o.getBoolean("shuffle") else null,
            )
        }.getOrNull()
    }

    fun set(ctx: Context, digit: Int, pin: Pin) {
        val o = JSONObject().put("uri", pin.uri).put("name", pin.name)
        pin.shuffle?.let { o.put("shuffle", it) }
        prefs(ctx).edit().putString(key(digit), o.toString()).apply()
    }

    fun clear(ctx: Context, digit: Int) {
        prefs(ctx).edit().remove(key(digit)).apply()
    }

    private fun key(digit: Int): String {
        require(digit in 1..9)
        return "pin_$digit"
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
