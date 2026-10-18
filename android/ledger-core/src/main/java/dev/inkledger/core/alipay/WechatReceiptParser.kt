package dev.inkledger.core.alipay

import java.time.ZoneId

/** Payer receipts only. Chat, transfers, red packets and incoming money are not expenses.
 * Templates are synthetic until verified on a particular WeChat version/device.
 * Reuses money/time validation and review handling from the existing receipt parser.
 */
class WechatReceiptParser(zoneId: ZoneId = ZoneId.systemDefault()) {
    private val parser = AlipayReceiptParser(zoneId, WECHAT_PACKAGE_NAME)

    fun parse(evidence: AlipayEvidence): AlipayParseResult {
        if (evidence.packageName != WECHAT_PACKAGE_NAME) return AlipayParseResult.Ignored(AlipayDiagnosticCode.UNTRUSTED_SOURCE)
        if (evidence.captureId.isBlank() || evidence.observedAt <= 0L) return AlipayParseResult.Ignored(AlipayDiagnosticCode.INVALID_EVIDENCE)
        if (evidence.textLines.size > 256 || evidence.textLines.sumOf { it.length.toLong() } > 32_000) {
            return AlipayParseResult.Ignored(AlipayDiagnosticCode.INPUT_TOO_LARGE)
        }
        val lines = AlipayReceiptParser.normalizeLines(evidence.textLines)
        // Check both inline and split-node fields before translating the WeChat labels.
        // A footer such as “申请退款” is not a refund, but “当前状态 / 已退款” is.
        if (lines.take(4).any { unsupportedHeading.matches(it) } ||
            lines.any { unsupportedOutcome.containsMatchIn(it) } ||
            fieldValues(lines, statusOrTypeLabel).any { unsupportedValue.containsMatchIn(it) }) {
            return AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND)
        }
        if (lines.any { chatControls.matches(it) }) return AlipayParseResult.Ignored(AlipayDiagnosticCode.NOT_PAYMENT_RECEIPT)
        if (evidence.source == AlipaySource.NOTIFICATION) {
            // A WeChat package also emits private/group chats; a title is only a hint.
            if (lines.firstOrNull() !in setOf("微信支付", "微信支付：", "微信支付:")) {
                return AlipayParseResult.Ignored(AlipayDiagnosticCode.NOT_PAYMENT_RECEIPT)
            }
        } else {
            // Two aliases of the amount label are still only one piece of structure.
            // Amount can be absent here so the caller can request its OCR fallback.
            val fields = fieldGroups.count { label -> lines.any { label.containsMatchIn(it) } }
            if (fields < 2) {
                return AlipayParseResult.Ignored(AlipayDiagnosticCode.NOT_PAYMENT_RECEIPT)
            }
        }
        val detail = evidence.source != AlipaySource.NOTIFICATION &&
            (lines.any { it == "账单详情" } || lines.any { statusLabel.containsMatchIn(it) })
        val normalized = lines.map { line ->
            line.replace(statusLabel, "")
                .replace(Regex("^微信支付[ :·-]*(?=支付成功|付款成功)"), "")
                .replace(Regex("^(?:已支付|已付款)$"), "支付成功")
                .replace(Regex("^收款商家(?=[: ]|$)"), "商家")
                .replace(Regex("^微信支付单号(?=[: ]|$)"), "交易单号")
        }.filter { it.isNotEmpty() }
        // Some receipt layouts expose the currency sign and digits as separate nodes.
        val joined = buildList {
            var i = 0
            while (i < normalized.size) {
                val line = normalized[i]
                val next = normalized.getOrNull(i + 1)
                if (line in setOf("¥", "-¥", "−¥") && next != null && number.matches(next)) {
                    add("$line$next"); i += 2
                } else { add(line); i++ }
            }
        }
        // Bill details display expenses with a minus sign. Only this receipt context
        // turns it into a positive expense magnitude; plus signs mean incoming money.
        if (detail && joined.withIndex().any { (index, line) ->
                positiveAmount.matches(line) && !nonPaymentAmountLabel.containsMatchIn(joined.getOrNull(index - 1).orEmpty())
            }) {
            return AlipayParseResult.Ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND)
        }
        val canonical = if (detail) joined.map { line ->
            negativeAmount.matchEntire(line)?.let { "¥${it.groupValues[1]}" }
                ?: negativeLabeledAmount.matchEntire(line)?.let { "${it.groupValues[1]}: ${it.groupValues[2]}元" }
                ?: line
        } else joined
        return when (val result = parser.parse(evidence.copy(textLines = canonical),
            allowPromotionalFooter = evidence.source != AlipaySource.NOTIFICATION)) {
            is AlipayParseResult.Candidate -> if (evidence.source == AlipaySource.NOTIFICATION) {
                AlipayParseResult.Candidate(result.payment.copy(reviewCodes =
                    (result.payment.reviewCodes + AlipayDiagnosticCode.WECHAT_NOTIFICATION_UNVERIFIED +
                        if (result.payment.transactionTime == null) listOf(AlipayDiagnosticCode.NO_TRANSACTION_TIME) else emptyList()).distinct()))
            } else result
            else -> result
        }
    }

    private fun fieldValues(lines: List<String>, label: Regex): List<String> = lines.mapIndexedNotNull { index, line ->
        val match = label.find(line) ?: return@mapIndexedNotNull null
        line.substring(match.range.last + 1).trim().ifEmpty { lines.getOrNull(index + 1).orEmpty() }.takeIf { it.isNotEmpty() }
    }

    companion object {
        private val statusLabel = Regex("^(?:当前状态|交易状态|支付状态|付款状态)(?:[: ]+|$)")
        private val statusOrTypeLabel = Regex("^(?:当前状态|交易状态|支付状态|付款状态|交易类型|业务类型)(?:[: ]+|$)")
        private val fieldGroups = listOf(
            "付款金额|支付金额|实付金额|实付金額|实付款|实际付款|实际支付",
            "商户全称|商户名称|商家名称|商户名|商家|商户|收款方|收款商家|收款商户|付款给",
            "支付方式|付款方式", "支付时间|付款时间|交易时间", "交易单号|微信支付单号|交易号",
            "当前状态|交易状态|支付状态|付款状态",
        ).map { Regex("^(?:$it)(?:[: ]|$)") }
        private val chatControls = Regex("发送|按住[ 说]*话|切换到键盘|聊天信息|语音输入|表情|添加到表情|撤回消息")
        private val unsupportedHeading = Regex("(?:微信转账|转账|微信红包|红包|群收款|收款|退款|零钱充值|零钱提现|信用卡还款)(?:$|[ :，,。-].*|给.*)")
        private val unsupportedOutcome = Regex("^(?:收款成功|收款到账|转账成功|退款成功|退款中|退款处理中|已退款|已全额退款|已部分退款|对方已收钱|已收钱|已收款|待收款|已存入零钱|收到红包)(?:$|[ :，,。0-9¥])")
        private val unsupportedValue = Regex("^(?:转账|转入|转出|红包|收款|退款|已退款|已全额退款|已部分退款|对方已收钱|已收钱|已收款|待收款|充值|提现|零钱充值|零钱提现|信用卡还款)")
        private val nonPaymentAmountLabel = Regex("余额|优惠|原价|订单金额|商品金额|红包|积分|奖励")
        private const val NUMBER = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)"
        private val number = Regex(NUMBER)
        private val negativeAmount = Regex("^(?:[-−]\\s*¥?|¥\\s*[-−])\\s*$NUMBER\\s*(?:元)?$")
        private val positiveAmount = Regex("^(?:(?:付款金额|支付金额|实付金额)[: ]*)?(?:\\+\\s*¥?|¥\\s*\\+)\\s*$NUMBER\\s*(?:元)?$")
        private val negativeLabeledAmount = Regex("^(付款金额|支付金额|实付金额|实付款|实际付款|实际支付)[: ]*(?:[-−]\\s*¥?|¥\\s*[-−])\\s*$NUMBER\\s*(?:元)?$")
    }
}
