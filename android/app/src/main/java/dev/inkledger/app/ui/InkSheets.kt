package dev.inkledger.app.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.inkledger.app.InAppNotice
import dev.inkledger.app.LedgerViewModel
import dev.inkledger.app.R
import dev.inkledger.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.UUID

abstract class InkSheet : BottomSheetDialogFragment() {
    protected val model: LedgerViewModel by activityViewModels()
    protected lateinit var content: LinearLayout
    protected lateinit var sheetDialog: BottomSheetDialog
    abstract val heading: String
    abstract fun build(saved: Bundle?)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val c = requireContext()
        sheetDialog = BottomSheetDialog(c)
        content = c.column().apply { setPadding(c.dp(24), c.dp(12), c.dp(24), c.dp(24)) }
        val headingRow = c.row()
        headingRow.addView(c.label(heading, 24f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(ImageButton(c).apply {
            setImageResource(R.drawable.ic_close); contentDescription = "关闭"
            ripple(); setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14))
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
        content.addView(headingRow); content.addGap(12)
        build(savedInstanceState)
        val scroll = ScrollView(c).apply { isFillViewport = false; addView(content) }
        sheetDialog.setContentView(scroll)
        sheetDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return sheetDialog
    }

    override fun onStart() {
        super.onStart()
        sheetDialog.behavior.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true; isHideable = true }
        sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(requireContext().color(R.color.ink_panel))
        sheetDialog.dismissWithAnimation = InkMotion.enabled(model.state.value.reducedMotion)
        if (!InkMotion.enabled(model.state.value.reducedMotion)) sheetDialog.window?.setWindowAnimations(0)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        sheetDialog.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(requireContext().dp(24) + bars.left, requireContext().dp(12), requireContext().dp(24) + bars.right, requireContext().dp(24) + bars.bottom)
            insets
        }
    }

    protected fun note(text: String) { content.addView(requireContext().label(text, 14f, true)); content.addGap(16) }
    protected fun button(text: String, secondary: Boolean = false, action: () -> Unit): MaterialButton = MaterialButton(requireContext()).apply {
        this.text = text; isAllCaps = false; minHeight = context.dp(52); cornerRadius = context.dp(16)
        if (secondary) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(context.color(R.color.ink_secondary))
            strokeWidth = context.dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(context.color(R.color.ink_line))
        }
        setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
    }
    protected fun field(label: String, value: String, input: Int = InputType.TYPE_CLASS_TEXT): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(requireContext()).apply {
            hint = label; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(context.dp(12).toFloat(), context.dp(12).toFloat(), context.dp(12).toFloat(), context.dp(12).toFloat())
        }
        val edit = TextInputEditText(requireContext()).apply { setText(value); inputType = input; minHeight = context.dp(56); maxLines = 2 }
        layout.addView(edit)
        content.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = requireContext().dp(12) })
        return layout to edit
    }
}

class EntrySheet : InkSheet() {
    override val heading get() = if (arguments?.getString("id") == null) "记一笔" else "账目详情"
    private var original: LedgerEntry? = null
    private var newId: String = UUID.randomUUID().toString()
    private lateinit var merchant: TextInputEditText
    private lateinit var amount: TextInputEditText
    private lateinit var category: Spinner
    private lateinit var kind: Spinner
    private val categories = listOf("餐饮", "购物", "交通", "居住", "娱乐", "医疗", "工资", "其他")
    private val kinds = EntryKind.entries

    override fun build(saved: Bundle?) {
        newId = saved?.getString("newId") ?: newId
        val entryId = arguments?.getString("id")
        original = model.state.value.entries.firstOrNull { it.id == entryId }
        if (entryId != null && original == null && model.state.value.loading) {
            note("正在读取账目…")
            lifecycleScope.launch {
                model.state.first { !it.loading }
                if (isAdded) {
                    while (content.childCount > 2) content.removeViewAt(2)
                    build(saved)
                }
            }
            return
        }
        if (entryId != null && original == null) { note("这笔账目已不存在。"); button("关闭") { dismiss() }; return }
        val entry = original
        note(if (entry?.state == EntryState.PENDING) "金额需要你核对。确认后才会计入统计。" else "改动仅保存在这个示例账本。")
        val merchantField = field("商家或收支说明", saved?.getString("merchant") ?: entry?.merchant.orEmpty())
        merchant = merchantField.second
        val amountField = field("金额 · 人民币", saved?.getString("amount") ?: entry?.amountMinor?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        amountField.first.helperText = "最多两位小数，单笔不超过 999,999,999.99 元"
        amount = amountField.second
        if (model.state.value.hideAmounts) {
            amount.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            amountField.first.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        content.addGap(16)
        content.addView(requireContext().label("消费类别", 13f, true))
        val categoryOptions = if (entry?.category != null && entry.category !in categories) categories + entry.category else categories
        category = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, categoryOptions)
            minimumHeight = context.dp(56); contentDescription = "消费类别"
            setSelection(saved?.getInt("category") ?: categoryOptions.indexOf(entry?.category ?: "其他").coerceAtLeast(0))
        }
        content.addView(category, LinearLayout.LayoutParams(-1, -2))
        content.addView(requireContext().label("收支类型", 13f, true))
        kind = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, kinds.map { it.label })
            minimumHeight = context.dp(56); contentDescription = "收支类型"
            setSelection(saved?.getInt("kind") ?: kinds.indexOf(entry?.kind ?: EntryKind.EXPENSE))
        }
        content.addView(kind, LinearLayout.LayoutParams(-1, -2))
        note("支付渠道：${entry?.channel?.label ?: "手动"} · 扣款账户：${entry?.accountLabel ?: "未获取"}")
        lateinit var save: MaterialButton
        save = button(if (entry?.state == EntryState.PENDING) "确认并入账" else "保存账目") {
            val minor = parseMinor(amount.text?.toString().orEmpty())
            val name = merchant.text?.toString().orEmpty().trim()
            merchantField.first.error = if (name.isEmpty()) "请填写商家或收支说明" else null
            amountField.first.error = if (minor == null) "请输入大于 0、最多两位小数的金额" else null
            if (name.isEmpty()) { merchant.requestFocus(); return@button }
            if (minor == null) { amount.requestFocus(); return@button }
            val edited = (entry ?: LedgerEntry(id = newId, merchant = name, amountMinor = minor, category = "其他",
                channel = PaymentChannel.MANUAL, occurredAt = System.currentTimeMillis(), state = EntryState.CONFIRMED, evidenceId = "manual-$newId"))
                .copy(merchant = name, amountMinor = minor, category = category.selectedItem.toString(), kind = kinds[kind.selectedItemPosition], state = EntryState.CONFIRMED)
            save.isEnabled = false
            lifecycleScope.launch {
                try {
                    when (val result = model.save(edited)) {
                        is SaveResult.Rejected -> { amountField.first.error = result.reason; amount.requestFocus() }
                        else -> dismiss()
                    }
                } finally { save.isEnabled = true }
            }
        }
        if (entry != null) button("删除这笔示例账目", secondary = true) {
            MaterialAlertDialogBuilder(requireContext()).setTitle("删除这笔账目？").setMessage("删除后会重新计算本月统计。")
                .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> model.delete(entry); dismiss() }.show()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("newId", newId)
        if (::merchant.isInitialized) {
            outState.putString("merchant", merchant.text.toString()); outState.putString("amount", amount.text.toString())
            outState.putInt("category", category.selectedItemPosition); outState.putInt("kind", kind.selectedItemPosition)
        }
        super.onSaveInstanceState(outState)
    }
    companion object {
        fun forEntry(id: String) = EntrySheet().apply { arguments = Bundle().apply { putString("id", id) } }
        fun newEntry() = EntrySheet()
    }
}

class InfoSheet : InkSheet() {
    override val heading get() = requireArguments().getString("title").orEmpty()
    override fun build(saved: Bundle?) {
        when (requireArguments().getString("mode")) {
            "pending" -> {
                val pending = model.state.value.entries.filter { it.state == EntryState.PENDING }
                note(if (pending.isEmpty()) "没有待核对账目。" else "共 ${pending.size} 笔，确认后才会计入收支。")
                pending.forEach { entry -> button("${entry.merchant} · ¥${money(entry.amountMinor, model.state.value.hideAmounts)}") {
                    dismiss(); EntrySheet.forEntry(entry.id).show(parentFragmentManager, "entry")
                } }
            }
            "theme" -> {
                note("应用外观可独立设置，字号跟随系统。")
                val group = RadioGroup(requireContext())
                listOf("跟随系统" to -1, "浅色" to 1, "深色" to 2).forEach { (name, mode) ->
                    group.addView(RadioButton(requireContext()).apply {
                        id = View.generateViewId(); text = name; textSize = 16f; minHeight = context.dp(56)
                        isChecked = model.state.value.theme == mode
                        setOnClickListener {
                            model.theme(mode)
                            // Finish removal before night mode recreates the Activity;
                            // otherwise FragmentManager restores the dismissed sheet.
                            dismissNow()
                            AppCompatDelegate.setDefaultNightMode(mode)
                        }
                    }, RadioGroup.LayoutParams(-1, -2))
                }
                content.addView(group)
            }
            "budget" -> {
                note("预算用于本月支出提醒，不改变账目。")
                val (layout, input) = field("月预算 · 人民币", java.math.BigDecimal.valueOf(model.state.value.budgetMinor, 2).toPlainString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                if (model.state.value.hideAmounts) input.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                button("保存预算") {
                    val value = parseMinor(input.text.toString())
                    if (value == null) layout.error = "请输入大于 0、最多两位小数的金额"
                    else { model.budget(value); dismiss() }
                }
            }
            "import" -> {
                val existingCount = (0..3).count { index -> model.state.value.entries.any { it.evidenceId == "demo-batch-$index" } }
                note("共 4 笔示例凭据，${4 - existingCount} 笔新账、$existingCount 笔已记录。确认后统一写入，已有账目不会被覆盖。")
                model.batchEntries().forEach { entry ->
                    content.addView(requireContext().label("${entry.merchant}   ¥${money(entry.amountMinor, model.state.value.hideAmounts)}", 16f)); content.addGap(16)
                }
                button("确认导入") { model.confirmImport(); dismiss() }
                button("取消导入", secondary = true) { model.cancelImport(); dismiss() }
            }
            "source" -> {
                val channel = requireArguments().getString("channel")
                note("$channel 的付款采集尚未接通，本版不会读取其他应用。")
                note("已预留来源采集、本地识别、交易解析、去重和入账接口。下一步在荣耀 100 Pro 上逐项验证授权与数据可获得性。")
                note("当前使用应用内提示。荣耀系统胶囊待场景审核通过后再接入。")
                button("体验入账提示") { model.scenario("success"); dismiss() }
            }
            "about" -> {
                note("记一笔 · 0.1.0 预览\n墨色清单＋底部 Dock")
                note("适配目标：荣耀 100 Pro / MagicOS 10。当前按系统窗口安全区布局，尚未通过荣耀真机验证。")
                note("选择性复用 AutoAccounting 的账单类型、状态和统计查询，并改为整数金额、待核对隔离与凭据去重。源码按 GPL-3.0 方案保留通知。")
                note("本版只使用本机示例账本，未申请联网、通知读取、无障碍或悬浮窗权限。")
                button("查看开源许可") { explain("开源许可", requireContext().assets.open("LICENSE").bufferedReader().use { it.readText() }).show(parentFragmentManager, "license") }
            }
            else -> {
                note(requireArguments().getString("body").orEmpty())
                if (requireArguments().getBoolean("demo")) button("体验待核对流程") { model.scenario("review"); dismiss() }
            }
        }
    }
    companion object {
        private fun make(mode: String, title: String) = InfoSheet().apply { arguments = Bundle().apply { putString("mode", mode); putString("title", title) } }
        fun pending() = make("pending", "待核对")
        fun theme() = make("theme", "显示外观")
        fun budget() = make("budget", "月预算")
        fun importPreview() = make("import", "导入前看一眼")
        fun about() = make("about", "关于记一笔")
        fun source(channel: String) = make("source", "$channel 来源").apply { requireArguments().putString("channel", channel) }
        fun explain(title: String, body: String, demo: Boolean = false) = make("explain", title).apply {
            requireArguments().putString("body", body); requireArguments().putBoolean("demo", demo)
        }
    }
}
