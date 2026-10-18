package dev.inkledger.core.alipay

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Synthetic structural fixtures only; real device/WeChat-version acceptance is separate. */
class WechatReceiptParserTest {
    private val parser = WechatReceiptParser(ZoneId.of("Asia/Shanghai"))
    private val now = Instant.parse("2026-10-18T04:00:00Z").toEpochMilli()
    private val detail = listOf("账单详情", "合成咖啡店", "-18.00", "当前状态", "支付成功",
        "商户全称", "合成咖啡店", "支付时间", "2026-10-18 12:00:00", "支付方式", "零钱",
        "微信支付单号", "4200000000202610180000000001")

    private fun evidence(lines: List<String> = detail, source: AlipaySource = AlipaySource.ACCESSIBILITY) =
        AlipayEvidence(WECHAT_PACKAGE_NAME, source, lines, now, "wechat-page")
    private fun candidate(lines: List<String> = detail, source: AlipaySource = AlipaySource.ACCESSIBILITY) =
        (parser.parse(evidence(lines, source)) as AlipayParseResult.Candidate).payment

    @Test fun splitNodeBillDetailProvidesExpenseMerchantTimeAndProviderId() {
        val result = candidate()
        assertEquals(1800L, result.amountMinor)
        assertEquals("合成咖啡店", result.merchant)
        assertEquals(now, result.transactionTime)
        assertEquals("4200000000202610180000000001", result.transactionId)
        assertTrue(result.reviewCodes.isEmpty())
    }

    @Test fun inlineBillDetailSupportsFullWidthColonAndUnicodeExpenseSign() {
        val result = candidate(listOf("账单详情", "−￥18.00", "当前状态：已支付", "商户名称：合成咖啡店",
            "支付时间：2026年10月18日 12:00:00", "交易单号：4200000000202610180000000001"))
        assertEquals(1800L, result.amountMinor)
        assertEquals(now, result.transactionTime)
        assertTrue(result.reviewCodes.isEmpty())
    }

    @Test fun successPageJoinsCurrencyNodesAndDoesNotInventMissingTime() {
        val result = candidate(listOf("微信支付", "支付成功", "￥", "18.00", "收款商家", "合成咖啡店", "付款方式", "零钱"))
        assertEquals(1800L, result.amountMinor)
        assertEquals("合成咖啡店", result.merchant)
        assertNull(result.transactionTime)
        assertEquals(listOf(AlipayDiagnosticCode.NO_TRANSACTION_TIME), result.reviewCodes)
    }

    @Test fun actualAmountBeatsOrderPriceDiscountAndBalance() {
        val result = candidate(detail + listOf("实付金额：18.00元", "订单金额", "￥20.00", "优惠", "￥2.00", "余额", "￥500.00", "奖励", "+0.50"))
        assertEquals(1800L, result.amountMinor)
        assertTrue(result.reviewCodes.isEmpty())
    }

    @Test fun labeledSignedExpenseAlsoKeepsExactMinorUnits() {
        val result = candidate(detail.filterNot { it == "-18.00" } + "付款金额：-￥1,234.56元")
        assertEquals(123456L, result.amountMinor)
    }

    @Test fun missingAmountRemainsDiagnosableForOcrFallback() {
        val result = candidate(detail.filterNot { it == "-18.00" })
        assertNull(result.amountMinor)
        assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.NO_AMOUNT))
    }

    @Test fun allOcrReceiptsRemainPendingEvenWhenAllFieldsArePresent() {
        assertEquals(listOf(AlipayDiagnosticCode.OCR_UNVERIFIED), candidate(source = AlipaySource.OCR).reviewCodes)
    }

    @Test fun wechatNotificationNeverAutoConfirmsAndMissingTimeIsExplicit() {
        val complete = candidate(listOf("微信支付", "支付成功", "付款金额：18.00元", "收款方：合成咖啡店",
            "支付时间：2026-10-18 12:00:00"), AlipaySource.NOTIFICATION)
        assertEquals(1800L, complete.amountMinor)
        assertEquals(listOf(AlipayDiagnosticCode.WECHAT_NOTIFICATION_UNVERIFIED), complete.reviewCodes)
        val incomplete = candidate(listOf("微信支付", "您已向合成咖啡店成功付款18.00元"), AlipaySource.NOTIFICATION)
        assertTrue(incomplete.reviewCodes.contains(AlipayDiagnosticCode.WECHAT_NOTIFICATION_UNVERIFIED))
        assertTrue(incomplete.reviewCodes.contains(AlipayDiagnosticCode.NO_TRANSACTION_TIME))
    }

    @Test fun notificationWithOrdinaryChatTitleIsNotAPayment() {
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.NOT_PAYMENT_RECEIPT),
            parser.parse(evidence(listOf("朋友", "支付成功", "付款金额：18.00元", "商家：合成咖啡店"), AlipaySource.NOTIFICATION)))
    }

    @Test fun chatControlsAndBareCopiedSuccessAreIgnored() {
        listOf("发送", "按住 说话", "聊天信息", "撤回消息").forEach { control ->
            assertEquals(control, AlipayParseResult.Ignored(AlipayDiagnosticCode.NOT_PAYMENT_RECEIPT),
                parser.parse(evidence(detail + control)))
        }
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.NOT_PAYMENT_RECEIPT),
            parser.parse(evidence(listOf("支付成功", "￥18.00"))))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.NOT_PAYMENT_RECEIPT),
            parser.parse(evidence(listOf("支付成功", "付款金额：18.00元", "支付金额：18.00元"))))
    }

    @Test fun transfersRedPacketsIncomeAndRefundStatesAreExcluded() {
        listOf("微信转账", "转账给张三", "微信红包：红包详情", "微信红包", "转账成功", "收款成功", "已收钱", "已退款").forEach { heading ->
            assertEquals(heading, AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND),
                parser.parse(evidence(listOf(heading) + detail)))
        }
        listOf("转账-转出", "红包-发出", "退款", "已全额退款", "已部分退款", "零钱提现").forEach { value ->
            assertEquals(value, AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND),
                parser.parse(evidence(detail + listOf("业务类型", value))))
        }
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND),
            parser.parse(evidence(detail + "当前状态：已退款")))
    }

    @Test fun refundActionFooterDoesNotMisclassifySuccessfulPurchase() {
        val result = candidate(detail + listOf("申请退款", "对订单有疑问", "转账", "领取优惠券"))
        assertEquals(1800L, result.amountMinor)
        assertTrue(result.reviewCodes.isEmpty())
    }

    @Test fun successPageFooterAlsoPreservesUnlabeledAmountAndOcrFallback() {
        val page = listOf("微信支付", "支付成功", "￥", "18.00", "收款商家", "合成咖啡店", "付款方式", "零钱",
            "申请退款", "转账", "领取优惠券")
        assertEquals(1800L, candidate(page).amountMinor)
        val withoutAmount = candidate(page.filterNot { it == "￥" || it == "18.00" })
        assertNull(withoutAmount.amountMinor)
        assertTrue(withoutAmount.reviewCodes.contains(AlipayDiagnosticCode.NO_AMOUNT))
    }

    @Test fun footerAllowanceDoesNotPermitMarketingHeadingsOrTransfers() {
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.MARKETING),
            parser.parse(evidence(listOf("活动通知") + detail + "领取优惠券")))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND),
            parser.parse(evidence(detail + listOf("交易类型", "转账", "领取优惠券"))))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.NO_SUCCESS),
            parser.parse(evidence(detail.map { if (it == "支付成功") "支付成功后领取优惠券" else it })))
    }

    @Test fun incomingPositiveAmountDoesNotBecomeExpense() {
        listOf("+18.00", "+￥18.00", "￥+18.00", "付款金额：+18.00元").forEach { amount ->
            assertEquals(amount, AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND),
                parser.parse(evidence(detail.map { if (it == "-18.00") amount else it })))
        }
    }

    @Test fun unfinishedPaymentsHaveNoLedgerCandidate() {
        listOf("待支付", "支付失败", "支付处理中", "交易关闭").forEach { state ->
            assertEquals(state, AlipayParseResult.Ignored(AlipayDiagnosticCode.PAYMENT_NOT_COMPLETED),
                parser.parse(evidence(detail.map { if (it == "支付成功") state else it })))
        }
    }

    @Test fun ambiguousInvalidAndForeignAmountsDoNotProduceAnExpenseValue() {
        val ambiguous = candidate(detail + listOf("付款金额：18.00元", "实付金额：19.00元"))
        assertNull(ambiguous.amountMinor)
        assertTrue(ambiguous.reviewCodes.contains(AlipayDiagnosticCode.AMBIGUOUS_AMOUNT))
        listOf("0.00", "18.001", "1000000000.00", "1,00.00").forEach { invalid ->
            val result = candidate(detail + "付款金额：$invalid 元")
            assertNull(invalid, result.amountMinor)
            assertTrue(result.reviewCodes.contains(AlipayDiagnosticCode.INVALID_AMOUNT))
        }
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_CURRENCY),
            parser.parse(evidence(detail + "付款金额：18.00美元")))
    }

    @Test fun missingMerchantAndHistoricalOrInvalidTimeNeedReview() {
        val noMerchant = candidate(detail.filterNot { it == "商户全称" || it == "合成咖啡店" })
        assertNull(noMerchant.merchant)
        assertTrue(noMerchant.reviewCodes.contains(AlipayDiagnosticCode.NO_MERCHANT))
        val historical = candidate(detail.map { if (it == "2026-10-18 12:00:00") "2026-10-16 12:00:00" else it })
        assertTrue(historical.reviewCodes.contains(AlipayDiagnosticCode.HISTORICAL_OR_FUTURE))
        val invalid = candidate(detail.map { if (it == "2026-10-18 12:00:00") "2026-02-30 12:00:00" else it })
        assertNull(invalid.transactionTime)
        assertTrue(invalid.reviewCodes.contains(AlipayDiagnosticCode.NO_TRANSACTION_TIME))
    }

    @Test fun merchantOrderCannotActAsProviderIdAndConflictingIdentityNeedsReview() {
        assertNull(candidate(detail.filterNot { it == "微信支付单号" || it == "4200000000202610180000000001" } +
            "商户订单号：4200000000202610180000000001").transactionId)
        assertTrue(candidate(detail + "商家：另一个商户").reviewCodes.contains(AlipayDiagnosticCode.AMBIGUOUS_IDENTITY))
        assertTrue(candidate(detail + "交易单号：4200000000202610180000000002")
            .reviewCodes.contains(AlipayDiagnosticCode.AMBIGUOUS_IDENTITY))
    }

    @Test fun packageCaptureAndInputBoundsAreCheckedBeforeParsing() {
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.UNTRUSTED_SOURCE),
            parser.parse(evidence().copy(packageName = ALIPAY_PACKAGE_NAME)))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.INVALID_EVIDENCE),
            parser.parse(evidence().copy(captureId = " ")))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.INVALID_EVIDENCE),
            parser.parse(evidence().copy(observedAt = 0L)))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.INPUT_TOO_LARGE),
            parser.parse(evidence(List(257) { "x" })))
        assertEquals(AlipayParseResult.Ignored(AlipayDiagnosticCode.INPUT_TOO_LARGE),
            parser.parse(evidence(listOf("x".repeat(32_001)))))
    }
}
