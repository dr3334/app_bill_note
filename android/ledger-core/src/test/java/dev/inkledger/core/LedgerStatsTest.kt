package dev.inkledger.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val month = YearMonth.of(2026, 10)
    private fun entry(
        id: String, amount: Long, kind: EntryKind = EntryKind.EXPENSE,
        state: EntryState = EntryState.CONFIRMED,
        at: String = "2026-10-17T10:00:00Z", category: String = "餐饮",
    ) = LedgerEntry(id, "示例商户", amount, category, PaymentChannel.MANUAL,
        Instant.parse(at).toEpochMilli(), state, null, kind)

    @Test fun confirmedOnlyRefundAdjustedTotalsExcludeTransfers() {
        val result = LedgerStats.forMonth(listOf(
            entry("expense", 10000),
            entry("income", 20000, EntryKind.INCOME),
            entry("refund", 2500, EntryKind.REFUND),
            entry("pending-expense", 90000, state = EntryState.PENDING),
            entry("pending-income", 90000, EntryKind.INCOME, EntryState.PENDING),
            entry("pending-refund", 90000, EntryKind.REFUND, EntryState.PENDING),
            entry("transfer", 50000, EntryKind.TRANSFER),
        ), month, zone)
        assertEquals(20000L, result.incomeMinor)
        assertEquals(10000L, result.expenseMinor)
        assertEquals(2500L, result.refundMinor)
        assertEquals(7500L, result.netExpenseMinor)
        assertEquals(12500L, result.balanceMinor)
        assertEquals(4, result.confirmedCount)
        assertEquals(3, result.pendingCount)
        assertEquals(listOf(CategoryTotal("餐饮", 10000L, 1)), result.categoryTotals)
    }

    @Test fun monthUsesLocalHalfOpenBoundariesRatherThanUtcOrInclusiveEnd() {
        val result = LedgerStats.forMonth(listOf(
            entry("before", 1, at = "2026-09-30T15:59:59.999Z"),
            entry("start", 2, at = "2026-09-30T16:00:00Z"),
            entry("last", 3, at = "2026-10-31T15:59:59.999Z"),
            entry("next", 100, at = "2026-10-31T16:00:00Z"),
        ), month, zone)
        assertEquals(5L, result.expenseMinor)
        assertEquals(2, result.confirmedCount)
    }

    @Test fun calendarBoundariesHandleDaylightSavingMonths() {
        val dstZone = ZoneId.of("America/New_York")
        val result = LedgerStats.forMonth(listOf(
            entry("start", 2, at = "2026-03-01T05:00:00Z"),
            entry("last", 3, at = "2026-04-01T03:59:59.999Z"),
            entry("next", 100, at = "2026-04-01T04:00:00Z"),
        ), YearMonth.of(2026, 3), dstZone)
        assertEquals(5L, result.expenseMinor)
    }

    @Test fun refundsMayExceedThisMonthsExpenseWithoutClamping() {
        val result = LedgerStats.forMonth(listOf(entry("old-order-refund", 2500, EntryKind.REFUND)), month, zone)
        assertEquals(-2500L, result.netExpenseMinor)
        assertEquals(2500L, result.balanceMinor)
    }

    @Test(expected = ArithmeticException::class)
    fun totalsNeverWrapSilentlyOnOverflow() {
        LedgerStats.forMonth(listOf(entry("large", Long.MAX_VALUE), entry("extra", 1)), month, zone)
    }
}
