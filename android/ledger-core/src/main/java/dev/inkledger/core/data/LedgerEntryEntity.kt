/*
 * Copyright (C) 2024 ankio(ankio@ankio.net)
 * Modified 2026-10-18 for Ink Ledger. Field model adapted from AutoAccounting
 * BillInfoModel.kt @ 34b38f82ca9ad6d7905c715c66891977f8809c88.
 * GPL v3 repository-level terms; see LICENSE and UPSTREAM.md.
 */
package dev.inkledger.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import dev.inkledger.core.EntryKind
import dev.inkledger.core.EntryState
import dev.inkledger.core.LedgerEntry
import dev.inkledger.core.PaymentChannel
import dev.inkledger.core.upstream.BillState
import dev.inkledger.core.upstream.BillType

@Entity(
    tableName = "ledger_entries",
    indices = [Index(value = ["evidenceId"], unique = true), Index(value = ["occurredAt"])],
)
internal data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val merchant: String,
    val amountMinor: Long,
    val category: String,
    val channel: PaymentChannel,
    val occurredAt: Long,
    val state: BillState,
    val evidenceId: String?,
    val type: BillType,
    val accountLabel: String?,
) {
    fun toEntry(): LedgerEntry = LedgerEntry(
        id, merchant, amountMinor, category, channel, occurredAt,
        when (state) {
            BillState.Wait2Edit -> EntryState.PENDING
            BillState.Edited, BillState.Synced -> EntryState.CONFIRMED
        },
        evidenceId,
        when (type) {
            BillType.Expend -> EntryKind.EXPENSE
            BillType.Income -> EntryKind.INCOME
            BillType.Transfer -> EntryKind.TRANSFER
            BillType.IncomeRefund -> EntryKind.REFUND
            else -> error("Unsupported type in the independent Ink Ledger database: $type")
        },
        accountLabel,
    )

    companion object {
        fun from(entry: LedgerEntry): LedgerEntryEntity = LedgerEntryEntity(
            entry.id, entry.merchant, entry.amountMinor, entry.category, entry.channel,
            entry.occurredAt, entry.state.toStoredState(), entry.evidenceId,
            entry.kind.storedType, entry.accountLabel,
        )
    }
}

@Entity(tableName = "ledger_metadata")
internal data class LedgerMetadata(@PrimaryKey val key: String, val value: String)

internal class LedgerConverters {
    @TypeConverter fun stateToString(value: BillState): String = value.name
    @TypeConverter fun stringToState(value: String): BillState = BillState.valueOf(value)
    @TypeConverter fun typeToString(value: BillType): String = value.name
    @TypeConverter fun stringToType(value: String): BillType = BillType.valueOf(value)
    @TypeConverter fun channelToString(value: PaymentChannel): String = value.name
    @TypeConverter fun stringToChannel(value: String): PaymentChannel = PaymentChannel.valueOf(value)
}
