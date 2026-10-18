package dev.inkledger.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local identity journal. No captured text, image, raw transaction id, or account credentials. */
@Entity(tableName = "alipay_receipts", indices = [
    Index(value = ["transactionKey"], unique = true),
    Index(value = ["entryId"], unique = true),
    Index(value = ["lastObservedAt"]),
])
internal data class AlipayReceiptEntity(
    @PrimaryKey val id: String,
    val transactionKey: String?,
    val entryId: String?,
    val amountMinor: Long?,
    val merchantKey: String?,
    val transactionTime: Long?,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val firstSource: String,
    val deleted: Boolean,
    val userEdited: Boolean,
    val diagnosticCodes: String,
)

@Entity(tableName = "alipay_evidence", indices = [
    Index(value = ["captureKey"]), Index(value = ["sessionKey"]),
    Index(value = ["payloadHash"]), Index(value = ["receiptId"]),
])
internal data class AlipayEvidenceEntity(
    @PrimaryKey val eventKey: String,
    val captureKey: String,
    val sessionKey: String?,
    val payloadHash: String,
    val source: String,
    val observedAt: Long,
    val receiptId: String,
    val diagnosticCodes: String,
)
