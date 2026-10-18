package dev.inkledger.app.ui

import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.inkledger.app.InAppNotice
import dev.inkledger.app.LedgerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureFragment : InkScreenFragment() {
    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val data = model.capture.diagnosticsJson()
            val resolver = requireContext().contentResolver
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        checkNotNull(resolver.openOutputStream(uri)).bufferedWriter().use { it.write(data) }
                    }.isSuccess
                }
                model.notice.value = InAppNotice(if (saved) "已导出诊断文件，未包含通知原文或截图" else "导出失败，请换个保存位置重试", error = !saved)
            }
        }
    }

    override fun rows(state: LedgerUiState): List<InkRow> = buildList {
        val c = state.capture
        val enabled = c.settings.notificationEnabled || c.settings.accessibilityEnabled
        val selectedApps = listOfNotNull(if (c.settings.wechatEnabled) "微信" else null, if (c.settings.alipayEnabled) "支付宝" else null)
        val connected = (c.settings.notificationEnabled && c.notificationGranted && c.notificationConnected) ||
            (c.settings.accessibilityEnabled && c.accessibilityGranted && c.accessibilityConnected)
        add(header("付款后，记一笔"))
        add(InkRow("capture-status", RowKind.TEXT, when {
            selectedApps.isEmpty() -> "先选择要识别的支付应用"
            connected -> "正在观察${selectedApps.joinToString("、")}付款"
            enabled -> "等待系统授权或服务连接"
            else -> "选择应用，再开启读取"
        }, "付款成功页停留片刻即可触发检查；信息不完整或疑似重复时会待核对。"))
        add(InkRow("guide", RowKind.ACTION, "使用指南", "四步触发说明与支付宝三类页面脱敏图解，随时回看。", "图文", "guide"))
        add(InkRow("apps", RowKind.SECTION, "01 · 选择支付应用"))
        add(InkRow("app-wechat", RowKind.ACTION, "微信", "识别消费付款凭证；聊天、红包和转账不记为消费。", if (c.settings.wechatEnabled) "已选择" else "未选择", "capture:wechat"))
        add(InkRow("app-alipay", RowKind.ACTION, "支付宝", "识别消费付款与账单详情；转账不自动记账。", if (c.settings.alipayEnabled) "已选择" else "未选择", "capture:alipay"))
        add(InkRow("sources", RowKind.SECTION, "02 · 开启读取方式"))
        add(InkRow("source-accessibility", RowKind.ACTION, "付款页面读取", "自动检查所选应用的可见付款页，不会替你点击或支付。",
            status(c.settings.accessibilityEnabled, c.accessibilityGranted, c.accessibilityConnected), "capture:accessibility"))
        add(InkRow("source-notification", RowKind.ACTION, "通知读取", "作为补充；没有付款系统通知时不会触发。微信通知需核对。",
            status(c.settings.notificationEnabled, c.notificationGranted, c.notificationConnected), "capture:notification"))
        add(InkRow("source-ocr", RowKind.ACTION, "截图 OCR", "页面有成功状态但缺金额时尝试本地识别；OCR 结果需要核对。",
            if (c.settings.ocrEnabled) "已开启" else "单独授权", "capture:ocr"))
        add(InkRow("manual", RowKind.SECTION, "没识别到？手动试一次"))
        if (c.probeSecondsRemaining != null) {
            add(InkRow("probe", RowKind.ACTION, "${c.probeSecondsRemaining} 秒后读取${c.probeTargetName}当前页", "请切到付款凭证；支付宝也可读账单详情。点这里取消，只尝试一次。", "取消", "capture:cancel-probe"))
        } else {
            add(InkRow("probe", RowKind.ACTION, "识别当前付款页", "8 秒后读取所选应用；支付宝可用付款成功页或账单详情。", "单次", "capture:probe"))
        }
        add(InkRow("runtime", RowKind.SECTION, "采集状态"))
        add(InkRow("runtime-reason", RowKind.TEXT, c.lastReason,
            "本次运行：通知 ${c.notificationEvents} 次 · 页面事件 ${c.nodeEvents} 次\n文字读取 ${c.nodeReads} 次 · OCR ${c.ocrAttempts} 次\n已入账 ${c.savedCount} 笔 · 待核对 ${c.pendingCount} 笔 · 重复 ${c.duplicateCount} 次"))
        add(InkRow("diagnostics", RowKind.ACTION, "查看诊断记录", "只记录来源、阶段与结果，不保留通知原文或截图。", "查看", "capture:diagnostics"))
        add(InkRow("export", RowKind.ACTION, "导出诊断文件", "先保存到你选择的位置，再决定是否提供给开发者。", "JSON", "capture:export"))
        add(InkRow("help", RowKind.ACTION, "荣耀手机权限与后台设置", "权限入口、后台活动与排查方法", "查看", "capture:help"))
        if (enabled || c.settings.captureConsentAccepted) add(InkRow("stop", RowKind.ACTION, "停止所有采集", "撤回应用内同意并关闭全部采集开关，已有账目保留。", "停止", "capture:stop"))
        add(InkRow("local", RowKind.TEXT, "只在本机处理", "不保留完整页面文字或截图，不代替你支付。识别结果在账本内查看；系统胶囊尚未接入。"))
    }

    private fun status(enabled: Boolean, granted: Boolean, connected: Boolean) = when {
        !enabled -> "未开启"
        !granted -> "待授权"
        !connected -> "待连接"
        else -> "已连接"
    }

    override fun action(action: String) {
        if (!action.startsWith("capture:")) { super.action(action); return }
        if (parentFragmentManager.isStateSaved || parentFragmentManager.fragments.any { it is InkSheet && it.isAdded }) return
        when (val mode = action.substringAfter(':')) {
            "stop" -> { model.capture.setCaptureConsent(false); model.notice.value = InAppNotice("采集已停止，已有账目保留") }
            "cancel-probe" -> model.capture.cancelOcrProbe()
            "export" -> exportDiagnostics.launch("ink-ledger-payment-diagnostics.json")
            else -> AlipaySetupSheet.forMode(mode).show(parentFragmentManager, "capture-setup")
        }
    }
}
