package dev.inkledger.app.capture

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkledger.core.alipay.AlipaySource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Notification objects, never NotificationManager.notify or live notifications. */
@RunWith(AndroidJUnit4::class)
class NotificationAdapterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun notification(bigText: String? = null, summary: Boolean = false, title: String = "合成测试标题"): Notification {
        val builder = Notification.Builder(context, "synthetic-test-channel")
            .setContentTitle(title).setContentText("合成普通正文").setWhen(1_000L)
        if (bigText != null) builder.setStyle(Notification.BigTextStyle().bigText(bigText))
        if (summary) builder.setGroup("synthetic-group").setGroupSummary(true)
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun posted(notification: Notification, packageName: String = ALIPAY_PACKAGE, at: Long = 2_000L) =
        StatusBarNotification(packageName, packageName, 7, "synthetic-tag", Process.myUid(),
            Process.myPid(), 0, notification, Process.myUserHandle(), at)

    @Test fun bigTextIsPreferredAndPlainTextIsTheFallback() {
        val big = AlipayNotificationService.extractEvidence(posted(notification("合成长正文")))!!
        assertEquals(AlipaySource.NOTIFICATION, big.source)
        assertEquals(listOf("合成测试标题", "合成长正文"), big.textLines)
        val normal = AlipayNotificationService.extractEvidence(posted(notification()))!!
        assertEquals(listOf("合成测试标题", "合成普通正文"), normal.textLines)
    }

    @Test fun otherPackagesAndGroupSummariesAreIgnored() {
        assertNull(AlipayNotificationService.extractEvidence(posted(notification(), "com.example.shopping")))
        assertNull(AlipayNotificationService.extractEvidence(posted(notification(summary = true))))
    }

    @Test fun wechatPaymentTitleIsRequiredAndExplicitChatsAreRejected() {
        assertNull(AlipayNotificationService.extractEvidence(posted(notification(title = "合成联系人"), WECHAT_PACKAGE)))
        val payment = notification("支付成功\n付款金额：12.34 元\n商户名称：合成商户", title = "微信支付")
        assertEquals(WECHAT_PACKAGE, AlipayNotificationService.extractEvidence(posted(payment, WECHAT_PACKAGE))?.packageName)

        val chat = Notification.Builder(context, "synthetic-test-channel")
            .setContentTitle("微信支付")
            .setStyle(Notification.MessagingStyle(Person.Builder().setName("本人").build())
                .addMessage("支付成功\n付款金额：12.34 元", 1_000L, Person.Builder().setName("微信支付").build()))
            .build()
        // A contact may choose the exact same name as the payment account.
        chat.extras.putCharSequence(Notification.EXTRA_TITLE, "微信支付")
        assertNull(AlipayNotificationService.extractEvidence(posted(chat, WECHAT_PACKAGE)))

        val conversation = notification("支付成功", title = "微信支付")
        conversation.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "合成群聊")
        assertNull(AlipayNotificationService.extractEvidence(posted(conversation, WECHAT_PACKAGE)))
    }

    @Test fun emptyBigTextFallsBackToPlainBodyButWechatTitleAloneIsNotEvidence() {
        val blankBig = notification("  ", title = "微信支付")
        assertEquals(listOf("微信支付", "合成普通正文"),
            AlipayNotificationService.extractEvidence(posted(blankBig, WECHAT_PACKAGE))?.textLines)
        val titleOnly = notification(title = "微信支付")
        titleOnly.extras.remove(Notification.EXTRA_TEXT)
        assertNull(AlipayNotificationService.extractEvidence(posted(titleOnly, WECHAT_PACKAGE)))
        // Preserve the existing Alipay adapter contract: its title may itself be the receipt.
        val alipayTitleOnly = notification(title = "付款成功 ¥12.34 合成商户")
        alipayTitleOnly.extras.remove(Notification.EXTRA_TEXT)
        assertEquals(listOf("付款成功 ¥12.34 合成商户"),
            AlipayNotificationService.extractEvidence(posted(alipayTitleOnly, ALIPAY_PACKAGE))?.textLines)
    }

    @Test fun repeatedCallbackHasStableIdentityButUpdatesShareOnlyWeakSession() {
        val first = posted(notification())
        val repeated = AlipayNotificationService.extractEvidence(first)!!
        assertEquals(repeated.captureId, AlipayNotificationService.extractEvidence(first)!!.captureId)
        val updated = AlipayNotificationService.extractEvidence(posted(notification("更新正文"), at = 3_000L))!!
        assertNotEquals(repeated.captureId, updated.captureId)
        assertEquals(repeated.sessionId, updated.sessionId)
        assertFalse(updated.captureId.contains("更新正文"))
    }
}
