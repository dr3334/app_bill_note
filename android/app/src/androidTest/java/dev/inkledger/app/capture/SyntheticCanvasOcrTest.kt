package dev.inkledger.app.capture

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.inkledger.core.alipay.ALIPAY_PACKAGE_NAME
import dev.inkledger.core.alipay.AlipayDiagnosticCode
import dev.inkledger.core.alipay.AlipayEvidence
import dev.inkledger.core.alipay.AlipayParseResult
import dev.inkledger.core.alipay.AlipayReceiptParser
import dev.inkledger.core.alipay.AlipaySource
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Only the checked-in SYNTHETIC fixture is read. No device screenshot or payment data. */
@RunWith(AndroidJUnit4::class)
class SyntheticCanvasOcrTest {
    @Test fun bundledChineseModelRecognizesSyntheticAmountWithoutAutoConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("synthetic-canvas.png").use {
            requireNotNull(BitmapFactory.decodeStream(it)) { "Synthetic test image cannot be decoded" }
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            // Exactly the production service extraction; preserve ML Kit's block/line order.
            val lines = recognized.textBlocks.flatMap { it.lines }.map { it.text }.take(300)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nSYNTHETIC_FIXTURE_OCR_LINES=" + JSONArray(lines).toString() + "\n")
            })
            val parsed = AlipayReceiptParser().parse(AlipayEvidence(
                ALIPAY_PACKAGE_NAME, AlipaySource.OCR, lines, System.currentTimeMillis(), "synthetic-canvas-ocr-regression",
            ))
            assertTrue("Synthetic success page should produce a candidate, got $parsed", parsed is AlipayParseResult.Candidate)
            val candidate = (parsed as AlipayParseResult.Candidate).payment
            assertEquals("Synthetic amount must be 23.45 CNY", 2345L, candidate.amountMinor)
            // The parser must retain this review code; core integration tests verify its
            // resulting PENDING database state. This test never writes the live ledger.
            assertTrue("OCR must remain pending review", AlipayDiagnosticCode.OCR_UNVERIFIED in candidate.reviewCodes)
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}
