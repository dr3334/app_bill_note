package dev.inkledger.core.alipay

import dev.inkledger.core.EvidenceIds
import java.util.Locale

/** These keys are the persisted identity contract. Keep legacy Alipay inputs intact. */
internal data class PaymentEvidenceIdentity(
    val event: String,
    val capture: String,
    val session: String?,
    val payload: String,
) {
    companion object {
        fun from(evidence: AlipayEvidence): PaymentEvidenceIdentity {
            val namespace = evidence.namespace()
            val payload = hash("$namespace-payload", AlipayReceiptParser.normalizeLines(evidence.textLines).joinToString("\n"))
            val capture = hash("$namespace-capture", "${evidence.source.name}\u0000${evidence.captureId}")
            return PaymentEvidenceIdentity(hash("$namespace-event", "$capture\u0000$payload"), capture,
                evidence.sessionId?.takeIf { it.isNotBlank() }?.let { hash("$namespace-session", "${evidence.source.name}\u0000$it") }, payload)
        }

        fun transactionKey(evidence: AlipayEvidence, transactionId: String?): String? {
            // OCR can misread IDs. A WeChat notification title also cannot authenticate
            // the sender: neither may claim a strong identity over a real payment page.
            if (evidence.source == AlipaySource.OCR ||
                (evidence.packageName == WECHAT_PACKAGE_NAME && evidence.source == AlipaySource.NOTIFICATION)) return null
            return transactionId?.let { hash("${evidence.namespace()}-transaction", it) }
        }

        fun merchantKey(evidence: AlipayEvidence, merchant: String?): String? = merchant?.let {
            hash("${evidence.namespace()}-merchant", it.lowercase(Locale.ROOT).replace(Regex("\\s+"), ""))
        }

        private fun hash(namespace: String, value: String): String =
            EvidenceIds.fromBytes(namespace, value.toByteArray(Charsets.UTF_8))
    }
}
