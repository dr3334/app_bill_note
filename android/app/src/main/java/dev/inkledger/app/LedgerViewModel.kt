package dev.inkledger.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.inkledger.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class LedgerUiState(
    val entries: List<LedgerEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val month: YearMonth = YearMonth.now(),
    val hideAmounts: Boolean = false,
    val reducedMotion: Boolean = false,
    val budgetMinor: Long = 400_000,
    val theme: Int = -1,
    val importProgress: Int? = null,
    val importReady: Boolean = false,
    val busy: Boolean = false,
) {
    val monthly get() = LedgerStats.forMonth(entries, month, ZoneId.systemDefault())
}

data class InAppNotice(val text: String, val amountMinor: Long? = null, val error: Boolean = false)

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as InkApplication).repository
    private val prefs = application.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(LedgerUiState(
        hideAmounts = prefs.getBoolean("private", false),
        reducedMotion = prefs.getBoolean("reduced", false),
        budgetMinor = prefs.getLong("budget", 400_000),
        theme = prefs.getInt("theme", -1)
    ))
    val state: StateFlow<LedgerUiState> = mutableState.asStateFlow()
    val notice = MutableStateFlow<InAppNotice?>(null)
    private var importJob: Job? = null
    private var observation: Job? = null

    init { load() }

    fun load() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                repo.seedDemoIfNeeded()
                repo.observeEntries().collect { entries -> mutableState.update { it.copy(entries = entries, loading = false, error = null) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, error = "账本暂时无法读取，请重试") } }
        }
    }

    fun changeMonth(offset: Long) { mutableState.update { it.copy(month = it.month.plusMonths(offset)) } }
    fun hideAmounts(value: Boolean) {
        prefs.edit().putBoolean("private", value).apply()
        mutableState.update { it.copy(hideAmounts = value) }
    }
    fun reduceMotion(value: Boolean) {
        prefs.edit().putBoolean("reduced", value).apply()
        mutableState.update { it.copy(reducedMotion = value) }
    }
    fun theme(value: Int) {
        prefs.edit().putInt("theme", value).apply()
        mutableState.update { it.copy(theme = value) }
    }
    fun budget(value: Long) {
        prefs.edit().putLong("budget", value).apply()
        mutableState.update { it.copy(budgetMinor = value) }
    }

    suspend fun save(entry: LedgerEntry): SaveResult = try {
        repo.save(entry).also { result ->
            when (result) {
                is SaveResult.Saved -> notice.value = InAppNotice(if (entry.state == EntryState.PENDING) "已保存待核对，未计入支出" else "已记一笔", if (entry.state == EntryState.CONFIRMED) entry.amountMinor else null)
                is SaveResult.Duplicate -> notice.value = InAppNotice("这笔已经记过，没有重复添加")
                is SaveResult.Rejected -> Unit
            }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { SaveResult.Rejected("暂未保存成功，内容已保留，请重试") }

    fun delete(entry: LedgerEntry) = viewModelScope.launch {
        try { repo.delete(entry.id); notice.value = InAppNotice("已删除这笔示例账目") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice.value = InAppNotice("删除失败，请重试", error = true) }
    }

    fun scenario(name: String) {
        if (state.value.busy || state.value.loading || state.value.error != null) return
        if (name == "failure") { notice.value = InAppNotice("演示：图片不清晰，未生成账目", error = true); return }
        if (name == "permission") { notice.value = InAppNotice("演示：微信来源权限失效，请检查来源设置", error = true); return }
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val pending = name == "review"
                val evidence = if (pending) "demo-review-fixed" else "demo-success-fixed"
                val existing = state.value.entries.firstOrNull { it.evidenceId == evidence }
                if (existing != null) {
                    notice.value = InAppNotice(if (existing.state == EntryState.PENDING) "这张示例凭据已在待核对列表" else "这笔已经记过，没有重复添加")
                } else {
                    val entry = LedgerEntry(
                        id = UUID.randomUUID().toString(), merchant = if (pending) "街角便利店" else "好好咖啡",
                        amountMinor = if (pending) 3280 else 2650, category = if (pending) "购物" else "餐饮",
                        channel = PaymentChannel.WECHAT, occurredAt = System.currentTimeMillis(),
                        state = if (pending) EntryState.PENDING else EntryState.CONFIRMED,
                        evidenceId = evidence,
                    )
                    when (val result = save(entry)) { is SaveResult.Rejected -> notice.value = InAppNotice(result.reason, error = true); else -> Unit }
                }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun startImport() {
        if (importJob?.isActive == true || state.value.busy) return
        if ((0..3).all { index -> state.value.entries.any { it.evidenceId == "demo-batch-$index" } }) {
            notice.value = InAppNotice("这批示例账单已处理，没有重复添加"); return
        }
        mutableState.update { it.copy(importReady = false, importProgress = 0) }
        importJob = viewModelScope.launch {
            try {
                repeat(4) { delay(280); mutableState.update { state -> state.copy(importProgress = it + 1) } }
                mutableState.update { it.copy(importReady = true, importProgress = null) }
                notice.value = InAppNotice("示例账单已整理，确认后再入账")
            } finally { if (!state.value.importReady) mutableState.update { it.copy(importProgress = null) } }
        }
    }

    fun cancelImport() {
        importJob?.cancel(); importJob = null
        mutableState.update { it.copy(importProgress = null, importReady = false) }
        notice.value = InAppNotice("已取消，账目没有变化")
    }

    fun batchEntries(): List<LedgerEntry> = listOf("早餐店" to 1800L, "城市地铁" to 600L, "生活超市" to 4200L, "共享单车" to 800L)
        .mapIndexed { index, (merchant, amount) -> LedgerEntry(
            id = UUID.randomUUID().toString(), merchant = merchant, amountMinor = amount,
            category = listOf("餐饮", "交通", "购物", "交通")[index],
            channel = PaymentChannel.ALIPAY, occurredAt = System.currentTimeMillis() - index * 60_000L,
            state = EntryState.CONFIRMED, evidenceId = "demo-batch-$index"
        ) }

    fun confirmImport() {
        if (!state.value.importReady || state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val results = repo.saveBatch(batchEntries())
                val saved = results.count { it is SaveResult.Saved }
                if (results.any { it is SaveResult.Rejected }) {
                    notice.value = InAppNotice("导入未完成，请检查后重试", error = true)
                } else {
                    mutableState.update { it.copy(importReady = false) }
                    val duplicate = results.count { it is SaveResult.Duplicate }
                    notice.value = InAppNotice(if (saved == 0) "这批账单已处理，没有重复添加" else "已导入 $saved 笔示例账目" + if (duplicate > 0) "，跳过 $duplicate 笔已有记录" else "")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice.value = InAppNotice("导入失败，账目未改变，可以重试", error = true) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
