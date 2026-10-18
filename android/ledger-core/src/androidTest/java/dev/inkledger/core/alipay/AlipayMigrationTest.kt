package dev.inkledger.core.alipay

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkledger.core.RoomLedgerRepository
import dev.inkledger.core.data.InkLedgerDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AlipayMigrationTest {
    @Test fun migrationPreservesVersionOneLedgerAndSeedMarkerAndValidatesNewTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val now = Instant.parse("2026-10-18T04:00:00Z").toEpochMilli()
        // Version-one DDL is copied from schemas/.../1.json, not generated from v2.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("""CREATE TABLE ledger_entries (id TEXT NOT NULL PRIMARY KEY,
                merchant TEXT NOT NULL, amountMinor INTEGER NOT NULL, category TEXT NOT NULL,
                channel TEXT NOT NULL, occurredAt INTEGER NOT NULL, state TEXT NOT NULL,
                evidenceId TEXT, type TEXT NOT NULL, accountLabel TEXT)""")
            old.execSQL("CREATE UNIQUE INDEX index_ledger_entries_evidenceId ON ledger_entries(evidenceId)")
            old.execSQL("CREATE INDEX index_ledger_entries_occurredAt ON ledger_entries(occurredAt)")
            old.execSQL("CREATE TABLE ledger_metadata (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            old.execSQL("INSERT INTO ledger_entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("old-entry", "合成旧商户", 1800L, "其他", "MANUAL", now, "Edited", "old-evidence", "Expend", null))
            old.execSQL("INSERT INTO ledger_metadata VALUES (?, ?)", arrayOf(RoomLedgerRepository.DEMO_SEED_KEY, "1"))
            old.version = 1
        }
        val database = Room.databaseBuilder(context, InkLedgerDatabase::class.java, name)
            .addMigrations(InkLedgerDatabase.MIGRATION_1_2).build()
        try {
            val repo = RoomLedgerRepository(database, isDemoDatabase = false)
            val old = repo.observeEntries().first().single()
            assertEquals("old-entry", old.id)
            assertEquals(1800L, old.amountMinor)
            assertEquals("1", database.ledgerDao().metadata(RoomLedgerRepository.DEMO_SEED_KEY))
            val evidence = AlipayEvidence(ALIPAY_PACKAGE_NAME, AlipaySource.NOTIFICATION,
                listOf("付款成功", "付款金额：19.00元", "商家：合成新商户"), now, "migration-event")
            assertTrue(AlipayProcessor(repo).process(evidence) is AlipayProcessResult.Saved)
            assertEquals(2, repo.observeEntries().first().size)
            assertEquals(1, database.alipayCaptureDao().receiptCount())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
