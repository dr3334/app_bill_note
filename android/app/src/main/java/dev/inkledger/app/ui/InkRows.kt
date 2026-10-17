package dev.inkledger.app.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.inkledger.app.R

enum class RowKind { HEADER, MONTH, HERO, SECTION, ENTRY, ACTION, TOGGLE, CATEGORY, TEXT, BUTTON }
data class InkRow(
    val id: String,
    val kind: RowKind,
    val title: String,
    val subtitle: String = "",
    val value: String = "",
    val action: String = "",
    val fraction: Float = 0f,
    val checked: Boolean = false,
    val enabled: Boolean = true,
)

class InkRows(private val onAction: (String) -> Unit) : ListAdapter<InkRow, InkRows.Holder>(object : DiffUtil.ItemCallback<InkRow>() {
    override fun areItemsTheSame(a: InkRow, b: InkRow) = a.id == b.id
    override fun areContentsTheSame(a: InkRow, b: InkRow) = a == b
}) {
    class Holder(val box: FrameLayout) : RecyclerView.ViewHolder(box)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    })

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val box = holder.box
        val c = box.context
        box.removeAllViews()
        box.background = null; box.setOnClickListener(null); box.isClickable = false; box.isFocusable = false
        val gutter = 24 + ((c.resources.configuration.screenWidthDp - 600) / 2).coerceAtLeast(0)
        box.setPadding(c.dp(gutter), c.dp(8), c.dp(gutter), c.dp(8))
        val content = c.column()
        box.addView(content, FrameLayout.LayoutParams(-1, -2))
        fun text(value: String, size: Float = 14f, muted: Boolean = false, bold: Boolean = false) = c.label(value, size, muted, bold)
        fun actionTarget(view: View, action: String = item.action) {
            view.minimumHeight = c.dp(56); view.isFocusable = true; view.isEnabled = item.enabled
            view.ripple(12); view.setOnClickListener { if (item.enabled) onAction(action) }
            ViewCompat.setAccessibilityDelegate(view, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button"
                }
            })
        }
        when (item.kind) {
            RowKind.HEADER -> {
                box.setPadding(c.dp(gutter), c.dp(20), c.dp(gutter), c.dp(20))
                val horizontal = c.row()
                val heading = c.column().apply {
                    addView(text(item.subtitle, 12f, true)); addGap(8)
                    addView(text(item.title, 28f, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) })
                }
                horizontal.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
                if (item.action.isNotEmpty()) horizontal.addView(ImageButton(c).apply {
                    setImageResource(R.drawable.ic_add); contentDescription = "手动记一笔"
                    setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14))
                    background = c.shape(c.color(R.color.ink_panel), 16, true)
                    setOnClickListener { onAction(item.action) }
                }, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
                content.addView(horizontal)
            }
            RowKind.MONTH -> {
                val horizontal = c.row()
                horizontal.addView(text(item.title, 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
                listOf(R.drawable.ic_back to "month-back", R.drawable.ic_forward to "month-next").forEach { (icon, action) ->
                    horizontal.addView(ImageButton(c).apply {
                        setImageResource(icon); contentDescription = if (action.endsWith("back")) "上个月" else "下个月"
                        setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14)); ripple()
                        setOnClickListener { onAction(action) }
                    }, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
                }
                content.addView(horizontal)
            }
            RowKind.HERO -> {
                content.addView(text(item.title, 14f, true))
                content.addGap(10)
                content.addView(text(item.value, 40f, bold = true).apply { fontFeatureSettings = "tnum"; letterSpacing = -.04f })
                content.addGap(16)
                content.addView(text(item.subtitle, 14f, true))
                content.addGap(16)
                val progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (item.fraction.coerceIn(0f, 1f) * 1000).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(c.color(R.color.ink_text))
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(c.color(R.color.ink_tint))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                content.addView(progress, LinearLayout.LayoutParams(-1, c.dp(4)))
                content.addGap(12)
            }
            RowKind.SECTION -> {
                content.addLine()
                val line = c.row()
                line.addView(text(item.title, 13f, true, true).apply { ViewCompat.setAccessibilityHeading(this, true) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (item.value.isNotEmpty()) line.addView(text(item.value, 12f, true))
                content.addView(line)
            }
            RowKind.ENTRY -> {
                val compact = c.resources.configuration.fontScale > 1.3f
                val line = if (compact) c.column() else c.row()
                val names = c.column().apply {
                    addView(text(item.title, 16f, bold = true)); addGap(8)
                    addView(text(item.subtitle, 12f, true))
                }
                line.addView(names, if (compact) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
                val amount = text(item.value, 19f, bold = true).apply {
                    gravity = Gravity.END; fontFeatureSettings = "tnum"
                    setPadding(c.dp(12), c.dp(8), 0, c.dp(8))
                }
                line.addView(amount, LinearLayout.LayoutParams(if (compact) -1 else -2, -2))
                line.setPadding(0, c.dp(10), 0, c.dp(10))
                actionTarget(line); content.addView(line)
            }
            RowKind.ACTION -> {
                val line = c.column()
                line.setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
                line.background = c.shape(c.color(R.color.ink_tint), 16)
                val titleLine = c.row()
                titleLine.addView(text(item.title, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                if (item.value.isNotEmpty()) titleLine.addView(text(item.value, 12f, true))
                line.addView(titleLine)
                if (item.subtitle.isNotEmpty()) { line.addGap(8); line.addView(text(item.subtitle, 13f, true)) }
                line.minimumHeight = c.dp(64)
                line.isFocusable = true; line.isClickable = true; line.isEnabled = item.enabled
                line.foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(c.color(R.color.ink_line)), null, c.shape(android.graphics.Color.WHITE))
                line.setOnClickListener { if (item.enabled) onAction(item.action) }
                line.alpha = if (item.enabled) 1f else .5f
                content.addView(line)
            }
            RowKind.TOGGLE -> {
                val toggle = MaterialSwitch(c).apply {
                    text = item.title; textSize = 16f; setTextColor(c.color(R.color.ink_text))
                    minHeight = c.dp(56); isChecked = item.checked
                    setOnCheckedChangeListener { _, _ -> onAction(item.action) }
                }
                content.addView(toggle)
                if (item.subtitle.isNotEmpty()) content.addView(text(item.subtitle, 13f, true))
            }
            RowKind.CATEGORY -> {
                val line = c.row()
                line.addView(text(item.title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(text(item.value, 16f).apply { fontFeatureSettings = "tnum" })
                content.addView(line); content.addGap(10)
                content.addView(ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (item.fraction * 1000).toInt().coerceIn(0, 1000)
                    progressTintList = android.content.res.ColorStateList.valueOf(c.color(R.color.ink_text))
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(c.color(R.color.ink_tint))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, c.dp(5)))
                content.addGap(6)
            }
            RowKind.TEXT -> {
                content.addView(text(item.title, 14f, true))
                if (item.subtitle.isNotEmpty()) { content.addGap(8); content.addView(text(item.subtitle, 13f, true)) }
            }
            RowKind.BUTTON -> {
                content.addView(MaterialButton(c).apply {
                    text = item.title; isAllCaps = false; minHeight = c.dp(52)
                    cornerRadius = c.dp(16); isEnabled = item.enabled
                    setOnClickListener { onAction(item.action) }
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
}
