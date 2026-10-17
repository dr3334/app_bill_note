package dev.inkledger.core

import java.security.MessageDigest

/** Caller-owned durable identity; do not derive it from a temporary URI or current time. */
data class CaptureEvidence(
    val evidenceId: String,
    val channel: PaymentChannel,
    val capturedAt: Long,
    val text: String? = null,
    val localImageUri: String? = null,
)

object EvidenceIds {
    /** Same namespace + same original bytes gives the same identity across retries.
     * This does not claim two different capture channels represent the same payment.
     */
    fun fromBytes(namespace: String, originalBytes: ByteArray): String {
        require(namespace.isNotBlank()) { "Evidence namespace must be nonblank" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(namespace.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(originalBytes)
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

interface CaptureSource {
    val sourceId: String
    suspend fun capture(): CaptureResult
}

sealed interface CaptureResult {
    data class Captured(val evidence: CaptureEvidence) : CaptureResult
    data class Unavailable(val reason: String) : CaptureResult
    data class Failed(val reason: String) : CaptureResult
}

class UnavailableCaptureSource(
    override val sourceId: String = "automatic-capture",
    private val reason: String = "当前版本尚未接入真实微信或支付宝采集",
) : CaptureSource {
    override suspend fun capture(): CaptureResult = CaptureResult.Unavailable(reason)
}

interface RecognitionEngine {
    suspend fun recognize(evidence: CaptureEvidence): RecognitionResult
}

sealed interface RecognitionResult {
    data class Recognized(val text: String) : RecognitionResult
    data class Unavailable(val reason: String) : RecognitionResult
    data class Failed(val reason: String) : RecognitionResult
}

/** Already-provided text only. No image decoder, network request, or claimed OCR. */
class ProvidedTextRecognitionEngine : RecognitionEngine {
    override suspend fun recognize(evidence: CaptureEvidence): RecognitionResult =
        evidence.text?.takeIf { it.isNotBlank() }?.let(RecognitionResult::Recognized)
            ?: RecognitionResult.Unavailable("本地图片识别引擎尚未接入")
}

interface ReceiptParser {
    fun parse(text: String, evidence: CaptureEvidence): ReceiptParseResult
}

data class ParsedReceipt(
    val merchant: String,
    val amountMinor: Long,
    val category: String,
    val occurredAt: Long,
    val kind: EntryKind = EntryKind.EXPENSE,
    val accountLabel: String? = null,
)

sealed interface ReceiptParseResult {
    data class Parsed(val receipt: ParsedReceipt) : ReceiptParseResult
    /** Incomplete material stays outside the ledger; never invent a zero-amount bill. */
    data class NeedsReview(val missingFields: List<String>, val reason: String) : ReceiptParseResult
    data class Unavailable(val reason: String) : ReceiptParseResult
    data class Failed(val reason: String) : ReceiptParseResult
}

class UnavailableReceiptParser : ReceiptParser {
    override fun parse(text: String, evidence: CaptureEvidence): ReceiptParseResult =
        ReceiptParseResult.Unavailable("微信和支付宝票据规则尚未接入，请手动确认")
}

enum class CapsuleState { PROCESSING, PENDING, DUPLICATE, FAILED, UNAVAILABLE }

data class CapsuleStatus(val state: CapsuleState, val message: String, val entryId: String? = null)

interface CapsulePresenter {
    suspend fun present(status: CapsuleStatus): CapsulePresentationResult
}

sealed interface CapsulePresentationResult {
    data object PresentedInApp : CapsulePresentationResult
    data class Unavailable(val reason: String) : CapsulePresentationResult
}

/** Explicit fallback: neither displays nor claims a HONOR system capsule. */
class UnavailableSystemCapsulePresenter : CapsulePresenter {
    override suspend fun present(status: CapsuleStatus): CapsulePresentationResult =
        CapsulePresentationResult.Unavailable("荣耀系统胶囊接入尚未验证，请使用 App 内状态提示")
}

/** UI-owned callback, explicitly an in-app status surface, not a system capsule. */
class InAppCapsulePresenter(private val show: (CapsuleStatus) -> Unit) : CapsulePresenter {
    override suspend fun present(status: CapsuleStatus): CapsulePresentationResult {
        show(status)
        return CapsulePresentationResult.PresentedInApp
    }
}
