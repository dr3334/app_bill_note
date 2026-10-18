package dev.inkledger.core.alipay

import dev.inkledger.core.Money
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Narrow payer-receipt parser. Page anchors additionally use the three user-supplied
 * Alipay screenshots; their text fixtures are manual transcriptions, not OCR executions.
 */
class AlipayReceiptParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val expectedPackage: String = ALIPAY_PACKAGE_NAME,
) {
    fun parse(evidence: AlipayEvidence): AlipayParseResult = parse(evidence, allowPromotionalFooter = false)

    /** Only a caller that has already checked receipt structure may permit footer promotions. */
    internal fun parse(evidence: AlipayEvidence, allowPromotionalFooter: Boolean): AlipayParseResult {
        if (evidence.packageName != expectedPackage) return ignored(AlipayDiagnosticCode.UNTRUSTED_SOURCE)
        if (evidence.captureId.isBlank() || evidence.observedAt <= 0L) return ignored(AlipayDiagnosticCode.INVALID_EVIDENCE)
        if (evidence.textLines.size > 256 || evidence.textLines.sumOf { it.length.toLong() } > 32_000L) {
            return ignored(AlipayDiagnosticCode.INPUT_TOO_LARGE)
        }
        val lines = normalizeLines(evidence.textLines)
        val text = lines.joinToString("\n")
        // Observed ML Kit output can use 額 in an amount label. Limit this
        // compatibility to OCR labels; never rewrite captured merchant text.
        val amountLabel = if (evidence.source == AlipaySource.OCR) ocrPaymentAmountLabel else paymentAmountLabel
        if (lines.any { notCompleted.containsMatchIn(it) }) return ignored(AlipayDiagnosticCode.PAYMENT_NOT_COMPLETED)
        if (expectedPackage == ALIPAY_PACKAGE_NAME && (lines.any { transferOutcome.containsMatchIn(it) } ||
                lines.take(3).any { transferHeading.matches(it) } ||
                labeledValues(lines, transactionTypeLabel).any { transferType.containsMatchIn(it) })) {
            return ignored(AlipayDiagnosticCode.TRANSFER_NOT_EXPENSE)
        }
        if (lines.any { unsupportedKind.containsMatchIn(it) } || lines.take(3).any { unsupportedHeading.matches(it) } ||
            labeledValues(lines, transactionTypeLabel).any { unsupportedTypeValue.containsMatchIn(it) }) {
            return ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND)
        }
        val page = if (expectedPackage == ALIPAY_PACKAGE_NAME && evidence.source != AlipaySource.NOTIFICATION)
            AlipayPageStructure.read(lines) else null
        val receiptLines = page?.fieldLines ?: lines
        val hasActualPaymentField = receiptLines.any { amountLabel.containsMatchIn(it) || inlinePayment.containsMatchIn(it) }
        // Payment result screens often contain promotional/transfer shortcuts below the
        // receipt. A verified structural adapter may have an unlabeled amount (WeChat
        // bill details), or be awaiting OCR, so footer words alone cannot reject it.
        // Marketing headings still reject even a structurally plausible receipt.
        if (lines.take(2).any { marketingHeading.matches(it) } ||
            (!hasActualPaymentField && page == null && !allowPromotionalFooter && marketing.containsMatchIn(text))) return ignored(AlipayDiagnosticCode.MARKETING)
        if (lines.none { successLine.containsMatchIn(it) || successfulSentence.containsMatchIn(it) }) {
            return ignored(AlipayDiagnosticCode.NO_SUCCESS)
        }
        if (page?.incoming == true) return ignored(AlipayDiagnosticCode.UNSUPPORTED_KIND)
        if (foreignCurrency.containsMatchIn(receiptLines.joinToString("\n"))) return ignored(AlipayDiagnosticCode.UNSUPPORTED_CURRENCY)

        val codes = mutableListOf<AlipayDiagnosticCode>()
        val labeled = labeledValues(receiptLines, amountLabel)
        val inline = receiptLines.flatMap { line -> inlinePayment.findAll(line).map { it.groupValues[1] }.toList() }
        val generic = receiptLines.mapIndexedNotNull { index, line ->
            val previous = receiptLines.getOrNull(index - 1).orEmpty()
            if (nonPaymentAmountLabel.containsMatchIn(previous)) return@mapIndexedNotNull null
            currencyOnly.matchEntire(line)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                ?: if (evidence.source != AlipaySource.OCR && index > 0 && successLine.matches(previous))
                    bareAmount.matchEntire(line)?.groupValues?.get(1) else null
        }
        // Explicit actual-payment labels outrank list prices, balances, and rewards.
        val amountTexts = if (page != null) page.amountTexts + labeled + inline
            else if (labeled.isNotEmpty() || inline.isNotEmpty()) labeled + inline else generic
        val amounts = amountTexts.mapNotNull { parseAmount(it) }.distinct()
        val hasInvalidAmount = amountTexts.any { parseAmount(it) == null }
        val amount = when {
            hasInvalidAmount -> { codes += AlipayDiagnosticCode.INVALID_AMOUNT; null }
            amounts.size > 1 -> { codes += AlipayDiagnosticCode.AMBIGUOUS_AMOUNT; null }
            amounts.isEmpty() -> { codes += AlipayDiagnosticCode.NO_AMOUNT; null }
            else -> amounts.single()
        }

        val merchantCandidates = (page?.merchants.orEmpty() + labeledValues(receiptLines, merchantLabel))
            .mapNotNull(::cleanMerchant).toMutableList()
        receiptLines.forEach { line -> payerMerchant.find(line)?.groupValues?.get(1)?.let(::cleanMerchant)?.let(merchantCandidates::add) }
        val merchants = merchantCandidates.distinct()
        val merchant = merchants.singleOrNull()
        if (merchants.isEmpty()) codes += AlipayDiagnosticCode.NO_MERCHANT
        if (merchants.size > 1) codes += AlipayDiagnosticCode.AMBIGUOUS_IDENTITY

        val rawIds = labeledValues(receiptLines, tradeIdLabel)
        val ids = rawIds.mapNotNull { tradeId.matchEntire(it.trim())?.value }.distinct()
        if (ids.size > 1 || rawIds.any { !tradeId.matches(it.trim()) }) codes += AlipayDiagnosticCode.AMBIGUOUS_IDENTITY
        val rawTimes = timeValues(receiptLines)
        val times = rawTimes.mapNotNull(::parseTime).distinct()
        if (times.size > 1) codes += AlipayDiagnosticCode.AMBIGUOUS_IDENTITY
        val transactionTime = times.singleOrNull()
        if ((transactionTime == null && evidence.source != AlipaySource.NOTIFICATION) || rawTimes.any { parseTime(it) == null }) {
            codes += AlipayDiagnosticCode.NO_TRANSACTION_TIME
        }
        if (transactionTime != null && (transactionTime > evidence.observedAt + 300_000L ||
                transactionTime < evidence.observedAt - 86_400_000L)) codes += AlipayDiagnosticCode.HISTORICAL_OR_FUTURE
        if (evidence.source == AlipaySource.OCR) codes += AlipayDiagnosticCode.OCR_UNVERIFIED
        return AlipayParseResult.Candidate(AlipayPaymentCandidate(amount, merchant, ids.singleOrNull(), transactionTime, codes.distinct()))
    }

    private fun ignored(code: AlipayDiagnosticCode) = AlipayParseResult.Ignored(code)

    private fun labeledValues(lines: List<String>, label: Regex): List<String> = lines.mapIndexedNotNull { index, line ->
        val match = label.find(line) ?: return@mapIndexedNotNull null
        val after = line.substring(match.range.last + 1).trim().trimStart(':', '：').trim()
        after.ifEmpty { lines.getOrNull(index + 1)?.trim().orEmpty() }.takeIf { it.isNotEmpty() }
    }

    private fun parseAmount(text: String): Long? {
        val token = fullAmount.matchEntire(text.trim())?.groupValues?.get(1) ?: return null
        return Money.parseMinor(token.replace(",", ""))
    }

    private fun timeValues(lines: List<String>): List<String> = lines.mapIndexedNotNull { index, line ->
        val match = timeLabel.find(line) ?: return@mapIndexedNotNull null
        val inline = line.substring(match.range.last + 1).trim().trimStart(':', '：').trim()
        val dateIndex = if (inline.isEmpty()) index + 1 else index
        val value = inline.ifEmpty { lines.getOrNull(dateIndex).orEmpty() }
        val next = lines.getOrNull(dateIndex + 1).orEmpty()
        (if (dateOnly.matches(value) && clockOnly.matches(next)) "$value $next" else value).takeIf { it.isNotEmpty() }
    }

    private fun cleanMerchant(value: String): String? = value.trim().takeIf {
        it.length in 1..100 && it.any(Char::isLetterOrDigit) &&
            !successLine.containsMatchIn(it) && !receiptFieldLabel.containsMatchIn(it) &&
            !bareAmount.matches(it) && !currencyOnly.matches(it)
    }

    private fun parseTime(value: String): Long? {
        try { return OffsetDateTime.parse(value).toInstant().toEpochMilli() } catch (_: Exception) { }
        val normalized = value.replace('/', '-').replace('年', '-').replace('月', '-').replace("日", " ")
            .replace(Regex("\\s+"), " ").trim()
        for (format in timeFormats) {
            try { return LocalDateTime.parse(normalized, format).atZone(zoneId).toInstant().toEpochMilli() } catch (_: Exception) { }
        }
        return null
    }

    companion object {
        internal fun normalizeLines(lines: List<String>): List<String> = lines.flatMap { it.lines() }.map {
            Normalizer.normalize(it, Normalizer.Form.NFKC)
                .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
                .replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotEmpty() }

        private val notCompleted = Regex("^(?:(?:交易状态|付款状态|支付状态|订单状态)[: ]*)?(?:待付款|待支付|等待付款|等待支付|未付款|未支付|支付失败|付款失败|交易失败|交易关闭|支付取消|取消支付|已取消|订单关闭|支付处理中|付款处理中)(?:$|[ :，,。])")
        private val unsupportedKind = Regex("^(?:(?:交易状态|交易类型|业务类型)[: ]*)?(?:收款成功|收款到账|收款金额|转账成功|转入成功|转出成功|退款成功|退款处理中|退款金额|充值成功|提现成功|还款成功|借款成功)(?:$|[ :，,。0-9¥])|^(?:交易类型|业务类型)[: ]*(?:转账|退款|收款|充值|提现|还款|理财)(?:$|[ :，,。])|^(?:您|你)?收到.{0,40}[0-9].*元")
        private val transferOutcome = Regex("^(?:(?:交易状态|交易类型|业务类型)[: ]*)?(?:转账成功|转入成功|转出成功)(?:$|[ :，,。0-9¥])")
        private val transferHeading = Regex("转账|转账到银行卡|转账到账")
        private val transferType = Regex("^(?:转账|转入|转出)")
        private val unsupportedHeading = Regex("转账|转账到银行卡|转账到账|收款|退款|充值|提现|信用卡还款|借款|理财")
        private val unsupportedTypeValue = Regex("^(?:转账|转入|转出|收款|退款|充值|提现|还款|信用卡还款|借款|理财)")
        private val marketingHeading = Regex("活动通知|营销通知|广告|优惠券到账|红包到账|领券提醒")
        private val marketing = Regex("优惠券|领券|领取|红包|限时活动|活动通知|营销|广告|支付成功后|付款成功后|支付即享|付款即享|去支付|去付款|立即购买")
        private val foreignCurrency = Regex("美元|港币|日元|欧元|英镑|USD|HKD|JPY|EUR|GBP|\\$", RegexOption.IGNORE_CASE)
        private val successLine = Regex("^(?:支付宝[ :·]*)?(?:支付成功|付款成功|交易成功)(?:$|[ :，,。¥0-9])")
        private val successfulSentence = Regex("^(?:您|你)(?:已)?(?:(?:向|在).{1,100}?(?:成功付款|付款成功|支付成功)|(?:成功付款|成功支付)[ :¥]*[0-9])")
        private val paymentAmountLabel = Regex("^(?:实付款|实付金额|实际付款|实际支付|付款金额|支付金额)(?:[: ]|$)")
        private val ocrPaymentAmountLabel = Regex(paymentAmountLabel.pattern.replace("实付金额", "实付金[额額]"))
        private val nonPaymentAmountLabel = Regex("余额|优惠|原价|订单金额|商品金额|红包|积分|奖励")
        private const val NUMBER = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)"
        private val fullAmount = Regex("^[¥]?\\s*$NUMBER\\s*(?:元|人民币)?$")
        private val inlinePayment = Regex("(?:成功付款|成功支付|付款成功|支付成功|付款)[ :]*[¥]?\\s*$NUMBER\\s*元(?:[，,。 ]|$)")
        private val currencyOnly = Regex("^(?:¥\\s*$NUMBER|$NUMBER\\s*元)$")
        private val bareAmount = Regex("^$NUMBER$")
        private val merchantLabel = Regex("^(?:商户全称|商家名称|商户名称|商户名|商家|商户|收款方|收款商户|付款给)(?:[: ]|$)")
        private val receiptFieldLabel = Regex("^(?:支付宝交易号|交易号|交易单号|商户订单号|交易时间|付款时间|支付时间|付款方式|支付方式|交易状态|订单状态|交易类型|业务类型|实付款|实付金额|实际付款|实际支付|付款金额|支付金额)(?:[: ]|$)")
        private val payerMerchant = Regex("(?:您|你)(?:已)?(?:向|在)(.{1,100}?)(?:成功付款|付款成功|支付成功|付款)")
        private val tradeIdLabel = Regex("^(?:支付宝交易号|交易号|交易单号)(?:[: ]|$)")
        private val transactionTypeLabel = Regex("^(?:交易类型|业务类型)(?:[: ]|$)")
        private val tradeId = Regex("[0-9]{16,40}")
        private val timeLabel = Regex("^(?:交易时间|付款时间|支付时间)(?:[: ]|$)")
        private val dateOnly = Regex("[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}")
        private val clockOnly = Regex("[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?")
        private val timeFormats = listOf("uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm")
            .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
    }
}
