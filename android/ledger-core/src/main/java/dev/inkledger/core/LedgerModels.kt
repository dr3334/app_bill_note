/*
 * Copyright (C) 2024 ankio(ankio@ankio.net)
 * Modified 2026-10-18 for Ink Ledger.
 * Adapted field concepts from AutoAccounting BillInfoModel and enum mapping
 * from BillType/BillState, commit 34b38f82ca9ad6d7905c715c66891977f8809c88.
 * Repository-level license: GPL v3. See LICENSE and UPSTREAM.md for the
 * original conflicting file notice; no permissive relicense is claimed.
 */
package dev.inkledger.core

import dev.inkledger.core.upstream.BillState
import dev.inkledger.core.upstream.BillType

/** An independent CNY demo-ledger model. Amounts are positive integer fen. */
data class LedgerEntry(
    val id: String,
    val merchant: String,
    val amountMinor: Long,
    val category: String,
    val channel: PaymentChannel,
    val occurredAt: Long,
    val state: EntryState,
    val evidenceId: String?,
    val kind: EntryKind = EntryKind.EXPENSE,
    val accountLabel: String? = null,
)

enum class PaymentChannel(val label: String) {
    WECHAT("微信"), ALIPAY("支付宝"), MANUAL("手动");
}

enum class EntryState(val label: String) {
    CONFIRMED("已确认"), PENDING("待确认");

    internal fun toStoredState(): BillState = when (this) {
        CONFIRMED -> BillState.Edited
        PENDING -> BillState.Wait2Edit
    }
}

enum class EntryKind(internal val storedType: BillType) {
    EXPENSE(BillType.Expend),
    INCOME(BillType.Income),
    TRANSFER(BillType.Transfer),
    REFUND(BillType.IncomeRefund);

    val label: String get() = storedType.toChineseString()
}

sealed interface SaveResult {
    data class Saved(val entry: LedgerEntry) : SaveResult
    /** Same stable evidence was already stored under another entry id. */
    data class Duplicate(val existing: LedgerEntry) : SaveResult
    data class Rejected(val reason: String) : SaveResult
}

internal fun LedgerEntry.normalized(): LedgerEntry = copy(
    id = id.trim(),
    merchant = merchant.trim(),
    category = category.trim(),
    evidenceId = evidenceId?.trim(),
    accountLabel = accountLabel?.trim()?.takeIf { it.isNotEmpty() },
)

internal fun LedgerEntry.validationError(): String? = when {
    id.isBlank() -> "账单标识不能为空"
    merchant.isBlank() -> "请填写商户或用途"
    amountMinor <= 0L -> "金额必须大于零"
    amountMinor > Money.MAX_AMOUNT_MINOR -> "单笔金额不能超过 999,999,999.99 元"
    category.isBlank() -> "请选择分类"
    occurredAt <= 0L -> "请选择有效的交易时间"
    evidenceId != null && evidenceId.isBlank() -> "来源标识不能为空白"
    else -> null
}
