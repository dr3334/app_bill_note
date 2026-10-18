package dev.inkledger.core.alipay

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * User screenshot layouts, manually transcribed and anonymized; no OCR/device execution.
 * Bill timestamps are synthetic, preserving the same-day and 20-day-old test intervals.
 */
class AlipayRealPageTranscriptTest {
    private val parser = AlipayReceiptParser(ZoneId.of("Asia/Shanghai"))
    private val observedAt = Instant.parse("2025-01-15T06:02:58Z").toEpochMilli()
    private val payment = fixture("payment-success-manual.txt")
    private val transfer = fixture("transfer-success-manual.txt")
    private val detail = fixture("bill-detail-manual.txt")

    private fun fixture(name: String): List<String> = checkNotNull(javaClass.getResourceAsStream("/alipay/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readLines() }
    private fun evidence(lines: List<String>, source: AlipaySource = AlipaySource.OCR) =
        AlipayEvidence(ALIPAY_PACKAGE_NAME, source, lines, observedAt, "manual-transcript")
    private fun candidate(lines: List<String>, source: AlipaySource = AlipaySource.OCR): AlipayPaymentCandidate =
        (parser.parse(evidence(lines, source)) as AlipayParseResult.Candidate).payment

    @Test fun paymentUses860Not880DiscountRewardsOrAdvertising() {
        val result = candidate(payment)
        assertEquals(860L, result.amountMinor)
        assertEquals("示例餐馆", result.merchant)
        assertNull(result.transactionTime)
        assertEquals(listOf(AlipayDiagnosticCode.NO_TRANSACTION_TIME, AlipayDiagnosticCode.OCR_UNVERIFIED), result.reviewCodes)
    }

    @Test fun accessibilitySuccessPageWithoutTimeRemainsPending() {
        val result = candidate(payment, AlipaySource.ACCESSIBILITY)
        assertEquals(860L, result.amountMinor)
        assertEquals(listOf(AlipayDiagnosticCode.NO_TRANSACTION_TIME), result.reviewCodes)
    }

    @Test fun paymentSupportsSplitCurrencyAndInlineMerchantPrice() {
        val split = payment.flatMap { if (it == "¥8.60") listOf("￥", "8.60") else listOf(it) }
        assertEquals(860L, candidate(split).amountMinor)
        val inlineMerchant = payment.filterNot { it == "¥8.80" }.map { if (it == "示例餐馆") "示例餐馆 ¥8.80" else it }
        assertEquals("示例餐馆", candidate(inlineMerchant).merchant)
        assertEquals(860L, candidate(inlineMerchant).amountMinor)
        assertEquals(860L, candidate(payment.map { if (it == "¥8.60") "8.60" else it }).amountMinor)
    }

    @Test fun missingPrimaryAmountNeverFallsBackToPriceDiscountOrAdvert() {
        val result = candidate(payment.filterNot { it == "¥8.60" })
        assertNull(result.amountMinor)
        assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.NO_AMOUNT))
        assertEquals("示例餐馆", result.merchant)
    }

    @Test fun explicitAmountInsideAdvertisingCannotRescueMissingPrimary() {
        val lines = payment.filterNot { it == "¥8.60" } + listOf("付款金额", "¥0.50")
        assertNull(candidate(lines).amountMinor)
        assertEquals(860L, candidate(payment + listOf("实付金额", "¥0.50")).amountMinor)
    }

    @Test fun conflictingActualReceiptAmountsNeedReview() {
        val lines = payment.toMutableList().apply { addAll(indexOf("交易方式"), listOf("实付金额", "¥9.60")) }
        val result = candidate(lines)
        assertNull(result.amountMinor)
        assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.AMBIGUOUS_AMOUNT))
    }

    @Test fun invalidPrimaryAmountNeverFallsBackToValidPrice() {
        listOf("¥0.00", "¥8.601", "¥1,00.00", "¥1000000000.00").forEach { amount ->
            val result = candidate(payment.map { if (it == "¥8.60") amount else it })
            assertNull(amount, result.amountMinor)
            assertTrue(amount, result.reviewCodes.contains(AlipayDiagnosticCode.INVALID_AMOUNT))
        }
    }

    @Test fun transferHasSpecificNonExpenseOutcomeEvenWithAdvertising() {
        AlipaySource.entries.forEach { source ->
            assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.TRANSFER_NOT_EXPENSE), parser.parse(evidence(transfer, source)))
        }
    }

    @Test fun billUsesExpenseMagnitudeMerchantAndPaymentTimeNotStatusClock() {
        val result = candidate(detail)
        assertEquals(850L, result.amountMinor)
        assertEquals("示例商店", result.merchant)
        assertEquals(Instant.parse("2025-01-15T02:20:30Z").toEpochMilli(), result.transactionTime)
        assertEquals(listOf(AlipayDiagnosticCode.OCR_UNVERIFIED), result.reviewCodes)
        assertTrue(candidate(detail, AlipaySource.ACCESSIBILITY).reviewCodes.isEmpty())
    }

    @Test fun billSupportsSplitExpenseSignCurrencyAndDateTime() {
        val split = detail.flatMap {
            when (it) {
                "-8.50" -> listOf("−", "￥", "8.50")
                "2025-01-15 10:20:30" -> listOf("2025-01-15", "10:20:30")
                else -> listOf(it)
            }
        }
        assertEquals(850L, candidate(split).amountMinor)
        assertEquals(Instant.parse("2025-01-15T02:20:30Z").toEpochMilli(), candidate(split).transactionTime)
    }

    @Test fun paymentProductDescriptionContainingCollectionDoesNotBecomeIncome() {
        assertEquals(850L, candidate(detail).amountMinor)
        assertEquals(850L, candidate(detail.map { if (it == "商品说明") "商品说明:收钱码收款" else it }
            .filterNot { it == "收钱码收款" }).amountMinor)
    }

    @Test fun incomeAndTransferDetailsNeverBecomeExpenses() {
        listOf("+8.50", "+￥8.50", "￥+8.50").forEach { amount ->
            assertEquals(amount, AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND),
                parser.parse(evidence(detail.map { if (it == "-8.50") amount else it })))
        }
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.TRANSFER_NOT_EXPENSE),
            parser.parse(evidence(detail + listOf("交易类型", "转账-转出"))))
    }

    @Test fun billMissingSignedPrimaryAmountDoesNotUseRewardOrOtherAmounts() {
        val noPrimary = detail.filterNot { it == "-8.50" }.toMutableList()
        noPrimary.addAll(noPrimary.indexOf("支付奖励") + 1, listOf("¥2.00", "+¥0.50"))
        val result = candidate(noPrimary)
        assertNull(result.amountMinor)
        assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.NO_AMOUNT))
        assertNull(candidate(detail.map { if (it == "-8.50") "8.50" else it }).amountMinor)
    }

    @Test fun oldBillKeepsItsPaymentTimeAndRequiresReview() {
        val old = parser.parse(evidence(detail).copy(observedAt = Instant.parse("2025-02-04T02:20:49Z").toEpochMilli()))
            as AlipayParseResult.Candidate
        assertEquals(Instant.parse("2025-01-15T02:20:30Z").toEpochMilli(), old.payment.transactionTime)
        assertTrue(old.payment.reviewCodes.contains(AlipayDiagnosticCode.HISTORICAL_OR_FUTURE))
    }

    @Test fun paymentAndBillRequireRealSuccessNotConditionalAdvertising() {
        val noSuccess = payment.map { if (it == "支付成功") "支付成功后领取红包" else it }
        assertTrue(parser.parse(evidence(noSuccess)) is AlipayParseResult.Ignored)
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.NO_SUCCESS),
            parser.parse(evidence(detail.map { if (it == "交易成功") "交易处理中" else it })))
    }
}
