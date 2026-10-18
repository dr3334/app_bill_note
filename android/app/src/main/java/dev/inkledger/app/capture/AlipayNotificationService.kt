package dev.inkledger.app.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.inkledger.core.alipay.AlipayEvidence
import dev.inkledger.core.alipay.AlipaySource

/**
 * Android NotificationListenerService adapter. Design reference: AutoAccounting
 * 34b38f82 NotificationService.kt (big-text fallback); implemented locally without
 * its JsAPI, raw logging, cross-app whitelist, notification-content dedup or hook code.
 */
class AlipayNotificationService : NotificationListenerService() {
    private val manager get() = AlipayCaptureManager.get(this)

    override fun onListenerConnected() { super.onListenerConnected(); manager.notificationConnection(true) }

    override fun onListenerDisconnected() {
        manager.notificationConnection(false)
        if (manager.canCapture(AlipaySource.NOTIFICATION)) {
            runCatching { requestRebind(ComponentName(this, AlipayNotificationService::class.java)) }
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() { manager.notificationConnection(false); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !manager.canCapture(AlipaySource.NOTIFICATION, sbn.packageName)) return
        manager.notificationEvent()
        try {
            val notification = sbn.notification
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
                manager.record("NOTIFICATION", "GROUP_SUMMARY", "已跳过通知分组摘要，避免重复统计")
                return
            }
            val evidence = extractEvidence(sbn)
            if (evidence == null) { manager.record("NOTIFICATION", "UNSUPPORTED_NOTIFICATION", "通知没有付款正文，或是微信聊天通知，已忽略"); return }
            manager.submit(evidence)
        } catch (_: Exception) { manager.record("NOTIFICATION", "READ_FAILED", "支付通知字段读取失败") }
    }

    companion object {
        /** Exposed internally for synthetic Android Notification tests; never posts or clicks it. */
        internal fun extractEvidence(sbn: StatusBarNotification): AlipayEvidence? {
            if (sbn.packageName !in PAYMENT_PACKAGES || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            val notification = sbn.notification
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(1_000)
            if (sbn.packageName == WECHAT_PACKAGE) {
                // A contact can be named "微信支付". Reject explicit conversation metadata
                // before reading its body; an otherwise matching notification is still
                // unverified in the core parser and can never auto-confirm an expense.
                if (title.trim().trimEnd(':', '：') != "微信支付" || isConversation(notification)) return null
            }
            val body = listOf(Notification.EXTRA_BIG_TEXT, Notification.EXTRA_TEXT)
                .firstNotNullOfOrNull { key -> extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() } }
                .orEmpty().take(12_000)
            if (sbn.packageName == WECHAT_PACKAGE && body.isBlank()) return null
            val lines = listOf(title, body).filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            val captureId = stableCaptureId("notification|${sbn.key}|${sbn.postTime}|${notification.`when`}|$title|$body")
            return AlipayEvidence(sbn.packageName, AlipaySource.NOTIFICATION, lines,
                System.currentTimeMillis(), captureId, stableCaptureId("notification-session|${sbn.key}"))
        }

        private fun isConversation(notification: Notification): Boolean {
            val extras = notification.extras
            return extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name ||
                extras.containsKey(Notification.EXTRA_MESSAGES) ||
                extras.containsKey(Notification.EXTRA_HISTORIC_MESSAGES) ||
                extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE) ||
                extras.containsKey(Notification.EXTRA_MESSAGING_PERSON) ||
                notification.bubbleMetadata != null ||
                notification.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
        }
    }
}
