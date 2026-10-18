package dev.inkledger.app.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import dev.inkledger.app.InkApplication
import dev.inkledger.core.EntryState
import dev.inkledger.core.RoomLedgerRepository
import dev.inkledger.core.alipay.AlipayEvidence
import dev.inkledger.core.alipay.AlipayProcessResult
import dev.inkledger.core.alipay.AlipayProcessor
import dev.inkledger.core.alipay.AlipaySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

/** App-local coordinator. The singleton is owned by InkApplication, not by a service. */
class AlipayCaptureManager internal constructor(
    context: Context,
    private val processEvidence: suspend (AlipayEvidence) -> AlipayProcessResult,
    preferencesName: String,
) {
    constructor(context: Context, repository: RoomLedgerRepository) : this(
        context, AlipayProcessor(repository)::process, "alipay_capture_v1",
    )
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processing = Mutex()
    private val jobs = mutableMapOf<Job, AlipaySource>()
    private var probeJob: Job? = null
    private var probeGeneration = 0L
    private var accessibility = WeakReference<AlipayAccessibilityService>(null)
    private val mutableState = MutableStateFlow(AlipayCaptureState(
        settings = CaptureSettings(
            alipayEnabled = preferences.getBoolean("alipay", true),
            wechatEnabled = preferences.getBoolean("wechat", false),
            notificationEnabled = preferences.getBoolean("notification", false),
            accessibilityEnabled = preferences.getBoolean("accessibility", false),
            ocrEnabled = preferences.getBoolean("ocr", false),
            captureConsentAccepted = preferences.getBoolean("consent", false),
            screenshotConsentAccepted = preferences.getBoolean("screenshot_consent", false),
        ), recentLogs = readLogs(),
    ))
    val state: StateFlow<AlipayCaptureState> = mutableState.asStateFlow()

    init { refreshAuthorizationState() }

    fun setPaymentAppEnabled(packageName: String, enabled: Boolean): Boolean {
        if (packageName !in PAYMENT_PACKAGES) return false
        if (enabled && !state.value.settings.captureConsentAccepted) return consentRequired()
        val settings = state.value.settings
        val next = if (packageName == WECHAT_PACKAGE) settings.copy(wechatEnabled = enabled)
            else settings.copy(alipayEnabled = enabled)
        if (next == settings) return true
        if (!enabled) { cancelJobs { true }; cancelOcrProbe(); accessibility.get()?.cancelPendingCapture() }
        updateSettings(next)
        record("CONTROL", "APP_SCOPE_CHANGED", "${paymentAppName(packageName)}采集${if (enabled) "已开启" else "已关闭"}")
        return true
    }

    fun setCaptureConsent(accepted: Boolean): Boolean {
        if (state.value.settings.captureConsentAccepted == accepted) return true
        updateSettings(if (accepted) state.value.settings.copy(captureConsentAccepted = true)
        else CaptureSettings())
        record("CONTROL", if (accepted) "CONSENT_ACCEPTED" else "CONSENT_REVOKED",
            if (accepted) "已同意本地采集说明，仍需分别开启功能" else "已停止全部采集并撤回截图同意")
        return true
    }

    fun setNotificationEnabled(enabled: Boolean): Boolean {
        if (state.value.settings.notificationEnabled == enabled) return true
        if (enabled && !state.value.settings.captureConsentAccepted) return consentRequired()
        updateSettings(state.value.settings.copy(notificationEnabled = enabled))
        if (enabled && state.value.notificationGranted && !state.value.notificationConnected) {
            runCatching { NotificationListenerService.requestRebind(ComponentName(appContext, AlipayNotificationService::class.java)) }
        }
        return true
    }

    fun setAccessibilityEnabled(enabled: Boolean): Boolean {
        if (state.value.settings.accessibilityEnabled == enabled) return true
        if (enabled && !state.value.settings.captureConsentAccepted) return consentRequired()
        updateSettings(state.value.settings.copy(accessibilityEnabled = enabled, ocrEnabled = enabled && state.value.settings.ocrEnabled))
        return true
    }

    fun setOcrConsent(accepted: Boolean): Boolean {
        if (state.value.settings.screenshotConsentAccepted == accepted) return true
        if (accepted && !state.value.settings.captureConsentAccepted) return consentRequired()
        updateSettings(state.value.settings.copy(screenshotConsentAccepted = accepted, ocrEnabled = accepted && state.value.settings.ocrEnabled))
        return true
    }

    fun setOcrEnabled(enabled: Boolean): Boolean {
        val settings = state.value.settings
        if (settings.ocrEnabled == enabled) return true
        if (enabled && Build.VERSION.SDK_INT < 34) {
            record("OCR", "API_UNSUPPORTED", "Android 14 以下仅支持通知与页面文字读取，不启用整屏截图 OCR")
            return false
        }
        if (enabled && (!settings.captureConsentAccepted || !settings.screenshotConsentAccepted || !settings.accessibilityEnabled)) {
            record("CONTROL", "OCR_CONSENT_REQUIRED", "请先开启页面读取，并单独同意仅在内存处理支付页面截图")
            return false
        }
        updateSettings(settings.copy(ocrEnabled = enabled))
        return true
    }

    fun refreshAuthorizationState() {
        val previous = state.value
        val notificationGranted = runCatching {
            appContext.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(ComponentName(appContext, AlipayNotificationService::class.java)) == true
        }.getOrDefault(false)
        val component = ComponentName(appContext, AlipayAccessibilityService::class.java)
        val accessibilityGranted = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }.getOrDefault(false)
        mutableState.update { it.copy(notificationGranted = notificationGranted, accessibilityGranted = accessibilityGranted) }
        if ((previous.notificationGranted && !notificationGranted) || (previous.accessibilityGranted && !accessibilityGranted)) {
            cancelJobs { source ->
                (source == AlipaySource.NOTIFICATION && previous.notificationGranted && !notificationGranted) ||
                    (source != AlipaySource.NOTIFICATION && previous.accessibilityGranted && !accessibilityGranted)
            }
            if (previous.accessibilityGranted && !accessibilityGranted) accessibility.get()?.cancelPendingCapture()
            record("CONTROL", "AUTHORIZATION_REVOKED", "系统授权已撤回，未完成的采集已取消")
        }
        if (!accessibilityGranted) cancelOcrProbe()
    }

    fun requestOcrProbe(packageName: String = ALIPAY_PACKAGE): Boolean {
        refreshAuthorizationState()
        if (Build.VERSION.SDK_INT < 34) {
            record("OCR", "API_UNSUPPORTED", "Android 14 以下仅支持通知与页面文字读取，不启用整屏截图 OCR")
            return false
        }
        if (!canCapture(AlipaySource.OCR, packageName) || !state.value.accessibilityGranted || !state.value.accessibilityConnected) {
            record("OCR", "PROBE_UNAVAILABLE", "请先单独同意并开启截图识别，且确认页面读取服务已连接")
            return false
        }
        cancelOcrProbe()
        mutableState.update { it.copy(probeTargetName = paymentAppName(packageName)) }
        val generation = probeGeneration
        probeJob = scope.launch {
            try {
                record("OCR", "PROBE_SCHEDULED", "8 秒后只识别一次${paymentAppName(packageName)}窗口，请切到付款成功页")
                for (seconds in 8 downTo 1) {
                    mutableState.update { it.copy(probeSecondsRemaining = seconds) }
                    delay(1_000)
                }
                mutableState.update { it.copy(probeSecondsRemaining = null) }
                refreshAuthorizationState()
                if (canCapture(AlipaySource.OCR, packageName) && state.value.accessibilityGranted && state.value.accessibilityConnected) accessibility.get()?.requestOneShotProbe(packageName)
                    ?: record("OCR", "SERVICE_DISCONNECTED", "页面读取服务未连接，本次诊断未执行")
            } finally {
                if (generation == probeGeneration) mutableState.update { it.copy(probeSecondsRemaining = null) }
            }
        }
        return true
    }

    fun cancelOcrProbe() {
        probeGeneration++
        probeJob?.cancel(); probeJob = null
        accessibility.get()?.cancelOcrCapture()
        cancelJobs { it == AlipaySource.OCR }
        mutableState.update { it.copy(probeSecondsRemaining = null) }
    }

    /** A changed/locked window invalidates unfinished page work, not background notifications. */
    internal fun cancelWindowProcessing() { cancelJobs { it != AlipaySource.NOTIFICATION } }

    /** Structured, redacted data only; the caller chooses the export destination. */
    fun diagnosticsJson(): String {
        refreshAuthorizationState()
        val snapshot = state.value
        return JSONObject().put("format", 1).put("exportedAt", System.currentTimeMillis())
            .put("scope", "SELECTED_PAYMENT_APPS_NO_RAW_TEXT_OR_IMAGES")
            .put("alipayEnabled", snapshot.settings.alipayEnabled).put("wechatEnabled", snapshot.settings.wechatEnabled)
            .put("captureConsentAccepted", snapshot.settings.captureConsentAccepted)
            .put("screenshotConsentAccepted", snapshot.settings.screenshotConsentAccepted)
            .put("notificationEnabled", snapshot.settings.notificationEnabled)
            .put("accessibilityEnabled", snapshot.settings.accessibilityEnabled).put("ocrEnabled", snapshot.settings.ocrEnabled)
            .put("notificationGranted", snapshot.notificationGranted).put("notificationConnected", snapshot.notificationConnected)
            .put("accessibilityGranted", snapshot.accessibilityGranted).put("accessibilityConnected", snapshot.accessibilityConnected)
            .put("notificationEvents", snapshot.notificationEvents).put("nodeEvents", snapshot.nodeEvents)
            .put("nodeReads", snapshot.nodeReads).put("ocrAttempts", snapshot.ocrAttempts)
            .put("savedCount", snapshot.savedCount).put("pendingCount", snapshot.pendingCount).put("duplicateCount", snapshot.duplicateCount)
            .put("logs", logsJson(snapshot.recentLogs)).toString(2)
    }

    fun clearDiagnostics() { mutableState.update { it.copy(recentLogs = emptyList()) }; preferences.edit().remove("logs").apply() }

    internal fun canCapture(source: AlipaySource, packageName: String? = null): Boolean =
        allows(state.value.settings, source) && (packageName == null || when (packageName) {
            ALIPAY_PACKAGE -> state.value.settings.alipayEnabled
            WECHAT_PACKAGE -> state.value.settings.wechatEnabled
            else -> false
        })

    private fun allows(settings: CaptureSettings, source: AlipaySource): Boolean {
        return settings.captureConsentAccepted && (settings.alipayEnabled || settings.wechatEnabled) && when (source) {
            AlipaySource.NOTIFICATION -> settings.notificationEnabled
            AlipaySource.ACCESSIBILITY -> settings.accessibilityEnabled
            AlipaySource.OCR -> Build.VERSION.SDK_INT >= 34 && settings.accessibilityEnabled && settings.ocrEnabled && settings.screenshotConsentAccepted
        }
    }

    internal fun notificationConnection(connected: Boolean) {
        mutableState.update { it.copy(notificationConnected = connected) }
        refreshAuthorizationState()
        record("NOTIFICATION", if (connected) "CONNECTED" else "DISCONNECTED", if (connected) "通知服务已实际连接" else "通知服务已断开，系统授权不代表仍在运行")
    }

    internal fun accessibilityConnection(service: AlipayAccessibilityService?) {
        accessibility = WeakReference(service)
        mutableState.update { it.copy(accessibilityConnected = service != null) }
        if (service == null) { cancelOcrProbe(); cancelWindowProcessing() }
        refreshAuthorizationState()
        record("ACCESSIBILITY", if (service != null) "CONNECTED" else "DISCONNECTED", if (service != null) "页面读取服务已实际连接" else "页面读取服务已断开")
    }

    internal fun notificationEvent() { mutableState.update { it.copy(notificationEvents = it.notificationEvents + 1) } }
    internal fun nodeEvent() { mutableState.update { it.copy(nodeEvents = it.nodeEvents + 1) } }
    internal fun nodeRead() { mutableState.update { it.copy(nodeReads = it.nodeReads + 1) } }
    internal fun ocrAttempt() { mutableState.update { it.copy(ocrAttempts = it.ocrAttempts + 1) } }

    internal fun submit(evidence: AlipayEvidence, completed: (AlipayProcessResult?) -> Unit = {}) {
        if (evidence.packageName !in PAYMENT_PACKAGES) {
            record("CONTROL", "UNTRUSTED_SOURCE", "来源不是支持的支付应用，已忽略")
            return
        }
        if (!canCapture(evidence.source, evidence.packageName)) return
        val job = scope.launch {
            try {
                processing.withLock {
                    if (!canCapture(evidence.source, evidence.packageName)) return@withLock
                    val result = processEvidence(evidence)
                    report(evidence, result)
                    completed(result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { record(evidence.source.name, "PROCESSING_FAILED", "本次本地处理失败，未确认成功入账"); completed(null) }
        }
        jobs[job] = evidence.source
        job.invokeOnCompletion { jobs.remove(job) }
    }

    internal fun record(source: String, code: String, message: String) {
        val now = System.currentTimeMillis()
        val previous = state.value.recentLogs.firstOrNull()
        if (previous?.source == source && previous.code == code && now - previous.time < 2_000) return
        val item = CaptureDiagnostic(UUID.randomUUID().toString(), now, source, code, message)
        mutableState.update { it.copy(lastReason = message, recentLogs = (listOf(item) + it.recentLogs).take(100)) }
        preferences.edit().putString("logs", logsJson(state.value.recentLogs).toString()).apply()
    }

    private fun report(evidence: AlipayEvidence, result: AlipayProcessResult) {
        val source = evidence.source
        when (result) {
            is AlipayProcessResult.Saved -> {
                mutableState.update { it.copy(savedCount = it.savedCount + 1, lastResult = notice("已记录${paymentAppName(evidence.packageName)}付款", result.entry.id)) }
                record(source.name, "SAVED", "符合付款规则，已记录到本地账本")
            }
            is AlipayProcessResult.NeedsReview -> {
                val pending = result.entry
                if (pending?.state == EntryState.PENDING) {
                    mutableState.update { it.copy(pendingCount = it.pendingCount + 1, lastResult = notice("有一笔待确认", pending.id)) }
                } else if (pending != null) {
                    mutableState.update { it.copy(lastResult = notice("已有账目信息冲突，请检查原记录与诊断", pending.id)) }
                }
                if (result.codes.isEmpty()) record(source.name, "NEEDS_REVIEW", when {
                    pending?.state == EntryState.PENDING -> "识别结果已存为待确认，请检查后确认"
                    pending != null -> "已有记录需要核对，本次未新增或覆盖账目"
                    else -> "信息不完整，未生成账目"
                })
                else result.codes.forEach { record(source.name, it.name, it.message) }
            }
            is AlipayProcessResult.Duplicate -> {
                mutableState.update { it.copy(duplicateCount = it.duplicateCount + 1, lastResult = notice("重复信息未再次入账", result.entry?.id)) }
                record(source.name, "DUPLICATE", "已处理过这份付款证据，未再次入账")
            }
            is AlipayProcessResult.Ignored -> record(source.name, result.code.name, result.code.message)
            is AlipayProcessResult.Failed -> record(source.name, "PROCESSING_FAILED", "本地处理失败，请检查账本后再试")
        }
    }

    private fun notice(title: String, entryId: String?) = CaptureResultNotice(UUID.randomUUID().toString(), title, entryId)

    internal fun close() { cancelOcrProbe(); cancelJobs { true }; scope.cancel() }

    private fun consentRequired(): Boolean { record("CONTROL", "CONSENT_REQUIRED", "请先阅读并同意本地采集说明"); return false }

    private fun updateSettings(settings: CaptureSettings) {
        val previous = state.value.settings
        if (settings == previous) return
        cancelJobs { source -> !allows(settings, source) }
        if (allows(previous, AlipaySource.ACCESSIBILITY) && !allows(settings, AlipaySource.ACCESSIBILITY)) {
            cancelOcrProbe()
            accessibility.get()?.cancelPendingCapture()
        } else if (allows(previous, AlipaySource.OCR) && !allows(settings, AlipaySource.OCR)) {
            cancelOcrProbe()
            accessibility.get()?.cancelOcrCapture()
        }
        mutableState.update { it.copy(settings = settings) }
        preferences.edit().putBoolean("notification", settings.notificationEnabled)
            .putBoolean("alipay", settings.alipayEnabled).putBoolean("wechat", settings.wechatEnabled)
            .putBoolean("accessibility", settings.accessibilityEnabled).putBoolean("ocr", settings.ocrEnabled)
            .putBoolean("consent", settings.captureConsentAccepted).putBoolean("screenshot_consent", settings.screenshotConsentAccepted).apply()
    }

    private fun cancelJobs(disallowed: (AlipaySource) -> Boolean) {
        jobs.entries.filter { disallowed(it.value) }.map { it.key }.forEach { it.cancel() }
    }

    private fun logsJson(logs: List<CaptureDiagnostic>): JSONArray = JSONArray().apply {
        logs.forEach { item -> put(JSONObject().put("id", item.id).put("time", item.time).put("source", item.source)
            .put("code", item.code).put("message", item.message)) }
    }

    private fun readLogs(): List<CaptureDiagnostic> = runCatching {
        val array = JSONArray(preferences.getString("logs", "[]"))
        (0 until minOf(array.length(), 100)).map { index -> array.getJSONObject(index).let {
            CaptureDiagnostic(it.getString("id"), it.getLong("time"), it.getString("source"), it.getString("code"), it.getString("message"))
        } }
    }.getOrDefault(emptyList())

    companion object {
        fun get(context: Context): AlipayCaptureManager = (context.applicationContext as InkApplication).captureManager
    }
}
