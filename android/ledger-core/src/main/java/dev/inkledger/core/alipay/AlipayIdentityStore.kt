package dev.inkledger.core.alipay

import androidx.room.withTransaction
import dev.inkledger.core.EntryKind
import dev.inkledger.core.EntryState
import dev.inkledger.core.LedgerEntry
import dev.inkledger.core.PaymentChannel
import dev.inkledger.core.data.AlipayEvidenceEntity
import dev.inkledger.core.data.AlipayReceiptEntity
import dev.inkledger.core.data.InkLedgerDatabase
import dev.inkledger.core.data.LedgerEntryEntity
import java.util.UUID

/** No fuzzy match ever returns Duplicate. Weak matches only create review work.
 * Journal + ledger changes commit together, and tombstones survive ledger deletion.
 */
internal class AlipayIdentityStore(private val database: InkLedgerDatabase) {
    private val captures = database.alipayCaptureDao()
    private val ledger = database.ledgerDao()

    suspend fun accept(evidence: AlipayEvidence, candidate: AlipayPaymentCandidate): AlipayProcessResult = database.withTransaction {
        val keys = PaymentEvidenceIdentity.from(evidence)
        val oldEvent = captures.event(keys.event)
        if (oldEvent != null) {
            val oldReceipt = checkNotNull(captures.receipt(oldEvent.receiptId))
            return@withTransaction previousResult(oldReceipt, decode(oldEvent.diagnosticCodes))
        }
        val transactionKey = PaymentEvidenceIdentity.transactionKey(evidence, candidate.transactionId)
        val merchantKey = PaymentEvidenceIdentity.merchantKey(evidence, candidate.merchant)
        val known = transactionKey?.let { captures.receiptByTransaction(it) }
        if (known != null) {
            if (known.deleted) {
                link(keys, evidence, known.id)
                return@withTransaction AlipayProcessResult.Duplicate(null, deleted = true)
            }
            val conflict = (known.amountMinor != null && candidate.amountMinor != null && known.amountMinor != candidate.amountMinor) ||
                (known.merchantKey != null && merchantKey != null && known.merchantKey != merchantKey)
            if (conflict) {
                val codes = listOf(AlipayDiagnosticCode.STORED_IDENTITY_CONFLICT)
                link(keys, evidence, known.id, codes)
                return@withTransaction review(known.entryId?.let { ledger.findById(it)?.toEntry() }, codes)
            }
            if (known.entryId != null) {
                // Preserve user edits, pending status, and original data; never reapply a capture.
                link(keys, evidence, known.id)
                return@withTransaction previousResult(known)
            }
            // A previous incomplete event had no ledger row. Strong identity can now supply
            // missing fields, but never modify a user-edited or deleted ledger entry.
            return@withTransaction createOrComplete(evidence, candidate, keys, transactionKey, merchantKey, known)
        }

        val start = (evidence.observedAt - CORRELATION_WINDOW_MS).coerceAtLeast(0L)
        val end = evidence.observedAt + CORRELATION_WINDOW_MS
        fun possiblySame(receipt: AlipayReceiptEntity): Boolean =
            transactionKey == null || receipt.transactionKey == null || receipt.transactionKey == transactionKey
        val sameCapture = captures.receiptsForCapture(keys.capture).filter(::possiblySame)
        val sameSession = keys.session?.let { captures.receiptsForSession(it, start, end) }.orEmpty().filter(::possiblySame)
        val samePayload = captures.receiptsForPayload(keys.payload).filter(::possiblySame)
        val nearby = captures.recentReceipts(start, end).filter(::possiblySame).filter {
            candidate.amountMinor != null && merchantKey != null && it.amountMinor == candidate.amountMinor && it.merchantKey == merchantKey
        }

        // A stronger distinct transaction number wins over same-price/session resemblance.
        // Without it, never resurrect an indistinguishable deleted event automatically.
        val deletedReplay = captures.hasDeletedReplay(keys.capture, keys.payload, transactionKey)
        if (deletedReplay) {
            val codes = (candidate.reviewCodes + AlipayDiagnosticCode.POSSIBLE_DELETED_REPLAY).distinct()
            return@withTransaction createOrComplete(evidence, candidate.copy(reviewCodes = codes), keys,
                transactionKey, merchantKey, suppressEntry = true)
        }

        val weakCodes = mutableListOf<AlipayDiagnosticCode>()
        if (sameCapture.isNotEmpty()) weakCodes += AlipayDiagnosticCode.EVENT_CHANGED
        // Reopening an old page must not become a new confirmed payment when the
        // short correlation window expires. Identical content is still only a weak
        // clue: a genuine new payment receives its own pending row, never a merge.
        if (sameSession.isNotEmpty() || nearby.isNotEmpty() || samePayload.isNotEmpty()) {
            weakCodes += AlipayDiagnosticCode.POSSIBLE_DUPLICATE
        }
        createOrComplete(evidence, candidate.copy(reviewCodes = (candidate.reviewCodes + weakCodes).distinct()),
            keys, transactionKey, merchantKey)
    }

    private suspend fun createOrComplete(
        evidence: AlipayEvidence,
        candidate: AlipayPaymentCandidate,
        keys: PaymentEvidenceIdentity,
        transactionKey: String?,
        merchantKey: String?,
        previous: AlipayReceiptEntity? = null,
        suppressEntry: Boolean = false,
    ): AlipayProcessResult {
        val codes = candidate.reviewCodes
        val entry = if (candidate.amountMinor == null || suppressEntry) null else LedgerEntry(
            id = UUID.randomUUID().toString(),
            merchant = candidate.merchant ?: "${if (evidence.packageName == WECHAT_PACKAGE_NAME) "微信" else "支付宝"}付款（商户待核对）",
            amountMinor = candidate.amountMinor,
            category = "其他",
            channel = if (evidence.packageName == WECHAT_PACKAGE_NAME) PaymentChannel.WECHAT else PaymentChannel.ALIPAY,
            occurredAt = candidate.transactionTime?.takeIf { it > 0L } ?: evidence.observedAt,
            state = if (codes.isEmpty()) EntryState.CONFIRMED else EntryState.PENDING,
            evidenceId = "${evidence.namespace()}:receipt:${previous?.id ?: keys.event}",
            kind = EntryKind.EXPENSE,
        )
        if (entry != null) ledger.insert(LedgerEntryEntity.from(entry))
        val receipt = AlipayReceiptEntity(
            id = previous?.id ?: UUID.randomUUID().toString(), transactionKey = transactionKey,
            entryId = entry?.id, amountMinor = candidate.amountMinor, merchantKey = merchantKey,
            transactionTime = candidate.transactionTime, firstObservedAt = previous?.firstObservedAt ?: evidence.observedAt,
            lastObservedAt = evidence.observedAt, firstSource = previous?.firstSource ?: evidence.source.name,
            deleted = false, userEdited = false, diagnosticCodes = encode(codes),
        )
        if (previous == null) captures.insertReceipt(receipt) else captures.updateReceipt(receipt)
        link(keys, evidence, receipt.id, codes)
        return if (entry != null && entry.state == EntryState.CONFIRMED) AlipayProcessResult.Saved(entry)
            else review(entry, codes)
    }

    private suspend fun previousResult(receipt: AlipayReceiptEntity, eventCodes: List<AlipayDiagnosticCode> = emptyList()): AlipayProcessResult {
        if (receipt.deleted) return AlipayProcessResult.Duplicate(null, deleted = true)
        val entry = receipt.entryId?.let { ledger.findById(it)?.toEntry() }
        if (eventCodes.contains(AlipayDiagnosticCode.STORED_IDENTITY_CONFLICT)) return review(entry, eventCodes)
        if (entry != null) return AlipayProcessResult.Duplicate(entry)
        return review(null, eventCodes.ifEmpty { decode(receipt.diagnosticCodes) })
    }

    private suspend fun link(keys: PaymentEvidenceIdentity, evidence: AlipayEvidence, receiptId: String, codes: List<AlipayDiagnosticCode> = emptyList()) {
        captures.insertEvent(AlipayEvidenceEntity(keys.event, keys.capture, keys.session, keys.payload,
            evidence.source.name, evidence.observedAt, receiptId, encode(codes)))
    }

    private fun review(entry: LedgerEntry?, codes: List<AlipayDiagnosticCode>) =
        AlipayProcessResult.NeedsReview(entry, codes.map { it.message }, codes)

    private fun encode(codes: List<AlipayDiagnosticCode>): String = codes.joinToString(",") { it.name }
    private fun decode(value: String): List<AlipayDiagnosticCode> = value.split(',').mapNotNull { name ->
        AlipayDiagnosticCode.entries.find { it.name == name }
    }

    companion object { private const val CORRELATION_WINDOW_MS = 10 * 60_000L }
}
