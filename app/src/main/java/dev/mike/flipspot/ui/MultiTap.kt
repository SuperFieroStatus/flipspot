package dev.mike.flipspot.ui

import android.os.Handler
import android.os.Looper

/**
 * Classic phone-keypad text entry: pressing a digit repeatedly cycles its letters; a pause of
 * [COMMIT_MS] (or a different key) commits the letter. 0 = space, 1 = punctuation.
 */
class MultiTap(private val onChange: (text: String, pending: Char?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val committed = StringBuilder()
    private var pendingKey = -1
    private var pendingIndex = 0

    val text: String get() = committed.toString() + (pendingChar()?.toString() ?: "")

    private val commit = Runnable { commitPending() }

    fun press(digit: Int) {
        val letters = KEYS[digit]
        if (digit == pendingKey) {
            pendingIndex = (pendingIndex + 1) % letters.length
        } else {
            commitPending()
            pendingKey = digit
            pendingIndex = 0
        }
        main.removeCallbacks(commit)
        main.postDelayed(commit, COMMIT_MS)
        notifyChange()
    }

    /** Deletes the pending letter or the last committed one. Returns false if already empty. */
    fun backspace(): Boolean {
        main.removeCallbacks(commit)
        if (pendingKey >= 0) {
            pendingKey = -1
        } else if (committed.isNotEmpty()) {
            committed.deleteCharAt(committed.length - 1)
        } else {
            return false
        }
        notifyChange()
        return true
    }

    fun clear() {
        main.removeCallbacks(commit)
        pendingKey = -1
        committed.setLength(0)
        notifyChange()
    }

    private fun commitPending() {
        pendingChar()?.let { committed.append(it) }
        pendingKey = -1
        notifyChange()
    }

    private fun pendingChar(): Char? = if (pendingKey >= 0) KEYS[pendingKey][pendingIndex] else null

    private fun notifyChange() = onChange(committed.toString(), pendingChar())

    companion object {
        const val COMMIT_MS = 900L
        val KEYS = arrayOf(" 0", ".'-&1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9")
    }
}
