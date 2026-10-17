package dev.inkledger.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** Independent schema: never opens or migrates an AutoAccounting database. */
@Database(entities = [LedgerEntryEntity::class, LedgerMetadata::class], version = 1, exportSchema = true)
@TypeConverters(LedgerConverters::class)
internal abstract class InkLedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
}
