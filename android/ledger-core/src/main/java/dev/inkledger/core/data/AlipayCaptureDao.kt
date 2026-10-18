package dev.inkledger.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface AlipayCaptureDao {
    @Query("SELECT * FROM alipay_evidence WHERE eventKey = :key")
    suspend fun event(key: String): AlipayEvidenceEntity?

    @Query("SELECT * FROM alipay_receipts WHERE id = :id")
    suspend fun receipt(id: String): AlipayReceiptEntity?

    @Query("SELECT * FROM alipay_receipts WHERE transactionKey = :key")
    suspend fun receiptByTransaction(key: String): AlipayReceiptEntity?

    @Query("""SELECT DISTINCT r.* FROM alipay_receipts r JOIN alipay_evidence e ON e.receiptId = r.id
        WHERE e.captureKey = :key ORDER BY r.lastObservedAt DESC LIMIT 20""")
    suspend fun receiptsForCapture(key: String): List<AlipayReceiptEntity>

    @Query("""SELECT DISTINCT r.* FROM alipay_receipts r JOIN alipay_evidence e ON e.receiptId = r.id
        WHERE e.sessionKey = :key AND e.observedAt >= :start AND e.observedAt <= :end
        ORDER BY r.lastObservedAt DESC LIMIT 50""")
    suspend fun receiptsForSession(key: String, start: Long, end: Long): List<AlipayReceiptEntity>

    @Query("""SELECT DISTINCT r.* FROM alipay_receipts r JOIN alipay_evidence e ON e.receiptId = r.id
        WHERE e.payloadHash = :hash ORDER BY r.lastObservedAt DESC LIMIT 50""")
    suspend fun receiptsForPayload(hash: String): List<AlipayReceiptEntity>

    // Tombstones must never fall out of a recent-result LIMIT during repeated replay.
    @Query("""SELECT EXISTS(SELECT 1 FROM alipay_receipts r JOIN alipay_evidence e ON e.receiptId = r.id
        WHERE r.deleted = 1 AND (e.captureKey = :captureKey OR e.payloadHash = :payloadHash)
        AND (:transactionKey IS NULL OR r.transactionKey IS NULL OR r.transactionKey = :transactionKey))""")
    suspend fun hasDeletedReplay(captureKey: String, payloadHash: String, transactionKey: String?): Boolean

    @Query("SELECT * FROM alipay_receipts WHERE lastObservedAt >= :start AND lastObservedAt <= :end ORDER BY lastObservedAt DESC LIMIT 200")
    suspend fun recentReceipts(start: Long, end: Long): List<AlipayReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: AlipayReceiptEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateReceipt(receipt: AlipayReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(evidence: AlipayEvidenceEntity)

    @Query("UPDATE alipay_receipts SET deleted = 1 WHERE entryId = :entryId")
    suspend fun markDeleted(entryId: String)

    @Query("UPDATE alipay_receipts SET deleted = 1 WHERE entryId IS NOT NULL")
    suspend fun markAllDeleted()

    @Query("UPDATE alipay_receipts SET userEdited = 1 WHERE entryId = :entryId")
    suspend fun markUserEdited(entryId: String)

    @Query("SELECT COUNT(*) FROM alipay_receipts")
    suspend fun receiptCount(): Int
}
