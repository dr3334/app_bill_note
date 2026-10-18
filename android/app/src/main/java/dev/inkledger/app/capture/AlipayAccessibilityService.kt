package dev.inkledger.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.inkledger.core.alipay.AlipayDiagnosticCode
import dev.inkledger.core.alipay.AlipayEvidence
import dev.inkledger.core.alipay.AlipayProcessResult
import dev.inkledger.core.alipay.AlipaySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Read-only selected-payment-app adapter. No actions, gestures, key filtering or overlay window.
 * AutoAccounting 34b38f82 supplied a reference for event debouncing and Android
 * screenshot buffer lifecycle; this implementation does not copy its Google-like
 * service identity, root/Shizuku bridge, image files or text fingerprints as IDs.
 */
class AlipayAccessibilityService : AccessibilityService() {
    private val manager get() = AlipayCaptureManager.get(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var readJob: Job? = null
    private var screenshotTimeout: Job? = null
    private var sessionId = UUID.randomUUID().toString()
    private var currentWindow = -1
    private var screenshotGeneration = 0L
    private var screenshotInFlight = false
    private var lastScreenshotAt = 0L
    private var lastAutoOcrSession: String? = null
    private var screenReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            resetWindow()
            manager.cancelOcrProbe()
            manager.record("ACCESSIBILITY", "SCREEN_LOCKED", "屏幕已关闭，未完成的页面读取与截图识别已取消")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this, screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        manager.accessibilityConnection(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (!manager.canCapture(AlipaySource.ACCESSIBILITY, packageName)) {
            // XML restricts event delivery to payment packages; an unselected
            // payment app must still invalidate work for the previously selected one.
            resetWindow()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        manager.nodeEvent()
        if (event.isPassword) {
            resetWindow()
            manager.record("ACCESSIBILITY", "SENSITIVE_INPUT", "检测到密码输入，已停止本次读取与截图")
            return
        }
        if (event.windowId != currentWindow || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            resetWindow()
            currentWindow = event.windowId
        }
        val expectedWindow = event.windowId
        // A busy animation must not indefinitely postpone the first read.
        if (readJob?.isActive == true) return
        readJob = scope.launch {
            delay(650)
            readPaymentWindow(expectedWindow, packageName)
        }
    }

    override fun onInterrupt() {
        resetWindow()
        manager.cancelOcrProbe()
        manager.record("ACCESSIBILITY", "INTERRUPTED", "页面读取被系统中断，本次处理已取消")
    }

    override fun onDestroy() {
        cancelPendingCapture()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenReceiverRegistered = false
        }
        manager.accessibilityConnection(null)
        scope.cancel()
        super.onDestroy()
    }

    internal fun cancelPendingCapture() {
        readJob?.cancel(); readJob = null
        cancelOcrCapture()
    }

    private fun resetWindow() {
        currentWindow = -1
        sessionId = UUID.randomUUID().toString()
        lastAutoOcrSession = null
        cancelPendingCapture()
        manager.cancelWindowProcessing()
    }

    internal fun cancelOcrCapture() {
        screenshotTimeout?.cancel(); screenshotTimeout = null
        screenshotGeneration++
        screenshotInFlight = false
    }

    internal fun requestOneShotProbe(packageName: String = ALIPAY_PACKAGE) {
        if (!manager.canCapture(AlipaySource.OCR, packageName)) return
        val snapshot = currentSnapshot(expectedPackage = packageName) ?: return
        requestScreenshot(snapshot, "PROBE")
    }

    private fun readPaymentWindow(expectedWindow: Int, expectedPackage: String) {
        if (!manager.canCapture(AlipaySource.ACCESSIBILITY, expectedPackage)) return
        val snapshot = currentSnapshot(expectedWindow, expectedPackage = expectedPackage) ?: return
        manager.nodeRead()
        if (snapshot.lines.isEmpty()) {
            manager.record("ACCESSIBILITY", "NO_VISIBLE_TEXT", "当前支付页面没有可读文字；可主动进行一次截图识别")
            return
        }
        val evidence = AlipayEvidence(snapshot.packageName, AlipaySource.ACCESSIBILITY,
            snapshot.lines, System.currentTimeMillis(),
            stableCaptureId("nodes|$sessionId|${snapshot.windowId}|${snapshot.lines.joinToString("\n")}"), sessionId)
        val observedSession = sessionId
        manager.submit(evidence) { result ->
            if (result is AlipayProcessResult.NeedsReview && result.entry == null &&
                AlipayDiagnosticCode.NO_AMOUNT in result.codes && observedSession == sessionId &&
                lastAutoOcrSession != observedSession &&
                snapshot.lines.any { SUCCESS.containsMatchIn(it) } && manager.canCapture(AlipaySource.OCR, snapshot.packageName)) {
                // Empty/unrelated pages never trigger automatic OCR.
                lastAutoOcrSession = observedSession
                requestScreenshot(snapshot, "SUCCESS_WITHOUT_AMOUNT")
            }
        }
    }

    /** Metadata is checked before reading any text; input fields and their subtrees are skipped. */
    @Suppress("DEPRECATION")
    private fun currentSnapshot(expectedWindow: Int? = null, quiet: Boolean = false, expectedPackage: String? = null): WindowSnapshot? {
        fun rejected(code: String, message: String): WindowSnapshot? {
            if (!quiet) manager.record("ACCESSIBILITY", code, message)
            return null
        }
        if (getSystemService(PowerManager::class.java)?.isInteractive != true || getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) {
            return rejected("SCREEN_LOCKED", "屏幕已锁定或关闭，不读取或截图")
        }
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return rejected("NO_ACTIVE_ROOT", "系统未提供当前窗口节点，无法确认支付窗口")
        try {
            val packageName = root.packageName?.toString().orEmpty()
            if (expectedPackage != null && packageName != expectedPackage) {
                return rejected("WRONG_APP", "当前不是本次所选支付应用，未读取页面或截图")
            }
            if (!manager.canCapture(AlipaySource.ACCESSIBILITY, packageName) || (expectedWindow != null && root.windowId != expectedWindow)) {
                return rejected("WINDOW_CHANGED", "当前前台窗口已变化，本次内容已丢弃")
            }
            val window = windows.firstOrNull { it.id == root.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?: return rejected("WINDOW_NOT_ACTIVE", "无法确认支付应用为当前活动窗口")
            if (Build.VERSION.SDK_INT >= 30 && window.displayId != Display.DEFAULT_DISPLAY) {
                return rejected("UNSUPPORTED_DISPLAY", "当前仅支持手机主屏上的支付窗口")
            }
            val bounds = Rect().also(window::getBoundsInScreen)
            if (bounds.isEmpty) return rejected("EMPTY_WINDOW", "支付窗口没有有效可见区域")
            val gathered = readVisibleNodes(root)
            if (gathered.sensitive) return rejected("SENSITIVE_INPUT", "检测到密码或验证码页面，本次内容已丢弃且不会截图")
            if (gathered.truncated) return rejected("NODE_LIMIT", "页面节点超过读取上限，本次未解析或截图")
            if (packageName == WECHAT_PACKAGE && isWechatChatWindow(gathered.lines, gathered.hasEditable)) {
                return rejected("CHAT_OR_INPUT", "微信聊天、会话列表或输入页面不用于付款识别")
            }
            return WindowSnapshot(root.windowId, bounds, gathered.lines, gathered.hasEditable, packageName)
        } catch (_: Exception) {
            return rejected("NODE_READ_FAILED", "系统节点读取失败，本次未生成账目")
        } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun readVisibleNodes(root: AccessibilityNodeInfo): NodeText {
        val output = linkedSetOf<String>()
        var visited = 0
        var characters = 0
        var sensitive = false
        var truncated = false
        var hasEditable = false
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (sensitive || truncated || !node.isVisibleToUser) return
            if (++visited > 600 || depth > 30 || characters > 16_000) { truncated = true; return }
            if (node.isPassword) { sensitive = true; return }
            if (node.isEditable || node.className?.toString()?.endsWith("EditText") == true) { hasEditable = true; return }
            for (value in listOf(node.text, node.contentDescription)) {
                val text = value?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                if (SENSITIVE.containsMatchIn(text)) { sensitive = true; return }
                if (text.length > 4_000) { truncated = true; return }
                if (output.add(text)) characters += text.length
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
                if (sensitive || truncated) break
            }
        }
        visit(root, 0)
        return NodeText(if (sensitive || truncated) emptyList() else output.toList(), sensitive, truncated, hasEditable)
    }

    private fun requestScreenshot(original: WindowSnapshot, trigger: String) {
        if (!manager.canCapture(AlipaySource.OCR, original.packageName)) return
        if (Build.VERSION.SDK_INT < 34) { manager.record("OCR", "API_UNSUPPORTED", "Android 14 以下仅支持通知与页面文字读取，不启用整屏截图 OCR"); return }
        if (screenshotInFlight) { manager.record("OCR", "BUSY", "已有一次识别正在处理，请稍后再试"); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenshotAt < 3_000) { manager.record("OCR", "THROTTLED", "截图请求过于频繁，本次已跳过"); return }
        val verified = currentSnapshot(original.windowId, expectedPackage = original.packageName) ?: return
        if (verified.bounds != original.bounds || verified.packageName != original.packageName) { manager.record("OCR", "WINDOW_CHANGED", "窗口已变化，请重新发起识别"); return }
        if (!screenSafeForCapture(verified)) return
        lastScreenshotAt = now
        screenshotInFlight = true
        val token = ++screenshotGeneration
        val capturedSession = sessionId
        manager.ocrAttempt()
        manager.record("OCR", "SCREENSHOT_REQUESTED", if (trigger == "PROBE") "正在执行${paymentAppName(original.packageName)}页面的单次识别" else "付款成功页缺少金额，正在尝试内存 OCR")
        screenshotTimeout = scope.launch {
            delay(10_000)
            if (token == screenshotGeneration) {
                screenshotGeneration++
                screenshotInFlight = false
                manager.record("OCR", "SCREENSHOT_TIMEOUT", "截图或识别未及时返回，本次结果将丢弃")
            }
        }
        takeBoundScreenshot(verified, token, capturedSession)
    }

    /** Window-bound screenshots only: never fall back to capturing an entire display. */
    private fun screenSafeForCapture(snapshot: WindowSnapshot, quiet: Boolean = false): Boolean {
        if (snapshot.hasEditable) {
            if (!quiet) manager.record("OCR", "EDITABLE_CONTENT", "页面包含输入控件，本次不会截图")
            return false
        }
        val availableWindows = runCatching { windows }.getOrElse {
            if (!quiet) manager.record("OCR", "WINDOW_CHECK_FAILED", "无法复核窗口遮挡，本次不会截图")
            return false
        }
        val blocked = availableWindows.any { window ->
            if (window.id == snapshot.windowId) false else {
                val bounds = Rect().also(window::getBoundsInScreen)
                !bounds.isEmpty && Rect.intersects(bounds, snapshot.bounds) &&
                    (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        // Window capture excludes ordinary unfocused system bars.
                        (window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                            (window.isActive || window.isFocused)) ||
                        window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
            }
        }
        if (blocked && !quiet) manager.record("OCR", "OBSCURED_WINDOW", "存在键盘、其他应用或遮挡窗口，本次不会截图")
        return !blocked
    }

    @RequiresApi(34)
    private fun takeBoundScreenshot(snapshot: WindowSnapshot, token: Long, capturedSession: String) {
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var bitmap: Bitmap? = null
                try {
                    if (!validCapture(snapshot, token)) { discard(token); return }
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    try { bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                    if (bitmap == null) { fail(token, "BITMAP_UNAVAILABLE", "系统截图无法转为可识别图像"); return }
                    val ownedBitmap = bitmap!!
                    bitmap = null
                    recognize(ownedBitmap, snapshot, token, capturedSession)
                } catch (_: Exception) { fail(token, "SCREENSHOT_PROCESSING_FAILED", "截图处理失败，本次未生成账目") }
                finally { bitmap?.recycle(); result.hardwareBuffer.close() }
            }

            override fun onFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "系统未授予有效无障碍截图能力"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "系统限制截图频率，请稍后再试"
                    ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "系统拒绝当前显示区域截图"
                    ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "目标窗口已经失效"
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "系统禁止安全窗口截图，不会尝试绕过"
                    else -> "系统未提供截图，本次未生成账目"
                }
                fail(token, "SCREENSHOT_ERROR_$errorCode", reason)
            }
        }
        try {
            takeScreenshotOfWindow(snapshot.windowId, mainExecutor, callback)
        } catch (_: SecurityException) { fail(token, "SCREENSHOT_DENIED", "系统拒绝截图，不会尝试绕过") }
        catch (_: Exception) { fail(token, "SCREENSHOT_FAILED", "截图请求未完成") }
    }

    private fun recognize(bitmap: Bitmap, snapshot: WindowSnapshot, token: Long, capturedSession: String) {
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
        catch (_: Exception) { bitmap.recycle(); fail(token, "OCR_START_FAILED", "本地中文识别组件未能启动"); return }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(mainExecutor) { task ->
                try {
                    if (!validCapture(snapshot, token)) { discard(token); return@addOnCompleteListener }
                    if (!task.isSuccessful) { fail(token, "OCR_FAILED", "本地中文识别未完成，请检查账单后重试"); return@addOnCompleteListener }
                    val recognizedLines = task.result.textBlocks.flatMap { it.lines }
                    val lines = (if (snapshot.packageName == ALIPAY_PACKAGE) {
                        alipayOcrReadingOrder(recognizedLines.map {
                            AlipayOcrLine(it.text, it.boundingBox?.top, it.boundingBox?.left)
                        })
                    } else recognizedLines.map { it.text }).take(300)
                    if (lines.any { SENSITIVE.containsMatchIn(it) }) { fail(token, "SENSITIVE_OCR", "识别到敏感输入页面，结果已丢弃"); return@addOnCompleteListener }
                    if (snapshot.packageName == WECHAT_PACKAGE && isWechatChatWindow(lines, hasEditable = false)) {
                        fail(token, "CHAT_OR_INPUT", "识别到微信聊天或会话列表，结果已丢弃")
                        return@addOnCompleteListener
                    }
                    if (lines.isEmpty()) { fail(token, "OCR_NO_TEXT", "截图中没有识别到文字"); return@addOnCompleteListener }
                    finish(token)
                    manager.submit(AlipayEvidence(snapshot.packageName, AlipaySource.OCR, lines,
                        System.currentTimeMillis(), UUID.randomUUID().toString(), capturedSession))
                } catch (_: Exception) { fail(token, "OCR_FAILED", "本地中文识别未完成") }
                finally { bitmap.recycle(); recognizer.close() }
            }
        } catch (_: Exception) { bitmap.recycle(); recognizer.close(); fail(token, "OCR_START_FAILED", "本地中文识别组件未能启动") }
    }

    private fun validCapture(snapshot: WindowSnapshot, token: Long): Boolean {
        if (token != screenshotGeneration || !manager.canCapture(AlipaySource.OCR, snapshot.packageName)) return false
        val now = currentSnapshot(snapshot.windowId, quiet = true, expectedPackage = snapshot.packageName) ?: return false
        return now.packageName == snapshot.packageName && now.bounds == snapshot.bounds && screenSafeForCapture(now, quiet = true)
    }

    private fun finish(token: Long) { if (token == screenshotGeneration) { screenshotInFlight = false; screenshotTimeout?.cancel(); screenshotTimeout = null } }
    private fun discard(token: Long) { if (token == screenshotGeneration) { finish(token); manager.record("OCR", "WINDOW_CHANGED", "窗口、授权或前台状态已变化，截图结果已丢弃") } }
    private fun fail(token: Long, code: String, message: String) { if (token == screenshotGeneration) { finish(token); manager.record("OCR", code, message) } }

    private data class WindowSnapshot(val windowId: Int, val bounds: Rect, val lines: List<String>, val hasEditable: Boolean, val packageName: String)
    private data class NodeText(val lines: List<String>, val sensitive: Boolean, val truncated: Boolean, val hasEditable: Boolean)

    companion object {
        private val SUCCESS = Regex("付款成功|支付成功|交易成功|扣款成功|已付款|已支付")
        private val SENSITIVE = Regex("支付密码|登录密码|输入密码|请输入密码|短信验证码|输入验证码|校验码")
    }
}
