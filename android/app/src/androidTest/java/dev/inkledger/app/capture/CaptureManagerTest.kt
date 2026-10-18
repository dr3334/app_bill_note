package dev.inkledger.app.capture

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkledger.core.EntryState
import dev.inkledger.core.LedgerEntry
import dev.inkledger.core.PaymentChannel
import dev.inkledger.core.alipay.AlipayDiagnosticCode
import dev.inkledger.core.alipay.AlipayEvidence
import dev.inkledger.core.alipay.AlipayProcessResult
import dev.inkledger.core.alipay.AlipaySource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic adapter/consent tests; never grant OS permissions or bind payment services. */
@RunWith(AndroidJUnit4::class)
class CaptureManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var manager: AlipayCaptureManager
    private lateinit var preferenceName: String
    private val started = AtomicInteger()
    private val simulatedWrites = AtomicInteger()

    @Before fun setUp() {
        preferenceName = "capture_test_${UUID.randomUUID()}"
        instrumentation.runOnMainSync {
            manager = AlipayCaptureManager(context, {
                started.incrementAndGet()
                delay(200)
                simulatedWrites.incrementAndGet()
                AlipayProcessResult.Ignored(AlipayDiagnosticCode.NO_SUCCESS.message, AlipayDiagnosticCode.NO_SUCCESS)
            }, preferenceName)
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { manager.close() }
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun sample(packageName: String = ALIPAY_PACKAGE) = AlipayEvidence(
        packageName, AlipaySource.NOTIFICATION,
        listOf("synthetic-private-merchant", "payment-secret-amount-123.45"),
        System.currentTimeMillis(), "synthetic-capture-id",
    )

    @Test fun defaultsAreOffAndSwitchesRequireConsent() {
        instrumentation.runOnMainSync {
            assertEquals(CaptureSettings(), manager.state.value.settings)
            assertFalse(manager.setNotificationEnabled(true))
            assertFalse(manager.setAccessibilityEnabled(true))
            assertFalse(manager.setOcrEnabled(true))
            assertFalse(manager.setPaymentAppEnabled(WECHAT_PACKAGE, true))
            manager.submit(sample())
        }
        instrumentation.waitForIdleSync()
        assertEquals(0, started.get())
        assertEquals(CaptureSettings(), manager.state.value.settings)
    }

    @Test fun wechatMustBeExplicitlySelectedAndDisablingItCancelsInFlightWork() = runBlocking {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.submit(sample(WECHAT_PACKAGE))
            assertFalse(manager.canCapture(AlipaySource.NOTIFICATION, WECHAT_PACKAGE))
        }
        assertEquals(0, started.get())
        instrumentation.runOnMainSync {
            assertTrue(manager.setPaymentAppEnabled(WECHAT_PACKAGE, true))
            assertTrue(manager.canCapture(AlipaySource.NOTIFICATION, WECHAT_PACKAGE))
            manager.submit(sample(WECHAT_PACKAGE))
        }
        assertEquals(1, started.get())
        instrumentation.runOnMainSync { manager.setPaymentAppEnabled(WECHAT_PACKAGE, false) }
        delay(300)
        assertEquals(0, simulatedWrites.get())
        assertFalse(manager.state.value.settings.wechatEnabled)
    }

    @Test fun changedWindowCancelsPageWorkButKeepsBackgroundNotificationWork() = runBlocking {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.setAccessibilityEnabled(true)
            manager.submit(sample().copy(source = AlipaySource.ACCESSIBILITY))
            manager.cancelWindowProcessing()
            manager.submit(sample())
            manager.cancelWindowProcessing()
        }
        withTimeout(3_000) { while (simulatedWrites.get() != 1) delay(10) }
        assertEquals(2, started.get())
        assertEquals(1, simulatedWrites.get())
    }

    @Test fun revocationCancelsInFlightAndRejectsLaterInput() = runBlocking {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.submit(sample())
        }
        assertEquals(1, started.get())
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(false)
            manager.submit(sample())
        }
        delay(300)
        assertEquals(1, started.get())
        assertEquals(0, simulatedWrites.get())
        assertEquals(CaptureSettings(), manager.state.value.settings)
    }

    @Test fun nonAlipayEvidenceNeverReachesProcessor() {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.submit(sample("com.example.shopping"))
        }
        instrumentation.waitForIdleSync()
        assertEquals(0, started.get())
        assertEquals("UNTRUSTED_SOURCE", manager.state.value.recentLogs.first().code)
    }

    @Test fun diagnosticsOnlyExportStructuredRedactedResults() = runBlocking {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.submit(sample())
        }
        delay(300)
        lateinit var exported: String
        instrumentation.runOnMainSync { exported = manager.diagnosticsJson() }
        assertFalse(exported.contains("synthetic-private-merchant"))
        assertFalse(exported.contains("payment-secret-amount"))
        assertFalse(exported.contains("123.45"))
        assertFalse(exported.contains("synthetic-capture-id"))
        val json = JSONObject(exported)
        val logs = json.getJSONArray("logs")
        assertTrue(logs.length() > 0)
        assertEquals("NO_SUCCESS", logs.getJSONObject(0).getString("code"))
        assertTrue(json.getBoolean("notificationEnabled"))
    }

    @Test fun ocrRequiresSeparateConsentAndNoUnconnectedProbeRuns() {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setAccessibilityEnabled(true)
            assertFalse(manager.setOcrEnabled(true))
            manager.setOcrConsent(true)
            assertEquals(Build.VERSION.SDK_INT >= 34, manager.setOcrEnabled(true))
            assertFalse(manager.requestOcrProbe())
            assertNull(manager.state.value.probeSecondsRemaining)
            manager.setOcrConsent(false)
            assertFalse(manager.state.value.settings.ocrEnabled)
            assertFalse(manager.state.value.settings.screenshotConsentAccepted)
        }
    }

    @Test fun repeatedConsentAndUnchangedSwitchDoNotCancelNotificationProcessing() = runBlocking {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.submit(sample())
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
        }
        withTimeout(3_000) { while (simulatedWrites.get() != 1) delay(10) }
        assertEquals(1, started.get())
        assertTrue(manager.state.value.settings.notificationEnabled)
    }

    @Test fun revokingOcrConsentDoesNotCancelEnabledNotificationProcessing() = runBlocking {
        instrumentation.runOnMainSync {
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.setAccessibilityEnabled(true)
            manager.setOcrConsent(true)
            manager.setOcrEnabled(true)
            manager.submit(sample())
            manager.setOcrConsent(false)
        }
        withTimeout(3_000) { while (simulatedWrites.get() != 1) delay(10) }
        assertEquals(1, started.get())
        assertTrue(manager.state.value.settings.notificationEnabled)
        assertTrue(manager.state.value.settings.accessibilityEnabled)
        assertFalse(manager.state.value.settings.ocrEnabled)
        assertFalse(manager.state.value.settings.screenshotConsentAccepted)
    }

    @Test fun confirmedIdentityConflictDoesNotIncrementPendingOrClaimNewPendingEntry() {
        val confirmed = LedgerEntry("existing-confirmed", "合成测试商户", 1234, "其他",
            PaymentChannel.ALIPAY, System.currentTimeMillis(), EntryState.CONFIRMED, "synthetic-existing-evidence")
        val code = AlipayDiagnosticCode.STORED_IDENTITY_CONFLICT
        var response: AlipayProcessResult = AlipayProcessResult.NeedsReview(confirmed, listOf(code.message), listOf(code))
        instrumentation.runOnMainSync {
            manager.close()
            manager = AlipayCaptureManager(context, { response }, preferenceName)
            manager.setCaptureConsent(true)
            manager.setNotificationEnabled(true)
            manager.submit(sample())
        }
        instrumentation.waitForIdleSync()
        assertEquals(0L, manager.state.value.pendingCount)
        assertEquals(0L, manager.state.value.savedCount)
        assertEquals(confirmed.id, manager.state.value.lastResult?.entryId)
        assertEquals("已有账目信息冲突，请检查原记录与诊断", manager.state.value.lastResult?.title)
        assertEquals("STORED_IDENTITY_CONFLICT", manager.state.value.recentLogs.first().code)

        instrumentation.runOnMainSync {
            response = AlipayProcessResult.NeedsReview(confirmed.copy(id = "actual-pending", state = EntryState.PENDING),
                listOf(AlipayDiagnosticCode.OCR_UNVERIFIED.message), listOf(AlipayDiagnosticCode.OCR_UNVERIFIED))
            manager.submit(sample())
        }
        instrumentation.waitForIdleSync()
        assertEquals(1L, manager.state.value.pendingCount)
        assertEquals("有一笔待确认", manager.state.value.lastResult?.title)
    }
}
