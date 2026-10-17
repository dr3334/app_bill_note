package dev.inkledger.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.inkledger.app.LedgerUiState
import dev.inkledger.app.LedgerViewModel
import dev.inkledger.app.MainActivity
import dev.inkledger.app.R
import dev.inkledger.core.EntryKind
import dev.inkledger.core.EntryState
import dev.inkledger.core.LedgerEntry
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

abstract class InkScreenFragment : Fragment() {
    protected val model: LedgerViewModel by activityViewModels()
    private var recycler: RecyclerView? = null
    private var rows: InkRows? = null
    abstract fun rows(state: LedgerUiState): List<InkRow>
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val adapter = InkRows(::action)
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        rows = adapter
        return RecyclerView(requireContext()).apply {
            id = R.id.screen_list
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            clipToPadding = false
            setPadding(0, 0, 0, context.dp(120))
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false; addDuration = InkMotion.ENTRY; removeDuration = 160 }
            recycler = this
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (!InkMotion.enabled(state.reducedMotion)) {
                        view.animate().cancel(); view.alpha = 1f; view.translationY = 0f
                    }
                    recycler?.itemAnimator = if (InkMotion.enabled(state.reducedMotion))
                        (recycler?.itemAnimator ?: DefaultItemAnimator().apply { supportsChangeAnimations = false; addDuration = InkMotion.ENTRY }) else null
                    rows?.submitList(rows(state))
                }
            }
        }
    }
    fun setBottomClearance(px: Int) { recycler?.setPadding(0, 0, 0, px) }
    override fun onDestroyView() { recycler?.adapter = null; recycler = null; rows = null; super.onDestroyView() }
    protected fun header(title: String, add: Boolean = false) = InkRow("header", RowKind.HEADER, title, "示例账本 · 仅用于界面体验", action = if (add) "add" else "")
    protected fun month(s: LedgerUiState) = InkRow("month", RowKind.MONTH, "${s.month.year} 年 ${s.month.monthValue} 月")
    protected fun entryRow(e: LedgerEntry, s: LedgerUiState) = InkRow("entry-${e.id}", RowKind.ENTRY, e.merchant,
        "${e.category} · ${e.channel.label} · ${Instant.ofEpochMilli(e.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}",
        (if (e.kind == EntryKind.INCOME || e.kind == EntryKind.REFUND) "+" else if (e.kind == EntryKind.TRANSFER) "" else "−") + money(e.amountMinor, s.hideAmounts), "entry:${e.id}")
    protected fun loading(s: LedgerUiState): List<InkRow>? = when {
        s.loading -> listOf(InkRow("loading", RowKind.TEXT, "正在打开示例账本…"))
        s.error != null -> listOf(InkRow("error", RowKind.ACTION, s.error, "点按重新读取", action = "retry"))
        else -> null
    }

    private fun action(action: String) {
        if (parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.fragments.any { it is InkSheet && it.isAdded }) return
        when {
            action == "month-back" -> model.changeMonth(-1)
            action == "month-next" -> model.changeMonth(1)
            action == "retry" -> model.load()
            action == "add" -> EntrySheet.newEntry().show(parentFragmentManager, "entry")
            action.startsWith("entry:") -> EntrySheet.forEntry(action.removePrefix("entry:")).show(parentFragmentManager, "entry")
            action == "pending" -> InfoSheet.pending().show(parentFragmentManager, "pending")
            action == "auto" -> (requireActivity() as MainActivity).select(2)
            action == "private" -> model.hideAmounts(!model.state.value.hideAmounts)
            action == "reduced" -> model.reduceMotion(!model.state.value.reducedMotion)
            action == "theme" -> InfoSheet.theme().show(parentFragmentManager, "theme")
            action == "budget" -> InfoSheet.budget().show(parentFragmentManager, "budget")
            action == "import" -> model.startImport()
            action == "cancel-import" -> model.cancelImport()
            action == "review-import" -> InfoSheet.importPreview().show(parentFragmentManager, "import")
            action.startsWith("demo:") -> model.scenario(action.substringAfter(':'))
            action == "about" -> InfoSheet.about().show(parentFragmentManager, "about")
            action == "source:微信" || action == "source:支付宝" -> InfoSheet.source(action.substringAfter(':')).show(parentFragmentManager, "source")
            action == "screenshot" -> InfoSheet.explain("截图补记", "页面已为系统图片选择器和本地识别留好入口。当前安装包仅提供交互演示，还不能读取或识别你的付款截图。", true).show(parentFragmentManager, "screenshot")
        }
    }
}

class LedgerFragment : InkScreenFragment() {
    override fun rows(state: LedgerUiState) = buildList {
        add(header("记一笔", true))
        loading(state)?.let { addAll(it); return@buildList }
        add(month(state))
        val stats = state.monthly
        add(InkRow("hero", RowKind.HERO, "本月净支出", "收入 ¥${money(stats.incomeMinor, state.hideAmounts)}     预算剩余 ¥${money(state.budgetMinor - stats.netExpenseMinor, state.hideAmounts)}",
            "¥ ${money(stats.netExpenseMinor, state.hideAmounts)}", fraction = stats.netExpenseMinor.toFloat() / state.budgetMinor.coerceAtLeast(1)))
        val pending = state.entries.count { it.state == EntryState.PENDING }
        if (pending > 0) add(InkRow("pending", RowKind.ACTION, "$pending 笔需要核对", "确认前不会计入收支", "查看", "pending"))
        val monthEntries = state.entries.filter { it.state == EntryState.CONFIRMED && java.time.YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault())) == state.month }
        if (monthEntries.isEmpty()) {
            add(InkRow("empty", RowKind.TEXT, "这个月还没有账目", "可以手动记一笔，或去「自动」体验入账流程。"))
            add(InkRow("empty-action", RowKind.BUTTON, "记第一笔", action = "add"))
        } else {
            monthEntries.sortedByDescending { it.occurredAt }.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, entries) ->
                add(InkRow("day-$day", RowKind.SECTION, day.format(DateTimeFormatter.ofPattern("M 月 d 日")), value = "${entries.size} 笔"))
                entries.forEach { add(entryRow(it, state)) }
            }
        }
    }
}

class StatsFragment : InkScreenFragment() {
    override fun rows(state: LedgerUiState) = buildList {
        add(header("看清每一笔"))
        loading(state)?.let { addAll(it); return@buildList }
        add(month(state))
        val stats = state.monthly
        add(InkRow("hero", RowKind.HERO, "本月净支出", "已确认 ${stats.confirmedCount} 笔 · 待核对不参与统计", "¥ ${money(stats.netExpenseMinor, state.hideAmounts)}",
            fraction = stats.netExpenseMinor.toFloat() / state.budgetMinor.coerceAtLeast(1)))
        add(InkRow("budget", RowKind.ACTION, "月预算 ¥${money(state.budgetMinor, state.hideAmounts)}", "剩余 ¥${money(state.budgetMinor - stats.netExpenseMinor, state.hideAmounts)}", "调整", "budget"))
        add(InkRow("categories", RowKind.SECTION, "钱花在哪里", value = "支出 · 未扣退款"))
        if (stats.categoryTotals.isEmpty()) add(InkRow("empty", RowKind.TEXT, "还没有支出，记下第一笔再来看看。"))
        stats.categoryTotals.forEach { item -> add(InkRow("category-${item.category}", RowKind.CATEGORY, item.category,
            value = "¥ ${money(item.amountMinor, state.hideAmounts)}", fraction = item.amountMinor.toFloat() / stats.expenseMinor.coerceAtLeast(1))) }
        add(InkRow("balance", RowKind.SECTION, "本月收支"))
        add(InkRow("income", RowKind.TEXT, "收入   ¥${money(stats.incomeMinor, state.hideAmounts)}"))
        add(InkRow("refunds", RowKind.TEXT, "退款   ¥${money(stats.refundMinor, state.hideAmounts)}"))
        add(InkRow("net", RowKind.TEXT, "结余   ¥${money(stats.balanceMinor, state.hideAmounts)}", "结余是本月收入减净支出，不代表银行卡余额。"))
    }
}

class CaptureFragment : InkScreenFragment() {
    override fun rows(state: LedgerUiState) = buildList {
        add(header("让记账更轻松"))
        add(InkRow("status", RowKind.TEXT, "付款采集尚未接通", "本版先体验界面和动效。下方演示只会改变示例账本。"))
        add(InkRow("sources", RowKind.SECTION, "付款来源"))
        listOf("微信", "支付宝").forEach { add(InkRow("source-$it", RowKind.ACTION, it, "查看接入安排", "未接通", "source:$it")) }
        add(InkRow("capture", RowKind.SECTION, "补记与整理"))
        add(InkRow("screenshot", RowKind.ACTION, "截图补记", "系统选图 · 本地识别", "规划中", "screenshot"))
        when {
            state.importProgress != null -> add(InkRow("import", RowKind.ACTION, "正在整理 ${state.importProgress} / 4", "演示处理，点按取消", "取消", "cancel-import"))
            state.importReady -> add(InkRow("import", RowKind.ACTION, "4 笔示例账目待导入", "先检查，再写入账本", "查看", "review-import", enabled = !state.busy))
            else -> add(InkRow("import", RowKind.ACTION, "体验账单导入", "整理进度、预览与重复处理", "演示", "import", enabled = !state.busy && !state.loading && state.error == null))
        }
        add(InkRow("demos", RowKind.SECTION, "试试自动入账", value = "示例事件"))
        listOf("success" to "付款成功", "review" to "金额需要核对", "failure" to "识别失败", "permission" to "来源权限失效").forEach { (key, name) ->
            add(InkRow("demo-$key", RowKind.ACTION, name, if (key == "success") "再点一次可验证重复提示" else "查看应用内反馈", action = "demo:$key", enabled = !state.busy && !state.loading && state.error == null))
        }
    }
}

class SettingsFragment : InkScreenFragment() {
    override fun rows(state: LedgerUiState) = listOf(
        header("按你的习惯"),
        InkRow("appearance", RowKind.SECTION, "外观与体验"),
        InkRow("theme", RowKind.ACTION, "显示外观", "跟随系统，也可以独立选择", when (state.theme) { 1 -> "浅色"; 2 -> "深色"; else -> "系统" }, "theme"),
        InkRow("privacy", RowKind.TOGGLE, "隐藏金额", "明细、统计和应用内提示同步隐藏", action = "private", checked = state.hideAmounts),
        InkRow("motion", RowKind.TOGGLE, "减少动态", "关闭位移和 Dock 滑动，保留所有状态反馈", action = "reduced", checked = state.reducedMotion),
        InkRow("book", RowKind.SECTION, "账本"),
        InkRow("budget", RowKind.ACTION, "月预算", "¥${money(state.budgetMinor, state.hideAmounts)}", "调整", "budget"),
        InkRow("scope", RowKind.TEXT, "当前为独立示例账本", "你的改动保存在本机。没有读取微信、支付宝或银行卡，也没有云端同步。"),
        InkRow("about", RowKind.ACTION, "关于记一笔", "版本、复用来源和设备适配", "0.1", "about"),
    )
}
