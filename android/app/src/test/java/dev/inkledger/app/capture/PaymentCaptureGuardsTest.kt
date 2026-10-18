package dev.inkledger.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentCaptureGuardsTest {
    @Test fun editableChatCannotBecomeAReceiptByQuotingPaymentFields() {
        assertTrue(isWechatChatWindow(listOf("支付成功", "付款金额：10.00", "商户名称：合成商户"), hasEditable = true))
    }

    @Test fun voiceChatAndInboxAreExcludedEvenWithoutEditableNodes() {
        assertTrue(isWechatChatWindow(listOf("微信支付", "支付成功", "按住 说话"), hasEditable = false))
        assertTrue(isWechatChatWindow(listOf("微信", "通讯录", "发现", "我", "付款成功"), hasEditable = false))
    }

    @Test fun receiptFieldsAndCanvasOnlyScreensRemainAvailableForReceiptValidation() {
        assertFalse(isWechatChatWindow(listOf("支付成功", "付款金额", "商户全称", "支付方式"), hasEditable = false))
        assertFalse(isWechatChatWindow(emptyList(), hasEditable = false))
    }
}
