/*
 * Copyright (C) 2024 ankio(ankio@ankio.net)
 * Modified 2026-10-18 for Ink Ledger.
 * Monthly SUM/COALESCE, category GROUP BY and half-open date range SQL adapted
 * from AutoAccounting BillInfoDao.kt @ 34b38f82ca9ad6d7905c715c66891977f8809c88.
 * Changes: new schema, integer fen, confirmed-only, exact evidence identity.
 * GPL v3 repository-level terms; see LICENSE and UPSTREAM.md.
 */
package dev.inkledger.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.inkledger.core.CategoryTotal
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LedgerDao {
    @Query("SELECT * FROM ledger_entries ORDER BY occurredAt DESC, id ASC")
    fun observeEntries(): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entries WHERE id = :id")
    suspend fun findById(id: String): LedgerEntryEntity?

    @Query("SELECT * FROM ledger_entries WHERE evidenceId = :evidenceId LIMIT 1")
    suspend fun findByEvidence(evidenceId: String): LedgerEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: LedgerEntryEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(entry: LedgerEntryEntity)

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM ledger_entries")
    suspend fun clearEntries()

    @Query("SELECT COUNT(*) FROM ledger_entries")
    suspend fun count(): Int

    @Query("SELECT value FROM ledger_metadata WHERE `key` = :key")
    suspend fun metadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMetadata(value: LedgerMetadata)

    @Query("""
        SELECT COALESCE(SUM(amountMinor), 0) FROM ledger_entries
        WHERE type = :type AND state IN ('Edited', 'Synced')
          AND occurredAt >= :startTime AND occurredAt < :endTime
    """)
    suspend fun sumConfirmedByType(type: String, startTime: Long, endTime: Long): Long

    @Query("""
        SELECT category, SUM(amountMinor) AS amountMinor, COUNT(*) AS count
        FROM ledger_entries
        WHERE type = 'Expend' AND state IN ('Edited', 'Synced')
          AND occurredAt >= :startTime AND occurredAt < :endTime
        GROUP BY category ORDER BY amountMinor DESC, category ASC
    """)
    suspend fun expenseCategoryTotals(startTime: Long, endTime: Long): List<CategoryTotal>

    @Query("""
        SELECT COUNT(*) FROM ledger_entries
        WHERE state IN ('Edited', 'Synced') AND occurredAt >= :startTime AND occurredAt < :endTime
    """)
    suspend fun confirmedCount(startTime: Long, endTime: Long): Int

    @Query("""
        SELECT COUNT(*) FROM ledger_entries
        WHERE state = 'Wait2Edit' AND occurredAt >= :startTime AND occurredAt < :endTime
    """)
    suspend fun pendingCount(startTime: Long, endTime: Long): Int
}
