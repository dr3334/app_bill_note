package dev.inkledger.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.inkledger.app.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.color(id: Int) = ContextCompat.getColor(this, id)
fun Context.shape(color: Int, radius: Int = 16, stroke: Boolean = false) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    if (stroke) setStroke(dp(1), this@shape.color(R.color.ink_line))
}
fun View.ripple(radius: Int = 16) {
    background = RippleDrawable(ColorStateList.valueOf(context.color(R.color.ink_line)), null,
        context.shape(android.graphics.Color.WHITE, radius))
}
fun Context.label(text: String, size: Float = 16f, secondary: Boolean = false, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color(if (secondary) R.color.ink_secondary else R.color.ink_text))
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    includeFontPadding = false
    setLineSpacing(dp(3).toFloat(), 1f)
}
fun LinearLayout.addGap(height: Int) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))
fun LinearLayout.addLine() = addView(View(context).apply { setBackgroundColor(context.color(R.color.ink_line)) },
    LinearLayout.LayoutParams(-1, context.dp(1)).apply { topMargin = context.dp(16); bottomMargin = context.dp(12) })
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun money(minor: Long, hidden: Boolean = false): String = if (hidden) "••••" else NumberFormat.getNumberInstance(Locale.CHINA).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(BigDecimal.valueOf(minor, 2))
fun parseMinor(text: String): Long? = dev.inkledger.core.Money.parseMinor(text)

object InkMotion {
    const val PAGE = 200L
    const val NOTICE = 260L
    const val ENTRY = 220L
    fun enabled(reduced: Boolean) = !reduced && ValueAnimator.areAnimatorsEnabled()
    fun enter(view: View, reduced: Boolean, distanceDp: Int = 8) {
        view.animate().cancel()
        if (!enabled(reduced)) { view.alpha = 1f; view.translationY = 0f; return }
        view.alpha = 0f; view.translationY = view.context.dp(distanceDp).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(PAGE)
            .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start()
    }
}
