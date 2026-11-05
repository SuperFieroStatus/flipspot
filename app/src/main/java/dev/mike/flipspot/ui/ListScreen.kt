package dev.mike.flipspot.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.mike.flipspot.R

data class ListItem(
    val label: String,
    /** Shown in the footer while this row is selected (e.g. artist, track count). */
    val detail: String = "",
    /** Greyed out (unplayable track, info row). Still selectable. */
    val dim: Boolean = false,
)

/**
 * Keypad list with a persistent highlight bar, virtualised: only the rows that fit on screen exist
 * as views and are rebound as the selection moves, so 1,700 liked songs cost the same as 6 menu items.
 *
 * The list owns no key handling; screens call [move]/[page] from their KeyRouter handler.
 * The optional footer shows the selected item's detail and "position/total".
 */
class ListScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val rowHeightPx = dp(40f)
    private val colorText = context.getColor(R.color.text)
    private val colorDim = context.getColor(R.color.text_dim)
    private val colorHighlight = context.getColor(R.color.highlight)
    private val colorHighlightText = context.getColor(R.color.highlight_text)

    private val rowsBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val footer = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(colorDim)
        setBackgroundColor(context.getColor(R.color.strip_bg))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START // keep "12/1675" visible, trim the detail
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        val pad = dp(8f)
        setPadding(pad, 0, pad, 0)
    }
    private val rows = ArrayList<TextView>()

    private var items: List<ListItem> = emptyList()
    private var windowStart = 0
    private var emptyText = ""

    var selectedIndex = 0
        private set

    val size: Int get() = items.size

    var onSelectionChanged: ((Int) -> Unit)? = null

    var showFooter: Boolean = true
        set(value) {
            field = value
            footer.visibility = if (value) VISIBLE else GONE
        }

    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(rowsBox, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, dp(20f)))
    }

    fun setItems(labels: List<String>, selected: Int = 0) = setListItems(labels.map { ListItem(it) }, selected)

    fun setListItems(newItems: List<ListItem>, selected: Int = 0) {
        items = newItems
        selectedIndex = if (items.isEmpty()) 0 else selected.coerceIn(0, items.size - 1)
        render()
        if (items.isNotEmpty()) onSelectionChanged?.invoke(selectedIndex)
    }

    /** Text shown in the first row while the list is empty (e.g. "Loading…"). */
    fun setEmptyText(text: String) {
        emptyText = text
        if (items.isEmpty()) render()
    }

    /** Move by [delta] rows. Single steps wrap around (feature-phone convention). */
    fun move(delta: Int, wrap: Boolean = true) {
        if (items.isEmpty()) return
        val n = items.size
        select(if (wrap) Math.floorMod(selectedIndex + delta, n) else (selectedIndex + delta).coerceIn(0, n - 1))
    }

    /** Page up (dir < 0) or down (dir > 0) by one screenful; clamps instead of wrapping. */
    fun page(dir: Int) {
        val perPage = rows.size.coerceAtLeast(1)
        move(if (dir < 0) -perPage else perPage, wrap = false)
    }

    fun select(index: Int) {
        if (items.isEmpty() || index == selectedIndex) return
        selectedIndex = index.coerceIn(0, items.size - 1)
        render()
        onSelectionChanged?.invoke(selectedIndex)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // rowsBox gets its height in the same layout pass; size rows from it afterwards.
        post { rebuildRows() }
    }

    private fun rebuildRows() {
        val n = (rowsBox.height / rowHeightPx).coerceAtLeast(1)
        if (n == rows.size) return
        rowsBox.removeAllViews()
        rows.clear()
        repeat(n) {
            val row = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                val pad = dp(10f)
                setPadding(pad, 0, pad, 0)
            }
            rowsBox.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx))
            rows.add(row)
        }
        render()
    }

    private fun render() {
        val n = rows.size
        if (n == 0) return
        if (items.isEmpty()) {
            rows.forEachIndexed { i, row -> bind(row, if (i == 0) ListItem(emptyText, dim = true) else null, false) }
            footer.text = ""
            return
        }
        if (selectedIndex < windowStart) windowStart = selectedIndex
        if (selectedIndex >= windowStart + n) windowStart = selectedIndex - n + 1
        windowStart = windowStart.coerceIn(0, (items.size - n).coerceAtLeast(0))
        for (i in 0 until n) {
            val idx = windowStart + i
            bind(rows[i], items.getOrNull(idx), idx == selectedIndex)
        }
        val detail = items[selectedIndex].detail
        val pos = "${selectedIndex + 1}/${items.size}"
        footer.text = if (detail.isEmpty()) pos else "$detail · $pos"
    }

    private fun bind(row: TextView, item: ListItem?, selected: Boolean) {
        row.text = item?.label ?: ""
        when {
            item == null -> {
                row.background = null
            }
            selected -> {
                row.setBackgroundColor(colorHighlight)
                row.setTextColor(colorHighlightText)
            }
            else -> {
                row.background = null
                row.setTextColor(if (item.dim) colorDim else colorText)
            }
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
