package dev.inkledger.core.alipay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkledger.core.EntryState
import dev.inkledger.core.PaymentChannel
import dev.inkledger.core.RoomLedgerRepository
import dev.inkledger.core.SaveResult
import dev.inkledger.core.data.InkLedgerDatabase
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

/** Synthetic evidence over real Room; compiled but not run when device testing is prohibited. */
@RunWith(AndroidJUnit4::class)
class WechatProcessorRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "wechat-test-${UUID.randomUUID()}.db"
    private lateinit var database: InkLedgerDatabase
    private lateinit var repo: RoomLedgerRepository
    private lateinit var processor: AlipayProcessor
    private val now = Instant.parse("2026-10-18T04:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")

    @Before fun setUp() { open() }
    @After fun tearDown() { database.close(); context.deleteDatabase(databaseName) }

    private fun open() {
        database = Room.databaseBuilder(context, InkLedgerDatabase::class.java, databaseName)
            .addMigrations(InkLedgerDatabase.MIGRATION_1_2).build()
        repo = RoomLedgerRepository(database, isDemoDatabase = false)
        processor = AlipayProcessor(repo, AlipayReceiptParser(zone), WechatReceiptParser(zone))
    }

    private fun evidence(capture: String = "page", source: AlipaySource = AlipaySource.ACCESSIBILITY,
        transaction: String? = "4200000000202610180000000001") = AlipayEvidence(
        WECHAT_PACKAGE_NAME, source, buildList {
            if (source == AlipaySource.NOTIFICATION) add("微信支付")
            add("支付成功"); add("付款金额：18.00元"); add("收款方：合成咖啡店")
            add("支付时间：2026-10-18 12:00:00")
            transaction?.let { add("交易单号：$it") }
        }, now, capture, "same-session")

    @Test fun completePageIsWechatExpenseAndProviderIdSurvivesRestart() = runBlocking {
        val original = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        assertEquals(PaymentChannel.WECHAT, original.channel)
        assertEquals(EntryState.CONFIRMED, original.state)
        assertTrue(original.evidenceId!!.startsWith("wechat:receipt:"))
        database.close(); open()
        assertEquals(original, (processor.process(evidence("reopened-page")) as AlipayProcessResult.Duplicate).entry)
        assertEquals(listOf(original), repo.observeEntries().first())
    }

    @Test fun sameProviderIdCaptureSessionAndPayloadInDifferentAppsStaySeparate() = runBlocking {
        val wechat = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        val alipay = (processor.process(evidence().copy(packageName = ALIPAY_PACKAGE_NAME)) as AlipayProcessResult.Saved).entry
        assertNotEquals(wechat.id, alipay.id)
        assertEquals(PaymentChannel.ALIPAY, alipay.channel)
        assertEquals(2, database.alipayCaptureDao().receiptCount())
        assertEquals(2, repo.observeEntries().first().size)
    }

    @Test fun deletedWechatReceiptDoesNotResurrectOrSuppressAlipayPayment() = runBlocking {
        val wechat = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        repo.delete(wechat.id)
        database.close(); open()
        assertTrue((processor.process(evidence("reopened-page")) as AlipayProcessResult.Duplicate).deleted)
        assertTrue(processor.process(evidence().copy(packageName = ALIPAY_PACKAGE_NAME)) is AlipayProcessResult.Saved)
        assertEquals(PaymentChannel.ALIPAY, repo.observeEntries().first().single().channel)
    }

    @Test fun notificationIsPendingAndCannotClaimStrongIdentityOverPage() = runBlocking {
        val notification = processor.process(evidence("notification", AlipaySource.NOTIFICATION)) as AlipayProcessResult.NeedsReview
        assertEquals(EntryState.PENDING, notification.entry!!.state)
        assertTrue(notification.codes.contains(AlipayDiagnosticCode.WECHAT_NOTIFICATION_UNVERIFIED))
        assertTrue(processor.process(evidence("notification", AlipaySource.NOTIFICATION)) is AlipayProcessResult.Duplicate)
        val page = processor.process(evidence()) as AlipayProcessResult.NeedsReview
        assertNotEquals(notification.entry!!.id, page.entry!!.id)
        assertTrue(page.codes.contains(AlipayDiagnosticCode.POSSIBLE_DUPLICATE))
        assertEquals(0, repo.observeEntries().first().count { it.state == EntryState.CONFIRMED })
    }

    @Test fun userCorrectionIsNeverOverwrittenByPageReplay() = runBlocking {
        val original = (processor.process(evidence()) as AlipayProcessResult.Saved).entry
        val edited = original.copy(merchant = "更正商户", category = "餐饮", amountMinor = 2000L)
        assertTrue(repo.save(edited) is SaveResult.Saved)
        assertEquals(edited, (processor.process(evidence("another-page-event")) as AlipayProcessResult.Duplicate).entry)
    }

    @Test fun missingAmountAndChatNeverProduceZeroAmountRows() = runBlocking {
        val noAmount = processor.process(evidence().copy(textLines = evidence().textLines.filterNot { it.startsWith("付款金额") }))
            as AlipayProcessResult.NeedsReview
        assertNull(noAmount.entry)
        assertTrue(noAmount.codes.contains(AlipayDiagnosticCode.NO_AMOUNT))
        assertTrue(processor.process(evidence("chat").copy(textLines = evidence().textLines + "发送")) is AlipayProcessResult.Ignored)
        assertTrue(repo.observeEntries().first().isEmpty())
    }

    @Test fun ocrIdCannotMergeAndOcrNeverAutoConfirms() = runBlocking {
        val result = processor.process(evidence("ocr", AlipaySource.OCR)) as AlipayProcessResult.NeedsReview
        assertEquals(EntryState.PENDING, result.entry!!.state)
        assertTrue(result.codes.contains(AlipayDiagnosticCode.OCR_UNVERIFIED))
        val page = processor.process(evidence()) as AlipayProcessResult.NeedsReview
        assertNotEquals(result.entry!!.id, page.entry!!.id)
        assertTrue(page.codes.contains(AlipayDiagnosticCode.POSSIBLE_DUPLICATE))
    }
}
