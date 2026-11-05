package dev.mike.flipspot

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import dev.mike.flipspot.ui.KeyAction
import dev.mike.flipspot.ui.KeyActionHandler
import dev.mike.flipspot.ui.KeyRouter
import dev.mike.flipspot.ui.ListScreen
import dev.mike.flipspot.ui.RoutedKey

class HomeActivity : Activity(), KeyActionHandler {

    private data class MenuItem(val label: String, val milestone: String)

    private val menu = listOf(
        MenuItem("Playlists", "M2"),
        MenuItem("Audiobooks", "M3"),
        MenuItem("Liked Songs", "M2"),
        MenuItem("Albums", "M2"),
        MenuItem("Search", "M4"),
        MenuItem("Settings", "M4"),
    )

    private lateinit var list: ListScreen
    private lateinit var debugKey: TextView
    private var debuggable = false
    private var toast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        list = findViewById(R.id.list)
        debugKey = findViewById(R.id.debug_key)
        debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) debugKey.visibility = View.VISIBLE

        list.setItems(menu.map { it.label }, savedInstanceState?.getInt(STATE_SELECTED) ?: 0)
        list.requestFocus()

        val dm = resources.displayMetrics
        Log.i(
            KeyRouter.TAG,
            "HomeActivity created: ${dm.widthPixels}x${dm.heightPixels}px densityDpi=${dm.densityDpi} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString(",")} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}",
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, list.selectedIndex)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = KeyRouter.dispatch(event, SCREEN, this)
        if (debuggable) debugKey.text = KeyRouter.lastDescription
        return consumed || super.dispatchKeyEvent(event)
    }

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        // Only navigation keys auto-repeat; everything else fires once per press.
        val repeat = event.repeatCount > 0
        return when (key.action) {
            KeyAction.UP -> { list.move(-1); true }
            KeyAction.DOWN -> { list.move(+1); true }
            KeyAction.LEFT -> { list.page(-1); true }
            KeyAction.RIGHT -> { list.page(+1); true }
            KeyAction.OK -> {
                if (!repeat) menu[list.selectedIndex].let { say("${it.label}: coming in ${it.milestone}") }
                true
            }
            KeyAction.SOFT_LEFT -> { if (!repeat) say("Options menu: M2"); true }
            KeyAction.SOFT_RIGHT -> { if (!repeat) say("Now Playing: M1"); true }
            KeyAction.PLAY_PAUSE -> { if (!repeat) say("Play/pause: M1"); true }
            KeyAction.DIGIT -> {
                if (!repeat) say(if (key.digit == 0) "Search: M4" else "Pin ${key.digit}: M2")
                true
            }
            KeyAction.STAR -> false // unused on Home
            KeyAction.BACK -> false // let the system finish the activity
        }
    }

    private fun say(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    private companion object {
        const val SCREEN = "Home"
        const val STATE_SELECTED = "selected"
    }
}
