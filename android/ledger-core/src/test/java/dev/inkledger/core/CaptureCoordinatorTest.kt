package dev.inkledger.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCoordinatorTest {
    private class RecordingRepository : LedgerRepository {
        val saved = mutableListOf<LedgerEntry>()
        override fun observeEntries(): Flow<List<LedgerEntry>> = flowOf(saved)
        override suspend fun save(entry: LedgerEntry): SaveResult {
            saved += entry
            return SaveResult.Saved(entry)
        }
        override suspend fun saveBatch(entries: List<LedgerEntry>): List<SaveResult> = entries.map { save(it) }
        override suspend fun delete(id: String) = Unit
        override suspend fun seedDemoIfNeeded() = Unit
    }

    private val evidence = CaptureEvidence("source:stable-event-1", PaymentChannel.WECHAT, 1L, "input")
    private val source = object : CaptureSource {
        override val sourceId = "test"
        override suspend fun capture() = CaptureResult.Captured(evidence)
    }

    @Test fun defaultCaptureIsExplicitlyUnavailableAndDoesNotSave() = runBlocking {
        val repo = RecordingRepository()
        val result = CaptureCoordinator(repo).collect()
        assertTrue(result is CaptureOutcome.Unavailable)
        assertEquals(CaptureStage.CAPTURE, (result as CaptureOutcome.Unavailable).stage)
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun imageInputDoesNotPretendToHaveBeenOcrRecognized() = runBlocking {
        val result = ProvidedTextRecognitionEngine().recognize(evidence.copy(text = null, localImageUri = "content://test/image"))
        assertTrue(result is RecognitionResult.Unavailable)
    }

    @Test fun missingFieldsNeverBecomeAZeroAmountLedgerEntry() = runBlocking {
        val repo = RecordingRepository()
        val parser = object : ReceiptParser {
            override fun parse(text: String, evidence: CaptureEvidence) =
                ReceiptParseResult.NeedsReview(listOf("金额"), "请补充金额")
        }
        val result = CaptureCoordinator(repo, source = source, parser = parser).collect()
        assertTrue(result is CaptureOutcome.NeedsReview)
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun parsedReceiptIsPendingAndPresentationFailureDoesNotLoseSavedResult() = runBlocking {
        val repo = RecordingRepository()
        val parser = object : ReceiptParser {
            override fun parse(text: String, evidence: CaptureEvidence) =
                ReceiptParseResult.Parsed(ParsedReceipt("商户", 1230, "餐饮", 1L))
        }
        val failingPresenter = object : CapsulePresenter {
            override suspend fun present(status: CapsuleStatus): CapsulePresentationResult = error("View detached")
        }
        val result = CaptureCoordinator(repo, source = source, parser = parser, capsule = failingPresenter).collect()
        assertTrue(result is CaptureOutcome.SavedPending)
        assertEquals(EntryState.PENDING, repo.saved.single().state)
        assertEquals(evidence.evidenceId, repo.saved.single().evidenceId)
        assertEquals(1230L, repo.saved.single().amountMinor)
    }

    @Test fun evidenceIdentityIsStableAndNamespaced() {
        val bytes = byteArrayOf(1, 2, 3, -1)
        assertEquals(EvidenceIds.fromBytes("image", bytes), EvidenceIds.fromBytes("image", bytes.copyOf()))
        assertNotEquals(EvidenceIds.fromBytes("image", bytes), EvidenceIds.fromBytes("notification", bytes))
        assertNotEquals(EvidenceIds.fromBytes("image", bytes), EvidenceIds.fromBytes("image", bytes + 4.toByte()))
    }
}
