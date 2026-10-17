/*
 * Copyright (C) 2024 ankio(ankio@ankio.net)
 * Modified 2026-10-18 for Ink Ledger.
 * Refund subtraction/category grouping adapted from AutoAccounting
 * BillInfoDao.kt, commit 34b38f82ca9ad6d7905c715c66891977f8809c88.
 * GPL v3 repository-level terms; see LICENSE and UPSTREAM.md.
 */
package dev.inkledger.core

import java.time.YearMonth
import java.time.ZoneId

data class CategoryTotal(val category: String, val amountMinor: Long, val count: Int = 0)

data class MonthlyStats(
    val incomeMinor: Long,
    /** Gross confirmed expense, before refunds. */
    val expenseMinor: Long,
    val refundMinor: Long,
    val netExpenseMinor: Long,
    /** Income - expense + refunds. Transfers are excluded. Not an account balance. */
    val balanceMinor: Long,
    val confirmedCount: Int,
    val pendingCount: Int,
    /** Gross expense by category; refunds are disclosed separately. */
    val categoryTotals: List<CategoryTotal>,
)

object LedgerStats {
    /** Uses a half-open local month [start, next start), including DST changes.
     * ArithmeticException signals totals outside Long range; never silently wraps.
     */
    fun forMonth(entries: List<LedgerEntry>, month: YearMonth, zoneId: ZoneId): MonthlyStats {
        val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val monthly = entries.filter { it.occurredAt >= start && it.occurredAt < end }
        val confirmed = monthly.filter { it.state == EntryState.CONFIRMED }
        fun sum(kind: EntryKind): Long = confirmed.asSequence()
            .filter { it.kind == kind }
            .fold(0L) { total, entry -> Math.addExact(total, entry.amountMinor) }
        val income = sum(EntryKind.INCOME)
        val expense = sum(EntryKind.EXPENSE)
        val refunds = sum(EntryKind.REFUND)
        val netExpense = Math.subtractExact(expense, refunds)
        val categories = confirmed.filter { it.kind == EntryKind.EXPENSE }
            .groupBy { it.category }
            .map { (category, bills) ->
                CategoryTotal(category, bills.fold(0L) { total, bill ->
                    Math.addExact(total, bill.amountMinor)
                }, bills.size)
            }.sortedWith(compareByDescending<CategoryTotal> { it.amountMinor }.thenBy { it.category })
        return MonthlyStats(
            income, expense, refunds, netExpense, Math.subtractExact(income, netExpense),
            confirmed.size, monthly.count { it.state == EntryState.PENDING }, categories,
        )
    }
}
