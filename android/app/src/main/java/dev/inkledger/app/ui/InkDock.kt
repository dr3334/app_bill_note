package dev.inkledger.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.inkledger.app.R

class InkDock @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val indicator = View(context)
    private val tabs = LinearLayout(context)
    private val labels = listOf("明细", "统计", "自动", "设置")
    private val icons = listOf(R.drawable.ic_ledger, R.drawable.ic_stats, R.drawable.ic_auto, R.drawable.ic_settings)
    private val buttons = mutableListOf<AppCompatTextView>()
    private var spring: SpringAnimation? = null
    var onSelect: (Int) -> Unit = {}
    var reducedMotion = false
    var selection = 0; private set

    init {
        background = context.shape(context.color(R.color.ink_panel), 30, true)
        elevation = context.dp(8).toFloat()
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        indicator.background = context.shape(context.color(R.color.ink_text), 24)
        // A MATCH_PARENT indicator makes a wrap-content FrameLayout consume the
        // full available height. Measure from the real tab row instead.
        addView(indicator, LayoutParams(0, context.dp(60)))
        tabs.orientation = LinearLayout.HORIZONTAL
        addView(tabs, LayoutParams(-1, -2))
        labels.forEachIndexed { index, label ->
            val button = AppCompatTextView(context).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                minHeight = context.dp(60); minimumWidth = context.dp(48)
                setPadding(0, context.dp(8), 0, context.dp(8))
                compoundDrawablePadding = context.dp(4)
                setCompoundDrawables(null, ContextCompat.getDrawable(context, icons[index])?.apply {
                    setBounds(0, 0, context.dp(22), context.dp(22))
                }, null, null)
                isClickable = true; isFocusable = true; ripple(24)
                contentDescription = label
                setOnClickListener { onSelect(index) }
                ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.Button"
                        info.isSelected = index == selection
                    }
                })
            }
            buttons += button
            tabs.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        select(0, false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The indicator never drives the Dock's height. The tab row owns it,
        // including when the system font size changes.
        indicator.layoutParams.width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) / 4
        indicator.layoutParams.height = 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        indicator.measure(
            MeasureSpec.makeMeasureSpec(indicator.layoutParams.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight - paddingTop - paddingBottom, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        select(selection, false)
    }

    fun select(index: Int, animate: Boolean = true) {
        selection = index.coerceIn(0, 3)
        buttons.forEachIndexed { i, button ->
            val tint = context.color(if (i == selection) R.color.ink_on_primary else R.color.ink_secondary)
            button.setTextColor(tint)
            TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(tint))
            button.isSelected = i == selection
        }
        val target = (width - paddingLeft - paddingRight) / 4f * selection
        if (animate && InkMotion.enabled(reducedMotion) && width > 0) {
            if (spring == null) spring = SpringAnimation(indicator, SpringAnimation.TRANSLATION_X).apply {
                spring = SpringForce().setDampingRatio(1f).setStiffness(700f)
            }
            spring?.animateToFinalPosition(target)
        } else { spring?.cancel(); indicator.translationX = target }
    }

    override fun onDetachedFromWindow() { spring?.cancel(); super.onDetachedFromWindow() }
}
