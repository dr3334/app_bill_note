package dev.inkledger.core.alipay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkledger.core.EntryState
import dev.inkledger.core.RoomLedgerRepository
import dev.inkledger.core.SaveResult
import dev.inkledger.core.data.InkLedgerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Real Room transactions with synthetic payment evidence, never a real Alipay fixture. */
@RunWith(AndroidJUnit4::class)
class AlipayProcessorRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "alipay-test-${UUID.randomUUID()}.db"
    private lateinit var database: InkLedgerDatabase
    private lateinit var repo: RoomLedgerRepository
    private lateinit var processor: AlipayProcessor
    private val now = Instant.parse("2026-10-18T04:00:00Z").toEpochMilli()

    @Before fun setUp() { open() }
    @After fun tearDown() { database.close(); context.deleteDatabase(databaseName) }

    private fun open() {
        database = Room.databaseBuilder(context, InkLedgerDatabase::class.java, databaseName)
            .addMigrations(InkLedgerDatabase.MIGRATION_1_2).build()
        repo = RoomLedgerRepository(database, isDemoDatabase = false)
        processor = AlipayProcessor(repo, AlipayReceiptParser(ZoneId.of("Asia/Shanghai")))
    }

    private fun evidence(
        capture: String = "event-1", transaction: String? = "202610180000000001",
        amount: String = "18.00", source: AlipaySource = AlipaySource.NOTIFICATION,
        session: String? = null, time: Long = now,
    ) = AlipayEvidence(ALIPAY_PACKAGE_NAME, source, buildList {
        add("付款成功"); add("实付金额：$amount 元"); add("商家：合成咖啡店")
        transaction?.let { add("交易号：$it") }
        add("交易时间：2026-10-18 12:00:00")
    }, time, capture, session)

    @Test fun concurrentSourceRetriesCommitOneLedgerAndOneReceipt() = runBlocking {
        val results = (1..20).map { async(Dispatchers.IO) { processor.process(evidence()) } }.awaitAll()
        assertEquals(1, results.count { it is AlipayProcessResult.Saved })
        assertEquals(19, results.count { it is AlipayProcessResult.Duplicate })
        assertEquals(1, repo.observeEntries().first().size)
        assertEquals(1, database.alipayCaptureDao().receiptCount())
    }

    @Test fun reliableTransactionIdDeduplicatesNotificationAndVisiblePage() = runBlocking {
        val first = processor.process(evidence()) as AlipayProcessResult.Saved
        val second = processor.process(evidence("page-1", source = AlipaySource.ACCESSIBILITY)) as AlipayProcessResult.Duplicate
        assertEquals(first.entry, second.entry)
        assertEquals(1, repo.observeEntries().first().size)
    }

    @Test fun distinctTransactionsAtSamePriceAndSameNotificationSlotRemainTwoConfirmedPayments() = runBlocking {
        assertTrue(processor.process(evidence(session = "slot")) is AlipayProcessResult.Saved)
        assertTrue(processor.process(evidence("event-2", "202610180000000002", session = "slot")) is AlipayProcessResult.Saved)
        assertEquals(2, repo.observeEntries().first().count { it.state == EntryState.CONFIRMED })
    }

    @Test fun noIdentitySamePriceIsSeparatePendingCandidateNeverSilentlySwallowed() = runBlocking {
        assertTrue(processor.process(evidence(transaction = null)) is AlipayProcessResult.Saved)
        val next = processor.process(evidence("other-event", transaction = null)) as AlipayProcessResult.NeedsReview
        assertNotNull(next.entry)
        assertEquals(EntryState.PENDING, next.entry!!.state)
        assertTrue(next.codes.contains(AlipayDiagnosticCode.POSSIBLE_DUPLICATE))
        assertEquals(2, repo.observeEntries().first().size)
        assertEquals(1, repo.observeEntries().first().count { it.state == EntryState.CONFIRMED })
    }

    @Test fun sameNotificationSlotAmountUpdateWithNewCaptureIdCannotConfirmTwice() = runBlocking {
        assertTrue(processor.process(evidence(transaction = null, session = "slot")) is AlipayProcessResult.Saved)
        val update = processor.process(evidence("updated-post-time-and-text", transaction = null, amount = "19.00", session = "slot"))
            as AlipayProcessResult.NeedsReview
        assertEquals(1900L, update.entry!!.amountMinor)
        assertTrue(update.codes.contains(AlipayDiagnosticCode.POSSIBLE_DUPLICATE))
        assertEquals(1, repo.observeEntries().first().count { it.state == EntryState.CONFIRMED })
    }

    @Test fun sameCapturePayloadChangeNeedsReviewEvenWithoutSession() = runBlocking {
        processor.process(evidence(transaction = null))
        val update = processor.process(evidence(transaction = null, amount = "19.00")) as AlipayProcessResult.NeedsReview
        assertTrue(update.codes.contains(AlipayDiagnosticCode.EVENT_CHANGED))
        assertEquals(EntryState.PENDING, update.entry!!.state)
    }

    @Test fun reopeningIdenticalPageAfterCorrelationWindowCannotConfirmTwice() = runBlocking {
        assertTrue(processor.process(evidence(transaction = null, source = AlipaySource.ACCESSIBILITY)) is AlipayProcessResult.Saved)
        val reopened = processor.process(evidence("new-page-session", transaction = null,
            source = AlipaySource.ACCESSIBILITY, time = now + 30 * 60_000L)) as AlipayProcessResult.NeedsReview
        assertTrue(reopened.codes.contains(AlipayDiagnosticCode.POSSIBLE_DUPLICATE))
        assertEquals(EntryState.PENDING, reopened.entry!!.state)
        assertEquals(1, repo.observeEntries().first().count { it.state == EntryState.CONFIRMED })
    }

    @Test fun userEditIsNeverOverwrittenByReplayOrAnotherSource() = runBlocking {
        val original = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        val edited = original.copy(amountMinor = 1234L, category = "交通", merchant = "用户更正商户")
        assertTrue(repo.save(edited) is SaveResult.Saved)
        assertEquals(edited, (processor.process(evidence()) as AlipayProcessResult.Duplicate).entry)
        assertEquals(edited, (processor.process(evidence("page", source = AlipaySource.ACCESSIBILITY)) as AlipayProcessResult.Duplicate).entry)
        assertEquals(listOf(edited), repo.observeEntries().first())
    }

    @Test fun deletedTransactionSurvivesRestartAndCannotBeResurrected() = runBlocking {
        val original = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        repo.delete(original.id)
        database.close(); open()
        assertTrue((processor.process(evidence()) as AlipayProcessResult.Duplicate).deleted)
        assertTrue((processor.process(evidence("page-reopened", source = AlipaySource.ACCESSIBILITY)) as AlipayProcessResult.Duplicate).deleted)
        assertTrue(repo.observeEntries().first().isEmpty())
    }

    @Test fun normalTransactionIdentityAlsoSurvivesRestart() = runBlocking {
        val original = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        database.close(); open()
        assertEquals(original, (processor.process(evidence("after-restart")) as AlipayProcessResult.Duplicate).entry)
        assertEquals(1, repo.observeEntries().first().size)
    }

    @Test fun deletedPayloadWithoutTransactionIdNeverRecreatesEvenAfterManyReplays() = runBlocking {
        val original = (processor.process(evidence(transaction = null)) as AlipayProcessResult.Saved).entry
        repo.delete(original.id)
        // More than the ordinary correlation query's LIMIT checks durable tombstone lookup.
        repeat(55) { index ->
            val replay = processor.process(evidence("new-capture-$index", transaction = null, time = now + index * 1000L))
                as AlipayProcessResult.NeedsReview
            assertNull(replay.entry)
            assertTrue(replay.codes.contains(AlipayDiagnosticCode.POSSIBLE_DELETED_REPLAY))
        }
        assertTrue(repo.observeEntries().first().isEmpty())
    }

    @Test fun clearLedgerKeepsCaptureTombstonesAndLiveLedgerNeverSeedsSamples() = runBlocking {
        repo.seedDemoIfNeeded()
        assertTrue(repo.observeEntries().first().isEmpty())
        processor.process(evidence())
        repo.clearEntries()
        assertTrue((processor.process(evidence()) as AlipayProcessResult.Duplicate).deleted)
        assertTrue(repo.observeEntries().first().isEmpty())
        assertNull(database.ledgerDao().metadata(RoomLedgerRepository.DEMO_SEED_KEY))
        assertNotEquals(RoomLedgerRepository.DATABASE_NAME, RoomLedgerRepository.LIVE_DATABASE_NAME)
    }

    @Test fun conflictingAmountForSameTradeIdDoesNotModifyOrInsertAnotherRow() = runBlocking {
        val original = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        val result = processor.process(evidence("conflicting", amount = "19.00")) as AlipayProcessResult.NeedsReview
        assertEquals(original, result.entry)
        assertEquals(listOf(AlipayDiagnosticCode.STORED_IDENTITY_CONFLICT), result.codes)
        assertEquals(listOf(original), repo.observeEntries().first())
    }

    @Test fun incompleteSuccessHasNoZeroAmountRowAndOcrRemainsPending() = runBlocking {
        val missing = evidence().copy(textLines = evidence().textLines.filterNot { it.startsWith("实付金额") })
        val result = processor.process(missing) as AlipayProcessResult.NeedsReview
        assertNull(result.entry)
        assertTrue(result.codes.contains(AlipayDiagnosticCode.NO_AMOUNT))
        assertTrue(repo.observeEntries().first().isEmpty())
        val ocr = processor.process(evidence("ocr", source = AlipaySource.OCR)) as AlipayProcessResult.NeedsReview
        assertEquals(EntryState.PENDING, ocr.entry!!.state)
        assertTrue(ocr.codes.contains(AlipayDiagnosticCode.OCR_UNVERIFIED))
        assertEquals(0, repo.observeEntries().first().count { it.state == EntryState.CONFIRMED })
    }

    @Test fun demoDatabaseRejectsCaptureAndUntrustedPackagesLeaveNoJournal() = runBlocking {
        val demoProcessor = AlipayProcessor(RoomLedgerRepository(database, isDemoDatabase = true))
        assertTrue(demoProcessor.process(evidence()) is AlipayProcessResult.Failed)
        assertTrue(processor.process(evidence().copy(packageName = "fake.alipay")) is AlipayProcessResult.Ignored)
        assertEquals(0, database.alipayCaptureDao().receiptCount())
        assertTrue(repo.observeEntries().first().isEmpty())
    }
}
