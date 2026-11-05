package dev.mike.flipspot.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.mike.flipspot.R

/**
 * Keypad list: ScrollView + LinearLayout of TextView rows, with a persistent highlight bar.
 * No RecyclerView (cheaper on the MT6739 for lists of tens of rows; revisit for 1000+ tracks).
 *
 * The list owns no key handling; screens call [move]/[page] from their KeyRouter handler.
 * The selected row is always kept fully visible.
 */
class ListScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = ArrayList<TextView>()

    private val rowHeightPx = dp(44f)
    private val colorText = context.getColor(R.color.text)
    private val colorHighlight = context.getColor(R.color.highlight)
    private val colorHighlightText = context.getColor(R.color.highlight_text)

    var selectedIndex = 0
        private set

    val size: Int get() = rows.size

    var onSelectionChanged: ((Int) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        isSmoothScrollingEnabled = false
        isVerticalScrollBarEnabled = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setItems(labels: List<String>, selected: Int = 0) {
        column.removeAllViews()
        rows.clear()
        for (label in labels) {
            val row = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                minHeight = rowHeightPx
                val pad = dp(12f)
                setPadding(pad, 0, pad, 0)
            }
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx))
            rows.add(row)
        }
        select(selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0)), force = true)
    }

    /** Move by [delta] rows. Single steps wrap around (feature-phone convention). */
    fun move(delta: Int, wrap: Boolean = true) {
        if (rows.isEmpty()) return
        val n = rows.size
        val target = if (wrap) Math.floorMod(selectedIndex + delta, n) else (selectedIndex + delta).coerceIn(0, n - 1)
        select(target)
    }

    /** Page up (dir < 0) or down (dir > 0) by one screenful; clamps instead of wrapping. */
    fun page(dir: Int) {
        val perPage = (height / rowHeightPx).coerceAtLeast(1)
        move(if (dir < 0) -perPage else perPage, wrap = false)
    }

    fun select(index: Int, force: Boolean = false) {
        if (rows.isEmpty()) return
        if (!force && index == selectedIndex) return
        rows.getOrNull(selectedIndex)?.let { paint(it, false) }
        selectedIndex = index
        paint(rows[index], true)
        ensureSelectedVisible()
        onSelectionChanged?.invoke(index)
    }

    private fun paint(row: TextView, selected: Boolean) {
        if (selected) {
            row.setBackgroundColor(colorHighlight)
            row.setTextColor(colorHighlightText)
        } else {
            row.background = null
            row.setTextColor(colorText)
        }
    }

    private fun ensureSelectedVisible() {
        if (height == 0 || column.height == 0) return // not laid out yet; onLayout retries
        val row = rows.getOrNull(selectedIndex) ?: return
        val viewTop = scrollY
        val viewBottom = scrollY + height - paddingTop - paddingBottom
        when {
            row.top < viewTop -> scrollTo(0, row.top)
            row.bottom > viewBottom -> scrollTo(0, row.bottom - (height - paddingTop - paddingBottom))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        ensureSelectedVisible()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
