package dev.inkledger.core

import kotlinx.coroutines.flow.Flow

interface LedgerRepository {
    /** Latest first, with stable id ordering when timestamps tie. */
    fun observeEntries(): Flow<List<LedgerEntry>>

    /** Inserts or edits by id; evidence identity cannot be changed by an edit. */
    suspend fun save(entry: LedgerEntry): SaveResult

    /** Atomic batch: duplicates are returned; invalid/storage failure throws and
     * rolls back all changes. No Saved result is returned before the commit.
     */
    suspend fun saveBatch(entries: List<LedgerEntry>): List<SaveResult>

    suspend fun delete(id: String)

    /** Only the independent demo database is seeded; clearing never reseeds it. */
    suspend fun seedDemoIfNeeded()
}

class BatchSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)
