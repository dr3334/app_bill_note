package dev.inkledger.core.alipay

import dev.inkledger.core.Money
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Deliberately synthetic examples, not captures of a verified Alipay version. */
class AlipayReceiptParserTest {
    private val parser = AlipayReceiptParser(ZoneId.of("Asia/Shanghai"))
    private val now = Instant.parse("2026-10-18T04:00:00Z").toEpochMilli()
    private val receipt = listOf("付款成功", "实付金额：￥18.00", "商家：合成咖啡店")

    private fun evidence(lines: List<String> = receipt, source: AlipaySource = AlipaySource.NOTIFICATION) =
        AlipayEvidence(ALIPAY_PACKAGE_NAME, source, lines, now, "test-capture")
    private fun candidate(lines: List<String> = receipt, source: AlipaySource = AlipaySource.NOTIFICATION): AlipayPaymentCandidate =
        (parser.parse(evidence(lines, source)) as AlipayParseResult.Candidate).payment

    @Test fun packageAndEvidenceAreStrictlyChecked() {
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.UNTRUSTED_SOURCE),
            parser.parse(evidence().copy(packageName = "fake.alipay")))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.INVALID_EVIDENCE),
            parser.parse(evidence().copy(captureId = " ")))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.INPUT_TOO_LARGE),
            parser.parse(evidence(List(257) { "付款成功" })))
    }

    @Test fun explicitPaymentAmountWinsOverBalancePriceAndCoupons() {
        val result = candidate(receipt + listOf("原价", "￥20.00", "优惠券", "￥2.00", "余额：￥500.00"))
        assertEquals(1800L, result.amountMinor)
        assertEquals("合成咖啡店", result.merchant)
        assertTrue(result.reviewCodes.isEmpty())
    }

    @Test fun footerShortcutsDoNotTurnValidReceiptIntoMarketingOrRefund() {
        val result = candidate(receipt + listOf("交易时间：2026-10-18 12:00:00", "领取红包", "退款", "转账", "优惠券入口"),
            AlipaySource.ACCESSIBILITY)
        assertEquals(1800L, result.amountMinor)
        assertTrue(result.reviewCodes.isEmpty())
    }

    @Test fun paymentSuccessSentencesSupportPayerAndAmountWithoutInventedMerchant() {
        val named = candidate(listOf("你已向合成咖啡店成功付款18.00元"))
        assertEquals(1800L, named.amountMinor)
        assertEquals("合成咖啡店", named.merchant)
        assertTrue(named.reviewCodes.isEmpty())
        val unnamed = candidate(listOf("你已成功付款18.00元"))
        assertEquals(1800L, unnamed.amountMinor)
        assertEquals(listOf(AlipayDiagnosticCode.NO_MERCHANT), unnamed.reviewCodes)
        assertEquals(1800L, candidate(listOf("您已成功支付18.00元", "商家：合成咖啡店")).amountMinor)
    }

    @Test fun pureMarketingAndUnfinishedPaymentsNeverBecomeCandidates() {
        val cases = listOf(
            listOf("活动通知", "支付成功后可领取18元红包") to AlipayDiagnosticCode.MARKETING,
            listOf("优惠券到账", "付款成功", "付款金额：18.00元") to AlipayDiagnosticCode.MARKETING,
            listOf("支付成功后领取红包", "商家：合成咖啡店") to AlipayDiagnosticCode.MARKETING,
            listOf("待付款", "付款金额：18.00元") to AlipayDiagnosticCode.PAYMENT_NOT_COMPLETED,
            listOf("支付失败", "付款金额：18.00元") to AlipayDiagnosticCode.PAYMENT_NOT_COMPLETED,
            listOf("订单已创建", "付款金额：18.00元") to AlipayDiagnosticCode.NO_SUCCESS,
        )
        cases.forEach { (lines, code) -> assertEquals(lines.toString(), AlipayParseResult.Ignored(code), parser.parse(evidence(lines))) }
    }

    @Test fun transferRefundIncomeAndSplitTypeLabelsAreExcluded() {
        listOf("退款成功", "收款到账", "充值成功").forEach { heading ->
            assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND), parser.parse(evidence(listOf(heading) + receipt)))
        }
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.TRANSFER_NOT_EXPENSE),
            parser.parse(evidence(listOf("转账成功") + receipt)))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.TRANSFER_NOT_EXPENSE),
            parser.parse(evidence(receipt + listOf("交易类型", "转账"))))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.TRANSFER_NOT_EXPENSE),
            parser.parse(evidence(listOf("交易成功", "交易类型：转账-转出", "付款金额：10.00元", "收款方：测试对象"))))
    }

    @Test fun contradictoryActualAmountsAreReviewWithoutAnyLedgerAmount() {
        val result = candidate(receipt + "付款金额：19.00元")
        assertNull(result.amountMinor)
        assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.AMBIGUOUS_AMOUNT))
        assertEquals(1800L, candidate(receipt + "付款金额：18.00元").amountMinor)
    }

    @Test fun successWithoutAmountIsDiagnosableForOptionalOcrFallback() {
        val result = candidate(listOf("付款成功", "商家：合成咖啡店"), AlipaySource.ACCESSIBILITY)
        assertNull(result.amountMinor)
        assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.NO_AMOUNT))
    }

    @Test fun malformedNegativeZeroAndExcessiveAmountsDoNotPass() {
        listOf("-18.00元", "0.00元", "1.001元", "1,00.00元", "1000000000.00元", "123元奖励").forEach { amount ->
            val result = candidate(listOf("付款成功", "实付金额：$amount", "商家：合成咖啡店"))
            assertNull(amount, result.amountMinor)
            assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.INVALID_AMOUNT))
        }
        assertEquals(Money.MAX_AMOUNT_MINOR,
            candidate(listOf("付款成功", "实付金额：999,999,999.99元", "商家：合成咖啡店")).amountMinor)
    }

    @Test fun foreignCurrencyDoesNotBecomeCny() {
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_CURRENCY),
            parser.parse(evidence(listOf("付款成功", "实付金额：18.00美元", "商家：合成咖啡店"))))
    }

    @Test fun pageNeedsTransactionTimeAndOcrAlwaysNeedsReview() {
        assertTrue(candidate(source = AlipaySource.ACCESSIBILITY).reviewCodes.contains(AlipayDiagnosticCode.NO_TRANSACTION_TIME))
        val complete = receipt + listOf("交易时间：2026-10-18 12:00:00", "交易号：202610180000000001")
        assertTrue(candidate(complete, AlipaySource.ACCESSIBILITY).reviewCodes.isEmpty())
        assertEquals(listOf(AlipayDiagnosticCode.OCR_UNVERIFIED), candidate(complete, AlipaySource.OCR).reviewCodes)
    }

    @Test fun invalidHistoricalAndFutureTimesNeedReview() {
        assertTrue(candidate(receipt + "交易时间：2026-02-30 12:00:00").reviewCodes.contains(AlipayDiagnosticCode.NO_TRANSACTION_TIME))
        listOf("2026-10-16 12:00:00", "2026-10-18 12:06:00").forEach {
            assertTrue(candidate(receipt + "交易时间：$it").reviewCodes.contains(AlipayDiagnosticCode.HISTORICAL_OR_FUTURE))
        }
    }

    @Test fun merchantOrderIsNotAProviderTransactionIdentityAndConflictsNeedReview() {
        assertNull(candidate(receipt + "商户订单号：202610180000000001").transactionId)
        assertTrue(candidate(receipt + listOf("交易号：202610180000000001", "交易号：invalid"))
            .reviewCodes.contains(AlipayDiagnosticCode.AMBIGUOUS_IDENTITY))
        assertTrue(candidate(receipt + "商家名称：另一个合成商户").reviewCodes.contains(AlipayDiagnosticCode.AMBIGUOUS_IDENTITY))
        val missingMerchant = candidate(listOf("付款成功", "付款金额：18.00元", "商家", "交易时间：2026-10-18 12:00:00"))
        assertNull(missingMerchant.merchant)
        assertTrue(missingMerchant.reviewCodes.contains(AlipayDiagnosticCode.NO_MERCHANT))
    }

    @Test fun isolatedCurrencyUnderSuccessCanSupplyAmountButBalanceCannot() {
        assertEquals(1800L, candidate(listOf("付款成功", "￥18.00", "商家：合成咖啡店")).amountMinor)
        assertNull(candidate(listOf("付款成功", "余额", "￥500.00", "商家：合成咖啡店")).amountMinor)
    }

    @Test fun observedSyntheticMlKitAmountLabelVariantIsOcrOnlyAndBareOcrNumbersNeedLabel() {
        // Exact line output from the emulator Canvas fixture; not a real Alipay receipt.
        val lines = listOf("采集测试夹具·非支付宝", "付款成功", "实付金額", "23.45",
            "商家:台成測试商家", "交易号:202610180000000003", "交易时间:2026-10-17 18:33:05",
            "本页仅生成台成数据,不执行真实支付")
        val result = candidate(lines, AlipaySource.OCR)
        assertEquals(2345L, result.amountMinor)
        assertEquals("台成測试商家", result.merchant)
        assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.OCR_UNVERIFIED))
        assertFalse(result.reviewCodes.contains(AlipayDiagnosticCode.NO_AMOUNT))
        assertNull(candidate(lines, AlipaySource.NOTIFICATION).amountMinor)
        val unlabeled = candidate(lines.filterNot { it == "实付金額" }, AlipaySource.OCR)
        assertNull(unlabeled.amountMinor)
        assertTrue(unlabeled.reviewCodes.contains(AlipayDiagnosticCode.NO_AMOUNT))
    }
}
