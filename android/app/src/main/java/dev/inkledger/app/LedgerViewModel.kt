package dev.inkledger.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.inkledger.core.*
import kotlinx.coroutines.CancellationException
import dev.inkledger.app.capture.AlipayCaptureState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

data class LedgerUiState(
    val entries: List<LedgerEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val month: YearMonth = YearMonth.now(),
    val hideAmounts: Boolean = false,
    val reducedMotion: Boolean = false,
    val budgetMinor: Long = 400_000,
    val theme: Int = -1,
    val capture: AlipayCaptureState = AlipayCaptureState(),
) {
    val monthly get() = LedgerStats.forMonth(entries, month, ZoneId.systemDefault())
}

data class InAppNotice(val text: String, val amountMinor: Long? = null, val error: Boolean = false)

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as InkApplication).repository
    val capture = (application as InkApplication).captureManager
    private val prefs = application.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(LedgerUiState(
        hideAmounts = prefs.getBoolean("private", false),
        reducedMotion = prefs.getBoolean("reduced", false),
        budgetMinor = prefs.getLong("budget", 400_000),
        theme = prefs.getInt("theme", -1)
    ))
    val state: StateFlow<LedgerUiState> = mutableState.asStateFlow()
    val notice = MutableStateFlow<InAppNotice?>(null)
    private var observation: Job? = null

    init {
        load()
        viewModelScope.launch {
            var lastNoticeId: String? = null
            capture.state.collect { captureState ->
                mutableState.update { it.copy(capture = captureState) }
                captureState.lastResult?.let { result ->
                    if (result.id != lastNoticeId) {
                        lastNoticeId = result.id
                        notice.value = InAppNotice(result.title)
                    }
                }
            }
        }
    }

    fun load() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
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
        try { repo.delete(entry.id); notice.value = InAppNotice("已删除这笔账目") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice.value = InAppNotice("删除失败，请重试", error = true) }
    }

    fun refreshCapture() = capture.refreshAuthorizationState()
}
