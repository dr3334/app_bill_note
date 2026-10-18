package dev.inkledger.core.alipay

import dev.inkledger.core.LedgerEntry

const val ALIPAY_PACKAGE_NAME: String = "com.eg.android.AlipayGphone"
const val WECHAT_PACKAGE_NAME: String = "com.tencent.mm"

// Keep legacy Alipay namespaces intact so upgrading does not re-import old receipts.
internal fun AlipayEvidence.namespace() = if (packageName == WECHAT_PACKAGE_NAME) "wechat" else "alipay"

enum class AlipaySource { NOTIFICATION, ACCESSIBILITY, OCR }

/** Caller must obtain packageName from Android, not from text on the screen.
 * captureId identifies a source event; sessionId is only a weak update/correlation hint.
 * Text is processed in memory and is never persisted by this module.
 */
data class AlipayEvidence(
    val packageName: String,
    val source: AlipaySource,
    val textLines: List<String>,
    val observedAt: Long,
    val captureId: String,
    val sessionId: String? = null,
)

/** Safe, fixed diagnostics: none includes captured text, merchant, or amount. */
enum class AlipayDiagnosticCode(val message: String) {
    UNTRUSTED_SOURCE("来源不是所选支付应用，已忽略"),
    NOT_PAYMENT_RECEIPT("未找到付款凭证结构，聊天或普通页面不会入账"),
    WECHAT_NOTIFICATION_UNVERIFIED("微信通知可能来自聊天，需核对付款凭证后确认"),
    INVALID_EVIDENCE("采集标识或时间无效"),
    INPUT_TOO_LARGE("页面信息过多，请打开单笔支付结果后重试"),
    NO_SUCCESS("未找到明确的支付成功状态"),
    MARKETING("营销或支付引导内容，未生成账单"),
    PAYMENT_NOT_COMPLETED("付款尚未完成或已失败，未生成账单"),
    UNSUPPORTED_KIND("收款、转账或退款需要单独核对，未按消费支出入账"),
    TRANSFER_NOT_EXPENSE("已识别为转账，不自动记为消费支出；请按实际用途手动记账"),
    NO_AMOUNT("已看到成功状态，但未识别出明确的付款金额"),
    AMBIGUOUS_AMOUNT("存在多个可能的付款金额，请核对"),
    INVALID_AMOUNT("付款金额超出支持范围或精度，请核对"),
    UNSUPPORTED_CURRENCY("当前仅支持人民币付款，请手动核对币种"),
    NO_MERCHANT("未识别出商户，请核对"),
    NO_TRANSACTION_TIME("交易时间未识别，待核对记录暂用采集时间"),
    AMBIGUOUS_IDENTITY("交易身份存在歧义，请核对"),
    OCR_UNVERIFIED("截图文字尚未核对，不会自动确认"),
    POSSIBLE_DUPLICATE("可能是已处理付款的另一份信息，请核对后决定"),
    POSSIBLE_DELETED_REPLAY("可能是已删除记录的旧信息，未重新生成账单"),
    EVENT_CHANGED("同一采集事件的付款信息发生变化，请核对"),
    STORED_IDENTITY_CONFLICT("同一交易标识出现不同付款信息，未覆盖原记录"),
    HISTORICAL_OR_FUTURE("交易时间与采集时间相差较大，请核对"),
    STORAGE_FAILURE("账单未保存成功，请重试"),
}

sealed interface AlipayProcessResult {
    data class Saved(val entry: LedgerEntry) : AlipayProcessResult
    /** entry is null when a durable tombstone suppresses replay after deletion. */
    data class Duplicate(val entry: LedgerEntry?, val deleted: Boolean = false) : AlipayProcessResult
    data class NeedsReview(
        val entry: LedgerEntry?,
        val reasons: List<String>,
        val codes: List<AlipayDiagnosticCode> = emptyList(),
    ) : AlipayProcessResult
    data class Ignored(
        val reason: String,
        val code: AlipayDiagnosticCode = AlipayDiagnosticCode.NO_SUCCESS,
    ) : AlipayProcessResult
    data class Failed(val reason: String) : AlipayProcessResult
}

/** Parser output is exposed for deterministic text-fixture testing. */
data class AlipayPaymentCandidate(
    val amountMinor: Long?,
    val merchant: String?,
    val transactionId: String?,
    val transactionTime: Long?,
    val reviewCodes: List<AlipayDiagnosticCode>,
)

sealed interface AlipayParseResult {
    data class Candidate(val payment: AlipayPaymentCandidate) : AlipayParseResult
    data class Ignored(val code: AlipayDiagnosticCode) : AlipayParseResult
}
