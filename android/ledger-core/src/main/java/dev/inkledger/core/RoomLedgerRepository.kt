package dev.inkledger.core

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dev.inkledger.core.data.InkLedgerDatabase
import dev.inkledger.core.data.LedgerEntryEntity
import dev.inkledger.core.data.LedgerMetadata
import dev.inkledger.core.alipay.AlipayEvidence
import dev.inkledger.core.alipay.AlipayIdentityStore
import dev.inkledger.core.alipay.AlipayPaymentCandidate
import dev.inkledger.core.alipay.AlipayProcessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import java.time.ZoneId

class RoomLedgerRepository internal constructor(
    private val database: InkLedgerDatabase,
    private val isDemoDatabase: Boolean,
) : LedgerRepository {
    private val dao = database.ledgerDao()

    override fun observeEntries(): Flow<List<LedgerEntry>> =
        dao.observeEntries().map { rows -> rows.map { it.toEntry() } }

    override suspend fun save(entry: LedgerEntry): SaveResult = try {
        saveBatch(listOf(entry)).single()
    } catch (error: BatchSaveException) {
        SaveResult.Rejected(error.message ?: "本地保存失败，请重试")
    }

    override suspend fun saveBatch(entries: List<LedgerEntry>): List<SaveResult> {
        val normalized = entries.mapIndexed { index, entry ->
            entry.normalized().also { value ->
                value.validationError()?.let { throw BatchSaveException("第 ${index + 1} 笔：$it") }
            }
        }
        return try {
            database.withTransaction {
                normalized.map { value ->
                    val current = dao.findById(value.id)
                    if (current != null && current.evidenceId != value.evidenceId) {
                        throw BatchSaveException("来源标识不能在编辑账单时更换")
                    }
                    val sameEvidence = value.evidenceId?.let { dao.findByEvidence(it) }
                    if (sameEvidence != null && sameEvidence.id != value.id) {
                        SaveResult.Duplicate(sameEvidence.toEntry())
                    } else {
                        val row = LedgerEntryEntity.from(value)
                        if (current == null) dao.insert(row) else {
                            database.alipayCaptureDao().markUserEdited(value.id)
                            dao.update(row)
                        }
                        SaveResult.Saved(value)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: BatchSaveException) {
            throw rejected
        } catch (error: Exception) {
            // Room rolls back the complete batch before this error reaches the UI.
            throw BatchSaveException("本地保存失败，请重试；本批次未写入", error)
        }
    }

    override suspend fun delete(id: String) {
        database.withTransaction {
            database.alipayCaptureDao().markDeleted(id)
            dao.delete(id)
        }
    }

    /** Keeps the seed marker. A cleared demo ledger stays empty on every restart. */
    suspend fun clearEntries() {
        database.withTransaction {
            database.alipayCaptureDao().markAllDeleted()
            dao.clearEntries()
        }
    }

    override suspend fun seedDemoIfNeeded() {
        if (!isDemoDatabase) return
        database.withTransaction {
            if (dao.metadata(DEMO_SEED_KEY) != null) return@withTransaction
            // Also protect data saved before the first invocation: never mix in samples.
            if (dao.count() == 0) DemoEntries.create().forEach { dao.insert(LedgerEntryEntity.from(it)) }
            dao.insertMetadata(LedgerMetadata(DEMO_SEED_KEY, "1"))
        }
    }

    internal suspend fun storeAlipay(evidence: AlipayEvidence, candidate: AlipayPaymentCandidate): AlipayProcessResult {
        if (isDemoDatabase) return AlipayProcessResult.Failed("真实采集不能写入示例账本，请切换真实测试账本")
        return AlipayIdentityStore(database).accept(evidence, candidate)
    }

    /** SQL equivalent of LedgerStats, useful when the ledger outgrows list aggregation. */
    suspend fun statsForMonth(month: YearMonth, zoneId: ZoneId): MonthlyStats = database.withTransaction {
        val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val income = dao.sumConfirmedByType("Income", start, end)
        val expense = dao.sumConfirmedByType("Expend", start, end)
        val refund = dao.sumConfirmedByType("IncomeRefund", start, end)
        val net = Math.subtractExact(expense, refund)
        MonthlyStats(income, expense, refund, net, Math.subtractExact(income, net),
            dao.confirmedCount(start, end), dao.pendingCount(start, end), dao.expenseCategoryTotals(start, end))
    }

    companion object {
        const val DATABASE_NAME: String = "ink-ledger-demo.db"
        const val LIVE_DATABASE_NAME: String = "ink-ledger-live.db"
        internal const val DEMO_SEED_KEY = "demo-seed-v1"
        @Volatile private var instance: RoomLedgerRepository? = null
        @Volatile private var liveInstance: RoomLedgerRepository? = null

        fun create(context: Context): RoomLedgerRepository = instance ?: synchronized(this) {
            instance ?: RoomLedgerRepository(
                Room.databaseBuilder(context.applicationContext, InkLedgerDatabase::class.java, DATABASE_NAME)
                    .addMigrations(InkLedgerDatabase.MIGRATION_1_2)
                    .build(),
                isDemoDatabase = true,
            ).also { instance = it }
        }

        fun createLive(context: Context): RoomLedgerRepository = liveInstance ?: synchronized(this) {
            liveInstance ?: RoomLedgerRepository(
                Room.databaseBuilder(context.applicationContext, InkLedgerDatabase::class.java, LIVE_DATABASE_NAME)
                    .addMigrations(InkLedgerDatabase.MIGRATION_1_2)
                    .build(),
                isDemoDatabase = false,
            ).also { liveInstance = it }
        }
    }
}
