package dev.inkledger.app.capture

/** Plain values keep OCR ordering testable without Android or a recognition model. */
internal data class AlipayOcrLine(val text: String, val top: Int?, val left: Int?)

/**
 * ML Kit block order can visit a right-hand price column before the main amount.
 * Order the individual lines visually; never merge prices, discounts or labels.
 * Unpositioned lines follow positioned lines in their original relative order.
 */
internal fun alipayOcrReadingOrder(lines: List<AlipayOcrLine>): List<String> =
    lines.sortedWith(compareBy<AlipayOcrLine> { it.top ?: Int.MAX_VALUE }
        .thenBy { it.left ?: Int.MAX_VALUE }).map { it.text }
