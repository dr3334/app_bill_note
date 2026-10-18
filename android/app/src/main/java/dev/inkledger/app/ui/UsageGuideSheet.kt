package dev.inkledger.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import dev.inkledger.app.MainActivity
import dev.inkledger.app.R

/** One set of instructions for first launch and the permanent help center. */
class UsageGuideSheet : InkSheet() {
    override val heading get() = if (firstLaunch) "第一次使用" else "使用指南"
    private var page = -1
    private var bodyStart = 0
    private val firstLaunch get() = arguments?.getBoolean("first") == true

    override fun build(saved: Bundle?) {
        page = (saved?.getInt("guidePage") ?: arguments?.getInt("chapter", -1)
            ?: if (firstLaunch) 0 else -1).coerceIn(-1, chapters.lastIndex)
        if (saved == null && firstLaunch) page = 0
        bodyStart = content.childCount
        render()
    }

    private fun render() {
        while (content.childCount > bodyStart) content.removeViewAt(bodyStart)
        content.post { (content.parent as? ScrollView)?.scrollTo(0, 0) }
        if (page == -1) {
            note("付款后自动检查；漏记时手动识别一次。选择你想看的步骤，阅读不会开启采集权限。")
            button("支付宝三类页面 · 真实图解") { showAlipayExamples() }
            note("付款、转账、账单分别怎么看；真实页面脱敏示例。")
            chapters.forEachIndexed { index, chapter -> button("${index + 1} · ${chapter.title}", secondary = true) { page = index; render() } }
            button("荣耀手机权限与后台设置", secondary = true) {
                dismiss()
                AlipaySetupSheet.forMode("help").show(parentFragmentManager, "capture-setup")
            }
            note("入口一直保留在「自动 → 使用指南」和「设置 → 使用指南」。")
            return
        }
        val chapter = chapters[page]
        val progress = requireContext().row().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        chapters.indices.forEach { index ->
            progress.addView(View(requireContext()).apply {
                background = context.shape(context.color(if (index <= page) R.color.ink_text else R.color.ink_tint), 2)
            }, LinearLayout.LayoutParams(0, requireContext().dp(3), 1f).apply { if (index > 0) leftMargin = requireContext().dp(8) })
        }
        content.addView(progress)
        content.addGap(16)
        content.addView(requireContext().label("第 ${page + 1} 步 / 共 ${chapters.size} 步", 13f, secondary = true))
        content.addGap(8)
        content.addView(requireContext().label(chapter.title, 22f, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) })
        content.addGap(12)
        val illustration = if (page == 1) {
            content.addView(alipayExamplePreview(requireContext(), AlipayGuideExamples.payment, "点按查看三类页面") { showAlipayExamples() },
                LinearLayout.LayoutParams(-1, requireContext().dp(224)))
            content.addGap(8)
            note("真实页面脱敏示例 · 点图查看三类页面")
            null
        } else {
            GuideIllustration(requireContext(), page).apply {
                contentDescription = chapter.imageDescription
                content.addView(this, LinearLayout.LayoutParams(-1, requireContext().dp(200)))
                content.addGap(8)
                note("操作示意图 · 系统界面以你的手机为准")
            }
        }
        content.addView(requireContext().label(chapter.body, 16f))
        content.addGap(12)
        note(chapter.detail)
        if (page == 1) button("查看付款、转账与账单图解", secondary = true) { showAlipayExamples() }
        if (page == 0 || page == 2) button(if (page == 0) "播放点按示意" else "播放切换应用手势", secondary = true) {
            illustration?.replay(model.state.value.reducedMotion)
        }
        button(if (page == chapters.lastIndex) "去开启自动记账" else "下一步") {
            if (page == chapters.lastIndex) {
                markSeen(requireContext())
                dismiss()
                (requireActivity() as MainActivity).select(2)
            } else { page++; render() }
        }
        if (page > 0) button("上一步", secondary = true) { page--; render() }
        if (firstLaunch) button("稍后设置", secondary = true) { dismiss() }
        else if (page == 0) button("返回目录", secondary = true) { page = -1; render() }
        if (page > 0 && !firstLaunch) button("返回目录", secondary = true) { page = -1; render() }
        if (content.isAttachedToWindow) content.announceForAccessibility("${page + 1} / ${chapters.size}，${chapter.title}")
    }

    private fun showAlipayExamples() {
        if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag("alipay-examples") == null) {
            AlipayExamplesSheet().showNow(childFragmentManager, "alipay-examples")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("guidePage", page); super.onSaveInstanceState(outState) }
    override fun onDismiss(dialog: DialogInterface) {
        if (firstLaunch && activity?.isChangingConfigurations != true) context?.let(::markSeen)
        super.onDismiss(dialog)
    }

    companion object {
        fun firstRun() = UsageGuideSheet().apply { arguments = Bundle().apply { putBoolean("first", true) } }
        fun forChapter(index: Int) = UsageGuideSheet().apply { arguments = Bundle().apply { putInt("chapter", index) } }
        fun needsFirstRun(context: Context) = !context.getSharedPreferences("usage-guide", Context.MODE_PRIVATE).getBoolean("seen_v3", false)
        private fun markSeen(context: Context) { context.getSharedPreferences("usage-guide", Context.MODE_PRIVATE).edit().putBoolean("seen_v3", true).apply() }
        private data class Chapter(val title: String, val body: String, val detail: String, val imageDescription: String)
        private val chapters = listOf(
            Chapter("先选应用，再授权", "在底部点「自动」，选择微信或支付宝。\n开启「付款页面读取」，按系统指引打开服务；返回后确认显示「已连接」。",
                "微信需要单独同意。通知读取是补充，没有付款系统通知就不会触发。需要截图时再单独开启 OCR，阅读本指南不会授予任何权限。",
                "手指点按底部自动入口，再点按付款页面读取按钮。"),
            Chapter("付款成功，停留片刻", "完成付款后，在付款成功页停留片刻。\n页面变化会触发文字检查。支付宝会区分付款、转账与账单详情，不把广告或优惠金额当作实付。",
                "付款凭证已有成功提示却缺金额，且已开启 OCR 时，才尝试自动截图。信息不完整时待核对或不生成账目；转账不自动记账，本版需手动处理。微信聊天、红包、收款和退款也不记为消费。",
                "支付宝付款成功页脱敏示例，实付八元六角；优惠与广告不计为付款金额。"),
            Chapter("没识别到？手动触发一次", "① 自动 → 识别当前付款页 → 选择微信或支付宝。\n② 点「开始 8 秒倒计时」。\n③ 从屏幕底部上滑并停住，进入最近任务，再点回对应凭证。支付宝可用付款成功页或账单详情，转账页会跳过。",
                "三键导航可点「最近任务」键。需要 Android 14 或以上、页面读取已连接和 OCR 已开启。倒计时期间回到「自动」可取消；不需要摇手机或双击背部。",
                "从手机底部向上滑动后停住，进入最近任务，再点选付款应用；这是安卓系统切换应用手势。"),
            Chapter("看结果，也能随时回看", "回到「明细」检查账目。\n截图识别和微信通知生成的候选账目会进入「需要核对」，确认前不计入统计。",
                "核对金额、类型和发生时间；转账不计消费。历史账单使用账单内支付时间，确认前检查是否已经记过。没有记录时到「自动 → 查看诊断记录」看原因。图文说明在「自动」与「设置」的「使用指南」随时回看。",
                "账本中显示一笔待核对记录，手动确认后才计入统计。"),
        )
    }
}

/** Theme-aware illustrations for permission, switching apps and reviewing results. */
private class GuideIllustration(context: Context, private val step: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 1f
    private var animator: ValueAnimator? = null
    init { background = context.shape(context.color(R.color.ink_tint), 20); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun replay(reduced: Boolean) {
        animator?.cancel()
        if (!InkMotion.enabled(reduced)) { progress = 1f; invalidate(); announceForAccessibility(contentDescription); return }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 300f, height / 200f)
        canvas.save(); canvas.translate((width - 300 * scale) / 2, (height - 200 * scale) / 2); canvas.scale(scale, scale)
        fun box(l: Float, t: Float, r: Float, b: Float, fill: Int = R.color.ink_panel, radius: Float = 8f) {
            paint.style = Paint.Style.FILL; paint.color = context.color(fill); canvas.drawRoundRect(l, t, r, b, radius, radius, paint)
        }
        fun line(x: Float, y: Float, xx: Float, yy: Float, width: Float = 2f) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
            paint.color = context.color(R.color.ink_text); canvas.drawLine(x, y, xx, yy, paint)
        }
        fun text(value: String, x: Float, y: Float, size: Float = 13f, color: Int = R.color.ink_text) {
            paint.style = Paint.Style.FILL; paint.color = context.color(color); paint.textSize = size; paint.typeface = android.graphics.Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }
        box(77f, 9f, 223f, 191f, R.color.ink_text, 18f)
        box(81f, 13f, 219f, 187f, radius = 15f)
        line(137f, 21f, 163f, 21f, 3f); line(130f, 179f, 170f, 179f, 3f)
        when (step) {
            0 -> {
                text("选择采集方式", 96f, 51f)
                box(91f, 65f, 209f, 102f, R.color.ink_tint); text("付款页面读取", 98f, 88f)
                box(91f, 144f, 209f, 168f, R.color.ink_text); text("自动", 137f, 161f, color = R.color.ink_on_primary)
                finger(canvas, 182f, 87f + (1 - progress) * 70f)
            }
            1 -> {
                line(135f, 52f, 146f, 62f, 3f); line(146f, 62f, 166f, 40f, 3f)
                text("付款成功", 124f, 90f, 14f)
                text("¥ 18.00", 111f, 122f, 23f)
                text("停留片刻", 124f, 154f)
            }
            2 -> {
                box(91f, 45f, 151f, 135f, R.color.ink_tint); text("付款页", 99f, 77f)
                box(158f, 45f, 210f, 135f, R.color.ink_tint); text("记一笔", 163f, 77f)
                text("8 秒", 98f, 115f, 18f)
                line(242f, 168f, 242f, 91f); line(242f, 91f, 236f, 102f); line(242f, 91f, 248f, 102f)
                text("上滑", 252f, 135f); text("停住", 252f, 153f)
                val y = 169f - progress.coerceAtMost(.7f) / .7f * 64f
                finger(canvas, if (progress > .7f) 123f else 150f, y)
            }
            else -> {
                text("明细", 96f, 50f, 16f)
                box(91f, 64f, 209f, 111f, R.color.ink_tint); text("1 笔需要核对", 99f, 85f)
                text("确认前不计入统计", 99f, 102f, 11f)
                box(91f, 130f, 209f, 163f, R.color.ink_text); text("核对后确认", 118f, 152f, color = R.color.ink_on_primary)
            }
        }
        canvas.restore()
    }

    private fun finger(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE; paint.color = context.color(R.color.ink_text); paint.strokeWidth = 2f
        canvas.drawCircle(x, y, 13f, paint)
        val path = Path().apply {
            moveTo(x - 4, y + 37); lineTo(x - 13, y + 23); quadTo(x - 15, y + 17, x - 9, y + 19)
            lineTo(x - 4, y + 24); lineTo(x - 4, y + 2); quadTo(x, y - 5, x + 4, y + 2)
            lineTo(x + 4, y + 16); quadTo(x + 22, y + 16, x + 21, y + 27); lineTo(x + 17, y + 40); close()
        }
        paint.style = Paint.Style.FILL; paint.color = context.color(R.color.ink_panel); canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE; paint.color = context.color(R.color.ink_text); canvas.drawPath(path, paint)
    }
}
