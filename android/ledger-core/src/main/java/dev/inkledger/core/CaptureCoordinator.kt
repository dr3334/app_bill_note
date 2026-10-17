package dev.inkledger.core

import kotlinx.coroutines.CancellationException
import java.util.UUID

enum class CaptureStage { CAPTURE, RECOGNITION, PARSING, SAVING }

sealed interface CaptureOutcome {
    data class SavedPending(val entry: LedgerEntry) : CaptureOutcome
    data class Duplicate(val existing: LedgerEntry) : CaptureOutcome
    data class NeedsReview(val missingFields: List<String>, val reason: String) : CaptureOutcome
    data class Unavailable(val stage: CaptureStage, val reason: String) : CaptureOutcome
    data class Failed(val stage: CaptureStage, val reason: String) : CaptureOutcome
}

/** A working composition boundary. Default ports explicitly report Unavailable.
 * Any future parser result is saved PENDING, never silently auto-confirmed.
 * No persistent copy of receipt text/image, cloud call, or permission request is made here.
 */
class CaptureCoordinator(
    private val repository: LedgerRepository,
    private val source: CaptureSource = UnavailableCaptureSource(),
    private val recognition: RecognitionEngine = ProvidedTextRecognitionEngine(),
    private val parser: ReceiptParser = UnavailableReceiptParser(),
    private val capsule: CapsulePresenter = UnavailableSystemCapsulePresenter(),
) {
    suspend fun collect(): CaptureOutcome {
        var stage = CaptureStage.CAPTURE
        val outcome = try {
            process { stage = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CaptureOutcome.Failed(stage, "本次处理未完成，请重试或手动添加")
        }
        val status = when (outcome) {
            is CaptureOutcome.SavedPending -> CapsuleStatus(CapsuleState.PENDING, "有一笔待确认", outcome.entry.id)
            is CaptureOutcome.Duplicate -> CapsuleStatus(CapsuleState.DUPLICATE, "这份信息已记录", outcome.existing.id)
            is CaptureOutcome.NeedsReview -> CapsuleStatus(CapsuleState.PENDING, outcome.reason)
            is CaptureOutcome.Unavailable -> CapsuleStatus(CapsuleState.UNAVAILABLE, outcome.reason)
            is CaptureOutcome.Failed -> CapsuleStatus(CapsuleState.FAILED, outcome.reason)
        }
        showStatus(status)
        return outcome
    }

    private suspend fun process(onStage: (CaptureStage) -> Unit): CaptureOutcome {
        val evidence = when (val result = source.capture()) {
            is CaptureResult.Captured -> result.evidence
            is CaptureResult.Unavailable -> return CaptureOutcome.Unavailable(CaptureStage.CAPTURE, result.reason)
            is CaptureResult.Failed -> return CaptureOutcome.Failed(CaptureStage.CAPTURE, result.reason)
        }
        if (evidence.evidenceId.isBlank() || evidence.capturedAt <= 0L) {
            return CaptureOutcome.Failed(CaptureStage.CAPTURE, "缺少稳定来源标识或有效采集时间")
        }
        showStatus(CapsuleStatus(CapsuleState.PROCESSING, "正在识别"))
        onStage(CaptureStage.RECOGNITION)
        val text = when (val result = recognition.recognize(evidence)) {
            is RecognitionResult.Recognized -> result.text
            is RecognitionResult.Unavailable -> return CaptureOutcome.Unavailable(CaptureStage.RECOGNITION, result.reason)
            is RecognitionResult.Failed -> return CaptureOutcome.Failed(CaptureStage.RECOGNITION, result.reason)
        }
        onStage(CaptureStage.PARSING)
        val receipt = when (val result = parser.parse(text, evidence)) {
            is ReceiptParseResult.Parsed -> result.receipt
            is ReceiptParseResult.NeedsReview -> return CaptureOutcome.NeedsReview(result.missingFields, result.reason)
            is ReceiptParseResult.Unavailable -> return CaptureOutcome.Unavailable(CaptureStage.PARSING, result.reason)
            is ReceiptParseResult.Failed -> return CaptureOutcome.Failed(CaptureStage.PARSING, result.reason)
        }
        val entry = LedgerEntry(
            id = UUID.randomUUID().toString(), merchant = receipt.merchant,
            amountMinor = receipt.amountMinor, category = receipt.category,
            channel = evidence.channel, occurredAt = receipt.occurredAt,
            state = EntryState.PENDING, evidenceId = evidence.evidenceId,
            kind = receipt.kind, accountLabel = receipt.accountLabel,
        )
        onStage(CaptureStage.SAVING)
        return when (val result = repository.save(entry)) {
            is SaveResult.Saved -> CaptureOutcome.SavedPending(result.entry)
            is SaveResult.Duplicate -> CaptureOutcome.Duplicate(result.existing)
            is SaveResult.Rejected -> CaptureOutcome.Failed(CaptureStage.SAVING, result.reason)
        }
    }

    private suspend fun showStatus(status: CapsuleStatus) {
        try {
            capsule.present(status)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Presentation is an optional side effect; it cannot undo a committed save.
        }
    }
}
