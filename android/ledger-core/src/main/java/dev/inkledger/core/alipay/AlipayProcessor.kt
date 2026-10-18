package dev.inkledger.core.alipay

import dev.inkledger.core.RoomLedgerRepository
import kotlinx.coroutines.CancellationException

class AlipayProcessor(
    private val repository: RoomLedgerRepository,
    private val parser: AlipayReceiptParser = AlipayReceiptParser(),
    private val wechatParser: WechatReceiptParser = WechatReceiptParser(),
) {
    suspend fun process(evidence: AlipayEvidence): AlipayProcessResult = try {
        when (val result = if (evidence.packageName == WECHAT_PACKAGE_NAME) wechatParser.parse(evidence) else parser.parse(evidence)) {
            is AlipayParseResult.Ignored -> AlipayProcessResult.Ignored(result.code.message, result.code)
            is AlipayParseResult.Candidate -> repository.storeAlipay(evidence, result.payment)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Never put sensitive source text or exception contents into UI/diagnostic storage.
        AlipayProcessResult.Failed(AlipayDiagnosticCode.STORAGE_FAILURE.message)
    }
}
