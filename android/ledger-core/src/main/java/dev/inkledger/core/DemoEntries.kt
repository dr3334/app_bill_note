package dev.inkledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** All records are synthetic. Stable ids ensure retries never create new demo rows. */
internal object DemoEntries {
    fun create(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<LedgerEntry> {
        val today = now.atZone(zone).toLocalDate()
        fun date(daysAgo: Long): LocalDate = today.minusDays(daysAgo)
            .takeIf { it.month == today.month && it.year == today.year } ?: today.withDayOfMonth(1)
        fun entry(
            key: String, merchant: String, amount: Long, category: String,
            channel: PaymentChannel, daysAgo: Long, hour: Int,
            state: EntryState = EntryState.CONFIRMED,
            kind: EntryKind = EntryKind.EXPENSE,
        ) = LedgerEntry(
            id = "demo-v1-$key", merchant = merchant, amountMinor = amount, category = category,
            channel = channel, occurredAt = date(daysAgo).atTime(hour, 20).atZone(zone).toInstant().toEpochMilli(),
            state = state, evidenceId = "demo:v1:$key", kind = kind,
            accountLabel = when (channel) {
                PaymentChannel.WECHAT -> "微信零钱（示例）"
                PaymentChannel.ALIPAY -> "支付宝余额（示例）"
                PaymentChannel.MANUAL -> "现金（示例）"
            },
        )
        return listOf(
            entry("lunch", "街角食堂", 2800, "餐饮", PaymentChannel.WECHAT, 0, 12),
            entry("coffee", "日常咖啡", 1800, "餐饮", PaymentChannel.ALIPAY, 0, 9),
            entry("metro", "地铁出行", 400, "交通", PaymentChannel.ALIPAY, 1, 8),
            entry("market", "社区超市", 8630, "购物", PaymentChannel.WECHAT, 1, 18),
            entry("review", "待核对消费", 5600, "其他", PaymentChannel.WECHAT, 2, 14, EntryState.PENDING),
            entry("books", "城市书店", 12800, "学习", PaymentChannel.ALIPAY, 3, 16),
            entry("refund", "城市书店退款", 2000, "学习", PaymentChannel.ALIPAY, 2, 10, kind = EntryKind.REFUND),
            entry("income", "兼职收入", 150000, "收入", PaymentChannel.MANUAL, 4, 15, kind = EntryKind.INCOME),
            entry("transfer", "账户间转账", 50000, "转账", PaymentChannel.MANUAL, 3, 11, kind = EntryKind.TRANSFER),
        )
    }
}
