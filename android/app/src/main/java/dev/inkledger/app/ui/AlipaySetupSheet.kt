package dev.inkledger.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.inkledger.app.InAppNotice
import dev.inkledger.app.R
import dev.inkledger.app.capture.ALIPAY_PACKAGE
import dev.inkledger.app.capture.WECHAT_PACKAGE
import dev.inkledger.app.capture.paymentAppName
import dev.inkledger.app.capture.AlipayCaptureState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AlipaySetupSheet : InkSheet() {
    private val mode get() = requireArguments().getString("mode").orEmpty()
    override val heading get() = when (mode) {
        "wechat" -> "微信付款识别"
        "alipay" -> "支付宝付款识别"
        "notification" -> "付款通知读取"
        "accessibility" -> "付款页面读取"
        "ocr" -> "本地截图 OCR"
        "probe" -> "识别当前付款页"
        "diagnostics" -> "最近诊断"
        else -> "荣耀手机设置"
    }

    override fun build(saved: Bundle?) {
        val capture = model.capture
        val state = capture.state.value
        when (mode) {
            "wechat", "alipay" -> {
                val wechat = mode == "wechat"
                val appName = if (wechat) "微信" else "支付宝"
                val packageName = if (wechat) WECHAT_PACKAGE else ALIPAY_PACKAGE
                val enabled = if (wechat) state.settings.wechatEnabled else state.settings.alipayEnabled
                note("选择后，已开启的读取方式会用于$appName。页面文字与付款通知仅在本机解析，不上传；不会点击、输入或执行支付。选择应用本身不会授予系统权限。")
                if (wechat) {
                    note("微信还包含聊天等私人内容。程序会过滤聊天通知与普通页面，只尝试识别消费付款凭证；微信通知识别结果仍需手动核对。红包、转账、收款和退款暂不自动记为消费。")
                } else note("区分付款成功、转账成功和账单详情：消费只取实付，不取广告、优惠或积分金额；转账不自动记账，需手动处理。账单采用支付时间，核对是否已记过。截图识别或信息不完整的候选会保留待核对。")
                note("当前：${if (enabled) "已选择" else "未选择"}。随时关闭可停止本应用的后续采集，已有账目保留。")
                button(if (enabled) "保持选择" else "同意并选择$appName") {
                    if (!enabled) { capture.setCaptureConsent(true); capture.setPaymentAppEnabled(packageName, true) }
                    dismiss()
                }
                if (enabled) button("停止识别$appName", secondary = true) { capture.setPaymentAppEnabled(packageName, false); dismiss() }
            }
            "notification" -> {
                note("系统授权后，记一笔只处理你选择的微信、支付宝通知，其他应用通知立即忽略。内容仅在本机解析；明确付款且信息可靠时入账，信息不完整时待核对或忽略。")
                note("微信聊天通知会过滤；其余微信付款候选也需要核对。不会登录你的支付账户。没有系统通知的付款，请使用页面读取。你可随时关闭处理或撤回系统权限。")
                note("系统授权：${if (state.notificationGranted) "已授予" else "未授予"} · 服务连接：${if (state.notificationConnected) "已连接" else "未连接"}")
                button(if (state.settings.notificationEnabled) "前往系统通知读取设置" else "同意并开启通知处理") {
                    capture.setCaptureConsent(true)
                    capture.setNotificationEnabled(true)
                    openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                if (state.settings.notificationEnabled) button("关闭通知处理", secondary = true) { capture.setNotificationEnabled(false); dismiss() }
            }
            "accessibility" -> {
                note("开启后，记一笔通过无障碍服务读取所选微信、支付宝当前可见页面的文字，用于判断付款凭证、成功状态与金额。不会执行点击、输入、转账或支付操作；跳过密码字段，其他应用页面不处理。")
                note("部分页面可能没有可读取文字。读不到时会记录诊断原因，不会把购物订单或待支付金额直接当作付款。页面读取不自动授予截图权限，截图需另外同意。")
                note("系统授权：${if (state.accessibilityGranted) "已授予" else "未授予"} · 服务连接：${if (state.accessibilityConnected) "已连接" else "未连接"}")
                button(if (state.settings.accessibilityEnabled) "前往系统无障碍设置" else "同意并开启页面读取") {
                    capture.setCaptureConsent(true)
                    capture.setAccessibilityEnabled(true)
                    openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                if (state.settings.accessibilityEnabled) button("关闭页面读取", secondary = true) { capture.setAccessibilityEnabled(false); dismiss() }
            }
            "ocr" -> {
                note("截图 OCR 会临时截取所选支付应用的当前窗口，并用随安装包提供的中文模型在本机识别。图片只在内存中处理，不保存到相册、不上传；诊断文件也不包含图片或识别原文。")
                note("自动截图只在付款凭证文字已有成功状态、但缺少金额时尝试。完全读不到文字时，请用「识别当前付款页」。系统禁止截图时会停止，不绕过限制。OCR 结果核对后才计入统计。")
                note("需要 Android 14 或以上。先开启「付款页面读取」及系统无障碍服务，再单独开启截图功能。")
                button("同意并开启本地 OCR") {
                    capture.setCaptureConsent(true)
                    capture.setOcrConsent(true)
                    val accepted = capture.setOcrEnabled(true)
                    model.notice.value = InAppNotice(if (accepted) "本地 OCR 已开启；请确认页面读取已连接" else "暂未开启，请检查页面读取与同意状态", error = !accepted)
                    dismiss()
                }
                if (state.settings.screenshotConsentAccepted) button("关闭并撤回截图同意", secondary = true) { capture.setOcrConsent(false); dismiss() }
            }
            "probe" -> {
                val apps = listOfNotNull(if (state.settings.wechatEnabled) WECHAT_PACKAGE else null, if (state.settings.alipayEnabled) ALIPAY_PACKAGE else null)
                var target = arguments?.getString("probeApp")?.takeIf { it in apps } ?: apps.firstOrNull()
                var refreshControls: (() -> Unit)? = null
                note("先打开要识别的付款凭证，再回这里开始倒计时。8 秒后只识别所选应用的当前窗口一次；付款候选进入待核对。支付宝可用付款成功页或账单详情，转账页会跳过。")
                if (apps.isNotEmpty()) {
                    content.addView(requireContext().label("选择本次识别的应用", 16f, bold = true))
                    content.addView(RadioGroup(requireContext()).apply {
                        apps.forEach { app -> addView(RadioButton(context).apply {
                            id = View.generateViewId(); text = paymentAppName(app); textSize = 16f
                            setTextColor(context.color(R.color.ink_text)); minHeight = context.dp(52)
                            isChecked = target == app
                            setOnClickListener { target = app; arguments?.putString("probeApp", app); refreshControls?.invoke() }
                        }, RadioGroup.LayoutParams(-1, -2)) }
                    })
                    content.addGap(16)
                }
                note("开始后，从底部上滑并停住，进入最近任务，再点回对应凭证；三键导航请点「最近任务」键。返回本应用可取消倒计时。")
                note("不要停在聊天、支付密码或银行卡页面。锁屏、不是所选应用窗口、权限不足或禁止截图时会停止。")
                fun unavailableReason(latest: AlipayCaptureState): String? = when {
                    target == null || (target == WECHAT_PACKAGE && !latest.settings.wechatEnabled) ||
                        (target == ALIPAY_PACKAGE && !latest.settings.alipayEnabled) -> "请先在「自动」选择本次要识别的支付应用。"
                    android.os.Build.VERSION.SDK_INT < 34 -> "单窗口截图需要 Android 14 或以上；当前可使用页面文字或通知读取。"
                    !latest.settings.captureConsentAccepted || !latest.settings.accessibilityEnabled -> "请先在「自动」同意并开启付款页面读取。"
                    !latest.accessibilityGranted -> "请先在系统无障碍设置中授权付款页面读取。"
                    !latest.accessibilityConnected -> "正在等待页面读取服务连接，连接后即可继续。"
                    !latest.settings.screenshotConsentAccepted || !latest.settings.ocrEnabled -> "请先在「自动 → 截图 OCR」单独同意并开启截图识别。"
                    else -> null
                }
                val readiness = requireContext().label("", 14f, secondary = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                content.addView(readiness)
                val start = button("开始 8 秒倒计时") {
                    val app = target ?: return@button
                    val started = capture.requestOcrProbe(app)
                    model.notice.value = InAppNotice(if (started) "请在 8 秒内切到${paymentAppName(app)}付款凭证" else "未开始，请检查应用选择、页面读取与 OCR 授权", error = !started)
                    if (started) dismiss()
                }
                refreshControls = {
                    val reason = unavailableReason(capture.state.value)
                    start.isEnabled = reason == null
                    readiness.text = reason ?: "已就绪 · 只识别${paymentAppName(target!!)}当前窗口一次"
                }
                refreshControls.invoke()
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        capture.state.collect { refreshControls.invoke() }
                    }
                }
                button("查看图解步骤", secondary = true) { dismiss(); UsageGuideSheet.forChapter(2).show(parentFragmentManager, "usage-guide") }
            }
            "diagnostics" -> {
                note("记录最近的采集阶段和处理原因，不含通知原文、完整交易号、商户、金额或截图。计数以本次应用进程为准。")
                val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                if (state.recentLogs.isEmpty()) note("还没有诊断记录。授权后执行一笔付款，再回到这里查看。")
                state.recentLogs.take(30).forEach { event ->
                    note("${formatter.format(Instant.ofEpochMilli(event.time))} · ${event.source}\n${event.code} · ${event.message}")
                }
                if (state.recentLogs.isNotEmpty()) button("清除诊断记录", secondary = true) { capture.clearDiagnostics(); dismiss() }
            }
            else -> {
                note("1. 在「自动」选择微信或支付宝，再开启付款页面读取、通知读取。系统权限页选择「记一笔 · 付款识别」及对应的通知/页面读取服务，返回后确认状态为「已连接」。")
                note("2. 若 MagicOS 提示受限设置，按系统提供的信任或解除限制流程处理；没有可用入口就保留提示供排查，不需要 Root、USB 调试或关闭全局安全防护。")
                note("3. 设置中搜索「应用启动管理」，检查本应用是否允许后台活动。必要时在最近任务中给应用加锁。")
                note("4. 完成一笔实际消费，在成功页停留片刻。回到明细检查金额、笔数与渠道。微信通知与 OCR 候选需要核对；相近的多份凭据可能提示疑似重复，请对照后决定。")
                note("5. 页面无文字时，单独开启 OCR，再用「识别当前付款页」。它只处理一次当前窗口；自动 OCR 仍需要先读到付款成功线索。")
                note("6. 没有记录时查看诊断原因，必要时导出脱敏文件。发现漏记或误记可手动补记、修改或删除。")
                button("打开本应用系统信息", secondary = true) {
                    openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}"))
                }
            }
        }
    }

    private fun openSettings(action: String, uri: Uri? = null) {
        val intent = Intent(action).apply { if (uri != null) data = uri }
        try { startActivity(intent); dismiss() }
        catch (_: Exception) { model.notice.value = InAppNotice("系统未提供此入口，请在手机设置中搜索对应权限", error = true) }
    }

    companion object {
        fun forMode(mode: String) = AlipaySetupSheet().apply { arguments = Bundle().apply { putString("mode", mode) } }
    }
}
