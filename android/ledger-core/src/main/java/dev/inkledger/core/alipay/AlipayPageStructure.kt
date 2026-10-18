package dev.inkledger.core.alipay

/** Layout anchors from the three user-supplied Alipay screenshots (June–September 2026).
 * Text order is reading order. These are manually transcribed layouts, not an OCR claim.
 * Only the receipt's primary amount can supply an unlabeled value; prices and promotions
 * elsewhere on the page are never fallback amounts.
 */
internal data class AlipayPageStructure(
    val fieldLines: List<String>,
    val amountTexts: List<String>,
    val merchants: List<String>,
    val incoming: Boolean = false,
) {
    companion object {
        fun read(input: List<String>): AlipayPageStructure? {
            val lines = joinAmountNodes(input)
            val detailIndex = lines.indexOfFirst { it == "账单详情" }
            if (detailIndex >= 0 && detailFields.count { label -> lines.any { label.containsMatchIn(it) } } >= 2) {
                return readDetail(lines, detailIndex)
            }
            val successIndex = lines.indexOfFirst { paymentSuccess.containsMatchIn(it) }
            val hasReceiptStructure = lines.any { paymentMethod.containsMatchIn(it) } ||
                ("回首页" in lines && "完成" in lines)
            return if (successIndex >= 0 && hasReceiptStructure) readPayment(lines, successIndex) else null
        }

        private fun readPayment(lines: List<String>, successIndex: Int): AlipayPageStructure {
            val footer = lines.indexOfFirstFrom(successIndex + 1) { promotionFooter.containsMatchIn(it) }
            val fields = lines.take(if (footer < 0) lines.size else footer)
            val inline = paymentSuccess.find(lines[successIndex])!!.let { match ->
                lines[successIndex].substring(match.range.last + 1).trim().trimStart(':').trim()
            }
            var amountIndex = successIndex
            val primary = if (inline.isNotEmpty()) inline.takeIf { monetaryToken.matches(it) } else {
                amountIndex = (successIndex + 1 until fields.size).firstOrNull { fields[it] != "回首页" } ?: fields.size
                fields.getOrNull(amountIndex)?.takeIf { monetaryToken.matches(it) }
            }
            // In the supplied success page the merchant row is below the primary amount
            // and reward text, above the discount/payment-method rows. Its right-hand price
            // is useful as a row anchor, but is deliberately never an amount candidate.
            val end = fields.indexOfFirstFrom(successIndex + 1) { paymentMethod.containsMatchIn(it) }
                .takeIf { it >= 0 } ?: fields.size
            val body = fields.subList((amountIndex + 1).coerceAtMost(end), end)
            val merchants = body.mapIndexedNotNull { index, line ->
                val inlineMerchant = merchantAndPrice.matchEntire(line)?.groupValues?.get(1)?.trim()
                when {
                    inlineMerchant != null && plausibleMerchant(inlineMerchant) -> inlineMerchant
                    plausibleMerchant(line) && body.getOrNull(index + 1)?.let { positivePrice.matches(it) } == true -> line
                    else -> null
                }
            }.distinct()
            return AlipayPageStructure(fields, listOfNotNull(primary), merchants)
        }

        private fun readDetail(lines: List<String>, detailIndex: Int): AlipayPageStructure {
            val statusIndex = lines.indexOfFirstFrom(detailIndex + 1) { it == "交易成功" || it == "支付成功" }
            val firstField = lines.indexOfFirstFrom(detailIndex + 1) { detailField.containsMatchIn(it) }
            val headerEnd = listOf(statusIndex, firstField).filter { it >= 0 }.minOrNull() ?: lines.size
            val header = lines.subList(detailIndex + 1, headerEnd)
            val signedAmounts = header.filter { signedAmount.matches(it) }
            val incoming = signedAmounts.any { it.replace("¥", "").trim().startsWith('+') }
            val amounts = signedAmounts.filterNot { it.replace("¥", "").trim().startsWith('+') }.map {
                it.replace("¥", "").trim().removePrefix("-").removePrefix("−").trim()
            }
            val amountIndex = header.indexOfFirst { signedAmount.matches(it) }
            val merchantArea = if (amountIndex >= 0) header.take(amountIndex) else header
            val merchants = merchantArea.filter(::plausibleMerchant)
            val footer = lines.indexOfFirstFrom(headerEnd) { it == "账单管理" }
            return AlipayPageStructure(lines.take(if (footer < 0) lines.size else footer), amounts, merchants, incoming)
        }

        private fun plausibleMerchant(value: String): Boolean = value.length in 1..100 && value.any(Char::isLetter) &&
            !excludedMerchant.containsMatchIn(value) && !detailField.containsMatchIn(value) &&
            !monetaryToken.matches(value) && !paymentSuccess.containsMatchIn(value)

        private fun joinAmountNodes(lines: List<String>): List<String> = buildList {
            var index = 0
            while (index < lines.size) {
                val prefix = lines[index]
                val next = lines.getOrNull(index + 1)
                val third = lines.getOrNull(index + 2)
                when {
                    prefix in amountPrefixes && next != null && unsignedNumber.matches(next) -> {
                        add(prefix + next); index += 2
                    }
                    prefix in setOf("-", "−", "+") && next == "¥" && third != null && unsignedNumber.matches(third) -> {
                        add(prefix + next + third); index += 3
                    }
                    else -> { add(prefix); index++ }
                }
            }
        }

        private fun List<String>.indexOfFirstFrom(start: Int, predicate: (String) -> Boolean): Int =
            (start until size).firstOrNull { predicate(this[it]) } ?: -1

        private val amountPrefixes = setOf("¥", "-", "−", "+", "-¥", "−¥", "+¥", "¥-", "¥−", "¥+")
        private const val NUMBER = "[0-9][0-9,.]*"
        private val unsignedNumber = Regex("$NUMBER(?:元)?")
        private val monetaryToken = Regex("^(?:[+\\-−]?\\s*¥?|¥\\s*[+\\-−]?)\\s*$NUMBER\\s*(?:元|人民币)?$")
        private val signedAmount = Regex("^(?:[+\\-−]\\s*¥?|¥\\s*[+\\-−])\\s*$NUMBER\\s*(?:元)?$")
        private val positivePrice = Regex("^(?:¥\\s*$NUMBER|$NUMBER\\s*元)$")
        private val merchantAndPrice = Regex("^(.+?)\\s+¥\\s*$NUMBER(?:元)?$")
        private val paymentSuccess = Regex("^(?:支付成功|付款成功)(?=$|[ :¥0-9])")
        private val paymentMethod = Regex("^(?:交易方式|付款方式|支付方式)(?:[: ]|$)")
        private val detailFields = listOf("支付时间|付款时间|交易时间", "付款方式|支付方式|交易方式", "商品说明", "收款方全称")
            .map { Regex("^(?:$it)(?:[: ]|$)") }
        private val detailField = Regex("^(?:支付时间|付款时间|交易时间|付款方式|支付方式|交易方式|商品说明|收款方全称|支付奖励|交易号|支付宝交易号|商户订单号)(?:[: ]|$)")
        private val promotionFooter = Regex("^(?:恭喜你获得|去领取|送你|广告|奖励一个|免费领|领取红包|优惠券入口|限时活动|活动推荐)")
        private val excludedMerchant = Regex("账单详情|全部账单|回首页|完成|交易成功|获得|能量|葵花籽|优惠|立减|红包|积分|奖励|领取|折扣|原价|订单金额|商品金额|交易方式|付款方式|支付方式|^(?:商家|商户|收款方)(?:[: ]|$)")
    }
}
