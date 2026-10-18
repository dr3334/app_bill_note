package dev.inkledger.app.capture

data class CaptureSettings(
    val alipayEnabled: Boolean = true,
    val wechatEnabled: Boolean = false,
    val notificationEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val ocrEnabled: Boolean = false,
    val captureConsentAccepted: Boolean = false,
    val screenshotConsentAccepted: Boolean = false,
)

/** Never contains notification text, page text, amounts, merchant names or screenshots. */
data class CaptureDiagnostic(
    val id: String,
    val time: Long,
    val source: String,
    val code: String,
    val message: String,
)

data class CaptureResultNotice(val id: String, val title: String, val entryId: String? = null)

data class AlipayCaptureState(
    val settings: CaptureSettings = CaptureSettings(),
    val notificationGranted: Boolean = false,
    val notificationConnected: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val notificationEvents: Long = 0,
    val nodeEvents: Long = 0,
    val nodeReads: Long = 0,
    val ocrAttempts: Long = 0,
    val savedCount: Long = 0,
    val pendingCount: Long = 0,
    val duplicateCount: Long = 0,
    val lastReason: String = "采集默认关闭，请先阅读说明并选择需要的功能",
    val recentLogs: List<CaptureDiagnostic> = emptyList(),
    val probeSecondsRemaining: Int? = null,
    val probeTargetName: String = "支付宝",
    val lastResult: CaptureResultNotice? = null,
)

internal const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
internal const val WECHAT_PACKAGE = "com.tencent.mm"
internal val PAYMENT_PACKAGES = setOf(ALIPAY_PACKAGE, WECHAT_PACKAGE)
internal fun paymentAppName(packageName: String) = if (packageName == WECHAT_PACKAGE) "微信" else "支付宝"

internal fun stableCaptureId(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
