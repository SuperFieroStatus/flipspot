package dev.mike.flipspot.ui

import android.util.Log
import android.view.KeyEvent

/** Logical actions, independent of which physical keycode the Flip 2 happens to send. */
enum class KeyAction {
    UP, DOWN, LEFT, RIGHT, OK, BACK,
    SOFT_LEFT, SOFT_RIGHT,
    PLAY_PAUSE, // '#'
    STAR,       // '*'
    DIGIT,      // 0-9, value in [RoutedKey.digit]
}

data class RoutedKey(val action: KeyAction, val digit: Int = -1)

/** Implemented by each screen. Return true if the action was handled (the key is then consumed). */
fun interface KeyActionHandler {
    fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean
}

/**
 * Single place that maps raw keycodes to [KeyAction]s and logs every key event.
 *
 * Call [dispatch] first thing from Activity.dispatchKeyEvent so we see keys before any
 * view consumes them. Keys we don't map (volume, End/power, Call, Home) are logged and
 * passed straight through to the system.
 *
 * Verified on TCL Flip 2 (4058G, Android 11) 2026-11-04. Keypad = /dev/input/event2 (deviceId 3),
 * side buttons = /dev/input/event1 (deviceId 4).
 *
 *   Key           kernel             scan  Android keycode
 *   D-pad U/D/L/R KEY_UP/DOWN/LEFT/RIGHT 103/108/105/106  19/20/21/22 DPAD_*
 *   OK            KEY_ENTER           28   23 DPAD_CENTER
 *   Left soft     KEY_MENU           139    1 SOFT_LEFT
 *   Right soft    KEY_B               48    2 SOFT_RIGHT
 *   Back          KEY_BACK           158    4 BACK
 *   Call          KEY_SEND           231    5 CALL        (pass-through)
 *   * / #         KEY_NUMERIC_STAR/POUND 522/523  17/18 STAR/POUND
 *   0..9          KEY_0..KEY_9       11,2..10   7..16
 *   Vol up/down   KEY_VOLUMEUP/DOWN  115/114  24/25       (pass-through)
 *   End           KEY_POWER (event1)  -    never delivered: system sends Home
 *                                          (end_button_behavior=1)
 */
object KeyRouter {
    const val TAG = "FlipSpot"

    /** Last formatted event, so a debug overlay can show it without logcat. */
    var lastDescription: String = ""
        private set

    fun route(keyCode: Int): RoutedKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> RoutedKey(KeyAction.UP)
        KeyEvent.KEYCODE_DPAD_DOWN -> RoutedKey(KeyAction.DOWN)
        KeyEvent.KEYCODE_DPAD_LEFT -> RoutedKey(KeyAction.LEFT)
        KeyEvent.KEYCODE_DPAD_RIGHT -> RoutedKey(KeyAction.RIGHT)

        KeyEvent.KEYCODE_DPAD_CENTER -> RoutedKey(KeyAction.OK)

        KeyEvent.KEYCODE_BACK -> RoutedKey(KeyAction.BACK)

        KeyEvent.KEYCODE_SOFT_LEFT -> RoutedKey(KeyAction.SOFT_LEFT)
        KeyEvent.KEYCODE_SOFT_RIGHT -> RoutedKey(KeyAction.SOFT_RIGHT)

        KeyEvent.KEYCODE_POUND -> RoutedKey(KeyAction.PLAY_PAUSE)
        KeyEvent.KEYCODE_STAR -> RoutedKey(KeyAction.STAR)

        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            RoutedKey(KeyAction.DIGIT, keyCode - KeyEvent.KEYCODE_0)

        // Deliberately unmapped (pass-through): VOLUME_UP/DOWN, ENDCALL, POWER, CALL, HOME.
        else -> null
    }

    /**
     * Logs [event], routes it, and offers it to [handler] on ACTION_DOWN (including repeats).
     * The matching ACTION_UP of a handled key is consumed too, so views never see half a press.
     * Returns true if the event was consumed.
     */
    fun dispatch(event: KeyEvent, screen: String, handler: KeyActionHandler): Boolean {
        val routed = route(event.keyCode)
        var consumed = false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (routed != null) {
                consumed = handler.onKeyAction(routed, event)
                if (consumed) downConsumed.add(event.keyCode) else downConsumed.remove(event.keyCode)
            }
            KeyEvent.ACTION_UP -> consumed = downConsumed.remove(event.keyCode)
        }
        lastDescription = describe(event, routed)
        Log.d(TAG, "$screen ${lastDescription}${if (consumed) " [handled]" else " [pass]"}")
        return consumed
    }

    private val downConsumed = HashSet<Int>()

    fun describe(event: KeyEvent, routed: RoutedKey? = route(event.keyCode)): String {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "MULTI"
        }
        val target = when {
            routed == null -> "-"
            routed.action == KeyAction.DIGIT -> "DIGIT_${routed.digit}"
            else -> routed.action.name
        }
        return "key $action code=${event.keyCode} ${KeyEvent.keyCodeToString(event.keyCode)} " +
            "scan=${event.scanCode} rep=${event.repeatCount} " +
            "meta=0x${Integer.toHexString(event.metaState)} flags=0x${Integer.toHexString(event.flags)} " +
            "dev=${event.deviceId} -> $target"
    }
}
