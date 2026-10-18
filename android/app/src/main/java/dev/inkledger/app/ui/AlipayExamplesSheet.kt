package dev.inkledger.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import dev.inkledger.app.R

/** Shipped examples are redacted derivatives, never the user's original receipt files. */
internal data class AlipayGuideExample(
    val type: String,
    val title: String,
    val result: String,
    val read: String,
    val ignore: String,
    val review: String,
    @DrawableRes val image: Int,
)

internal object AlipayGuideExamples {
    val payment = AlipayGuideExample(
        type = "付款",
        title = "付款成功页",
        result = "实付 ¥8.60 · 消费付款",
        read = "读取「支付成功」下的主金额，花呗是付款方式。",
        ignore = "¥8.80 是优惠前金额，−¥0.20 是优惠。广告里的 0.5 元、积分和奖励都不是这笔支出。",
        review = "没有支付日期的成功页，请核对实际发生时间；金额不明确时不会拿广告金额补齐。",
        image = R.drawable.guide_alipay_payment,
    )
    val all = listOf(payment, AlipayGuideExample(
        type = "转账",
        title = "转账成功页",
        result = "转账 ¥298.00 · 不自动记账",
        read = "先看「转账成功」，再读取主金额。即使收款方昵称像店铺，也不改记成消费。",
        ignore = "亲情卡是付款方式，不凭它判断资金由谁承担。「通知 TA」是页面按钮，不是系统付款通知。广告券金额和积分都忽略。",
        review = "转账不自动记入消费，也不生成待核对账目。本版需手动处理：如需记录，在「明细」手动记一笔，选择「转账」。",
        image = R.drawable.guide_alipay_transfer,
    ), AlipayGuideExample(
        type = "账单",
        title = "账单详情页",
        result = "支出 ¥8.50 · 按账单时间",
        read = "−8.50 表示支出，采用「支付时间」字段；状态栏时钟不是交易时间。",
        ignore = "「收钱码收款」是商品说明，不会因此把付款人的支出识别成收入。积分奖励不作为金额。",
        review = "历史账单先核对支付日期和已有记录。同一笔付款的成功页与详情页，不能重复确认入账。",
        image = R.drawable.guide_alipay_bill,
    ))
}

/** Three independently browsable examples keep the tutorial short and the receipts readable. */
class AlipayExamplesSheet : InkSheet() {
    override val heading = "支付宝页面图解"
    private var selected = 0
    private var detailStart = 0

    override fun build(saved: Bundle?) {
        selected = (saved?.getInt("example") ?: 0).coerceIn(AlipayGuideExamples.all.indices)
        note("先分清页面类型，再核对主金额。以下是真实页面脱敏示例；灰块遮盖姓名、头像或交易时间等隐私。")
        val selector = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        AlipayGuideExamples.all.forEachIndexed { index, example ->
            selector.addView(RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = example.type
                textSize = 16f
                minHeight = context.dp(52)
                setTextColor(context.color(R.color.ink_text))
                buttonTintList = ColorStateList.valueOf(context.color(R.color.ink_text))
                contentDescription = "${example.type}页面示例"
                isChecked = index == selected
                setOnClickListener {
                    if (selected != index) {
                        selected = index
                        renderExample()
                    }
                }
            }, RadioGroup.LayoutParams(0, -2, 1f).apply {
                if (index > 0) leftMargin = requireContext().dp(8)
            })
        }
        content.addView(selector)
        detailStart = content.childCount
        renderExample()
    }

    private fun renderExample() {
        while (content.childCount > detailStart) content.removeViewAt(detailStart)
        val example = AlipayGuideExamples.all[selected]
        content.addGap(16)
        content.addView(requireContext().label(example.result, 22f, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        })
        content.addGap(8)
        content.addView(requireContext().label(example.read, 16f))
        content.addGap(16)
        content.addView(alipayExamplePreview(requireContext(), example, open = ::showImage),
            LinearLayout.LayoutParams(-1, requireContext().dp(260)))
        button("查看${example.type}大图", secondary = true, action = ::showImage)
        content.addGap(16)
        content.addView(requireContext().label("这些信息别混淆", 16f, bold = true))
        content.addGap(8)
        content.addView(requireContext().label(example.ignore, 16f))
        content.addGap(16)
        content.addView(requireContext().label("识别后的处理", 16f, bold = true))
        content.addGap(8)
        content.addView(requireContext().label(example.review, 16f))
        content.addGap(16)
        note("示例只帮助你认位置，不会生成账目。截图 OCR 读出的付款、账单候选需确认后才入账。")
        button("返回使用指南", secondary = true) { dismiss() }
        content.post { (content.parent as? ScrollView)?.scrollTo(0, 0) }
        if (content.isAttachedToWindow) content.announceForAccessibility(example.result)
    }

    private fun showImage() {
        if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag("example-image") == null) {
            AlipayExampleImageSheet.forExample(selected).showNow(childFragmentManager, "example-image")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("example", selected)
        super.onSaveInstanceState(outState)
    }
}

/** The large view fits the receipt to the sheet width and scrolls, instead of shrinking to fit height. */
class AlipayExampleImageSheet : InkSheet() {
    private val example get() = AlipayGuideExamples.all[
        (arguments?.getInt("example") ?: 0).coerceIn(AlipayGuideExamples.all.indices)
    ]
    override val heading get() = "${example.type}示例大图"

    override fun build(saved: Bundle?) {
        note("真实页面脱敏示例 · 上下滚动查看\n" +
            if (example.type == "账单") "灰块为隐私遮盖；账单的支付时间不是缺失。" else "灰块为隐私遮盖。")
        content.addView(ImageView(requireContext()).apply {
            setImageResource(example.image)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${example.title}脱敏示例。${example.result}。${example.read}"
        }, LinearLayout.LayoutParams(-1, -2))
        content.addGap(16)
        button("返回图解", secondary = true) { dismiss() }
    }

    companion object {
        fun forExample(index: Int) = AlipayExampleImageSheet().apply {
            arguments = Bundle().apply { putInt("example", index) }
        }
    }
}

internal fun alipayExamplePreview(context: Context, example: AlipayGuideExample, actionDescription: String = "点按查看大图", open: () -> Unit) = ImageView(context).apply {
    setImageResource(example.image)
    adjustViewBounds = true
    scaleType = ImageView.ScaleType.FIT_CENTER
    background = context.shape(context.color(R.color.ink_tint), 16)
    foreground = android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(context.color(R.color.ink_line)), null, context.shape(android.graphics.Color.WHITE, 16),
    )
    setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
    contentDescription = "${example.title}脱敏图片，${example.result}，$actionDescription"
    isFocusable = true
    setOnClickListener { open() }
}
