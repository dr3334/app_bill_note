package dev.inkledger.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AlipayOcrReadingOrderTest {
    @Test fun mainAmountPrecedesMerchantPriceAndPromotionEvenWhenBlocksAreReversed() {
        val lines = listOf(
            AlipayOcrLine("送你0.5元红包", 1100, 400),
            AlipayOcrLine("¥8.80", 630, 1000),
            AlipayOcrLine("-¥0.20", 700, 1000),
            AlipayOcrLine("碰一下立减", 700, 60),
            AlipayOcrLine("示例商户", 630, 60),
            AlipayOcrLine("¥8.60", 400, 450),
            AlipayOcrLine("支付成功", 280, 500),
        )
        assertEquals(listOf("支付成功", "¥8.60", "示例商户", "¥8.80", "碰一下立减", "-¥0.20", "送你0.5元红包"),
            alipayOcrReadingOrder(lines))
    }

    @Test fun splitCurrencyAndAmountAreKeptAsSeparateLines() {
        assertEquals(listOf("支付成功", "¥", "8.60"), alipayOcrReadingOrder(listOf(
            AlipayOcrLine("8.60", 400, 450), AlipayOcrLine("¥", 400, 380),
            AlipayOcrLine("支付成功", 280, 500),
        )))
    }

    @Test fun linesWithoutBoundsStayStableAfterPositionedLines() {
        assertEquals(listOf("支付成功", "未知位置一", "未知位置二"), alipayOcrReadingOrder(listOf(
            AlipayOcrLine("未知位置一", null, null), AlipayOcrLine("支付成功", 280, 500),
            AlipayOcrLine("未知位置二", null, null),
        )))
    }
}
