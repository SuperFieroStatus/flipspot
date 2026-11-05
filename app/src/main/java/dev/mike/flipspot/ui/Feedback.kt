package dev.mike.flipspot.ui

import android.content.Context
import android.widget.Toast

private var current: Toast? = null

/** Short toast that replaces the previous one instead of queueing behind it. */
fun Context.say(msg: String) {
    current?.cancel()
    current = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).also { it.show() }
}
