package dev.inkledger.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Independent schema: never opens or migrates an AutoAccounting database. */
@Database(entities = [LedgerEntryEntity::class, LedgerMetadata::class, AlipayReceiptEntity::class,
    AlipayEvidenceEntity::class], version = 2, exportSchema = true)
@TypeConverters(LedgerConverters::class)
internal abstract class InkLedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun alipayCaptureDao(): AlipayCaptureDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS alipay_receipts (
                    id TEXT NOT NULL PRIMARY KEY, transactionKey TEXT, entryId TEXT,
                    amountMinor INTEGER, merchantKey TEXT, transactionTime INTEGER,
                    firstObservedAt INTEGER NOT NULL, lastObservedAt INTEGER NOT NULL,
                    firstSource TEXT NOT NULL, deleted INTEGER NOT NULL, userEdited INTEGER NOT NULL,
                    diagnosticCodes TEXT NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_alipay_receipts_transactionKey ON alipay_receipts(transactionKey)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_alipay_receipts_entryId ON alipay_receipts(entryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alipay_receipts_lastObservedAt ON alipay_receipts(lastObservedAt)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS alipay_evidence (
                    eventKey TEXT NOT NULL PRIMARY KEY, captureKey TEXT NOT NULL, sessionKey TEXT,
                    payloadHash TEXT NOT NULL, source TEXT NOT NULL, observedAt INTEGER NOT NULL,
                    receiptId TEXT NOT NULL, diagnosticCodes TEXT NOT NULL)""")
                for (column in listOf("captureKey", "sessionKey", "payloadHash", "receiptId")) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_alipay_evidence_$column ON alipay_evidence($column)")
                }
            }
        }
    }
}
