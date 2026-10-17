package dev.inkledger.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkledger.core.data.InkLedgerDatabase
import dev.inkledger.core.data.LedgerEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RoomLedgerRepositoryTest {
    private lateinit var database: InkLedgerDatabase
    private lateinit var repo: RoomLedgerRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), InkLedgerDatabase::class.java,
        ).build()
        repo = RoomLedgerRepository(database, isDemoDatabase = true)
    }

    @After fun tearDown() { database.close() }

    private fun entry(id: String, evidence: String? = "evidence:$id") = LedgerEntry(
        id, "测试商户", 1230L, "餐饮", PaymentChannel.WECHAT,
        Instant.parse("2026-10-18T08:00:00Z").toEpochMilli(), EntryState.PENDING, evidence,
    )

    @Test fun concurrentEvidenceRetriesInsertExactlyOneRow() = runBlocking {
        val results = (1..20).map { index ->
            async(Dispatchers.IO) { repo.save(entry("retry-$index", "same-evidence")) }
        }.awaitAll()
        assertEquals(1, results.count { it is SaveResult.Saved })
        assertEquals(19, results.count { it is SaveResult.Duplicate })
        assertEquals(1, repo.observeEntries().first().size)
    }

    @Test fun uniqueIndexProtectsAgainstWritesOutsideRepository() = runBlocking {
        database.ledgerDao().insert(LedgerEntryEntity.from(entry("first", "same")))
        var rejected = false
        try {
            database.ledgerDao().insert(LedgerEntryEntity.from(entry("second", "same")))
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            rejected = true
        }
        assertTrue("Unique evidence index must reject duplicate SQL inserts", rejected)
        assertEquals(1, database.ledgerDao().count())
    }

    @Test fun confirmingExistingEntryPreservesItsEvidenceIdentity() = runBlocking {
        val original = entry("original")
        assertTrue(repo.save(original) is SaveResult.Saved)
        assertTrue(repo.save(original.copy(state = EntryState.CONFIRMED)) is SaveResult.Saved)
        assertEquals(EntryState.CONFIRMED, repo.observeEntries().first().single().state)
        assertTrue(repo.save(original.copy(evidenceId = "replacement")) is SaveResult.Rejected)
        assertEquals(original.evidenceId, repo.observeEntries().first().single().evidenceId)
    }

    @Test fun midBatchIdentityFailureRollsBackEarlierInsert() = runBlocking {
        val existing = entry("existing")
        repo.save(existing)
        try {
            repo.saveBatch(listOf(entry("new"), existing.copy(evidenceId = "changed")))
            fail("Batch should reject evidence mutation")
        } catch (_: BatchSaveException) { /* Expected, after rollback. */ }
        assertEquals(listOf(existing), repo.observeEntries().first())
    }

    @Test fun duplicatesWithinBatchDoNotOverwriteOrMultiply() = runBlocking {
        val result = repo.saveBatch(listOf(entry("one", "same"), entry("two", "same")))
        assertTrue(result[0] is SaveResult.Saved)
        assertTrue(result[1] is SaveResult.Duplicate)
        assertEquals("one", (result[1] as SaveResult.Duplicate).existing.id)
        assertEquals(1, repo.observeEntries().first().size)
    }

    @Test fun emptyEvidenceAndZeroAmountRejectWholeBatch() = runBlocking {
        try {
            repo.saveBatch(listOf(entry("valid"), entry("invalid").copy(amountMinor = 0L)))
            fail("Zero amounts must be rejected")
        } catch (_: BatchSaveException) { }
        assertTrue(repo.observeEntries().first().isEmpty())
        assertTrue(repo.save(entry("blank", " ")) is SaveResult.Rejected)
    }

    @Test fun seedMarkerSurvivesClearingEntries() = runBlocking {
        repo.seedDemoIfNeeded()
        val count = repo.observeEntries().first().size
        assertTrue(count > 0)
        repo.seedDemoIfNeeded()
        assertEquals(count, repo.observeEntries().first().size)
        repo.clearEntries()
        repo.seedDemoIfNeeded()
        assertTrue(repo.observeEntries().first().isEmpty())
        assertEquals("1", database.ledgerDao().metadata(RoomLedgerRepository.DEMO_SEED_KEY))
    }

    @Test fun sqlAndPureStatsAgreeOnConfirmationRefundAndTransferSemantics() = runBlocking {
        repo.saveBatch(listOf(
            entry("pending"),
            entry("expense").copy(state = EntryState.CONFIRMED, amountMinor = 10000L),
            entry("refund").copy(state = EntryState.CONFIRMED, kind = EntryKind.REFUND, amountMinor = 2500L),
            entry("income").copy(state = EntryState.CONFIRMED, kind = EntryKind.INCOME, amountMinor = 20000L),
            entry("transfer").copy(state = EntryState.CONFIRMED, kind = EntryKind.TRANSFER, amountMinor = 90000L),
        ))
        val month = YearMonth.of(2026, 10)
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(LedgerStats.forMonth(repo.observeEntries().first(), month, zone), repo.statsForMonth(month, zone))
    }
}
