package dev.inkledger.core.alipay

import dev.inkledger.core.EvidenceIds
import org.junit.Assert.*
import org.junit.Test

class PaymentEvidenceIdentityTest {
    private val evidence = AlipayEvidence(ALIPAY_PACKAGE_NAME, AlipaySource.ACCESSIBILITY,
        listOf("付款成功", "付款金额：18.00元", "商家：合成咖啡店"), 1L, "event", "session")

    @Test fun alipayKeepsEveryLegacyJournalKey() {
        val keys = PaymentEvidenceIdentity.from(evidence)
        fun legacy(kind: String, value: String) = EvidenceIds.fromBytes("alipay-$kind", value.toByteArray(Charsets.UTF_8))
        val payload = legacy("payload", "付款成功\n付款金额:18.00元\n商家:合成咖啡店")
        val capture = legacy("capture", "ACCESSIBILITY\u0000event")
        assertEquals(payload, keys.payload)
        assertEquals(capture, keys.capture)
        assertEquals(legacy("event", "$capture\u0000$payload"), keys.event)
        assertEquals(legacy("session", "ACCESSIBILITY\u0000session"), keys.session)
        assertEquals(legacy("transaction", "202610180000000001"),
            PaymentEvidenceIdentity.transactionKey(evidence, "202610180000000001"))
        assertEquals(legacy("merchant", "合成咖啡店"), PaymentEvidenceIdentity.merchantKey(evidence, "合成 咖啡店"))
    }

    @Test fun identicalEvidenceInDifferentPaymentAppsHasSeparateIdentities() {
        val wechat = evidence.copy(packageName = WECHAT_PACKAGE_NAME)
        val alipayKeys = PaymentEvidenceIdentity.from(evidence)
        val wechatKeys = PaymentEvidenceIdentity.from(wechat)
        assertNotEquals(alipayKeys.event, wechatKeys.event)
        assertNotEquals(alipayKeys.capture, wechatKeys.capture)
        assertNotEquals(alipayKeys.session, wechatKeys.session)
        assertNotEquals(alipayKeys.payload, wechatKeys.payload)
        assertNotEquals(PaymentEvidenceIdentity.transactionKey(evidence, "202610180000000001"),
            PaymentEvidenceIdentity.transactionKey(wechat, "202610180000000001"))
        assertNotEquals(PaymentEvidenceIdentity.merchantKey(evidence, "同一商户"),
            PaymentEvidenceIdentity.merchantKey(wechat, "同一商户"))
    }

    @Test fun ocrAndWechatNotificationCannotClaimStrongTransactionIdentity() {
        assertNull(PaymentEvidenceIdentity.transactionKey(evidence.copy(source = AlipaySource.OCR), "202610180000000001"))
        assertNull(PaymentEvidenceIdentity.transactionKey(evidence.copy(packageName = WECHAT_PACKAGE_NAME,
            source = AlipaySource.NOTIFICATION), "202610180000000001"))
        assertNotNull(PaymentEvidenceIdentity.transactionKey(evidence.copy(source = AlipaySource.NOTIFICATION), "202610180000000001"))
    }

    @Test fun retryUsesSameKeyButChangedPayloadAndNewCaptureRemainDistinct() {
        val first = PaymentEvidenceIdentity.from(evidence)
        assertEquals(first, PaymentEvidenceIdentity.from(evidence.copy(observedAt = 2L)))
        val edited = PaymentEvidenceIdentity.from(evidence.copy(textLines = evidence.textLines + "其他字段"))
        assertEquals(first.capture, edited.capture)
        assertNotEquals(first.event, edited.event)
        val another = PaymentEvidenceIdentity.from(evidence.copy(captureId = "next-event"))
        assertEquals(first.payload, another.payload)
        assertNotEquals(first.event, another.event)
    }
}
