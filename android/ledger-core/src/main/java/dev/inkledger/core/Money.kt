package dev.inkledger.core

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    /** Product limit for one CNY entry: 999,999,999.99 yuan. */
    const val MAX_AMOUNT_MINOR: Long = 99_999_999_999L

    /** CNY decimal input only. No floating-point conversion or silent rounding. */
    fun parseMinor(text: String): Long? {
        val value = text.trim()
        if (!value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) return null
        return try {
            BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact().takeIf { it in 1..MAX_AMOUNT_MINOR }
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun formatMinor(amountMinor: Long): String = BigDecimal.valueOf(amountMinor, 2).toPlainString()
}
