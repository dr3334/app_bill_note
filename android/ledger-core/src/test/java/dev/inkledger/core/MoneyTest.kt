package dev.inkledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun decimalInputNeverPassesThroughFloatingPoint() {
        assertEquals(29L, Money.parseMinor("0.29"))
        assertEquals(12345678990L, Money.parseMinor("123456789.90"))
        assertEquals(1200L, Money.parseMinor(" 12 "))
        assertEquals("0.29", Money.formatMinor(29L))
        assertEquals("-0.01", Money.formatMinor(-1L))
    }

    @Test fun unsupportedPrecisionAndOverflowAreRejectedInsteadOfRounded() {
        listOf("0", "-12", "1.005", "NaN", "1e3", "¥10", "1,000", "", "92233720368547758.08")
            .forEach { assertNull(it, Money.parseMinor(it)) }
        assertNull(Money.parseMinor("92233720368547758.07"))
    }

    @Test fun productLimitIsTheSameForInputAndRepositoryValidation() {
        assertEquals(Money.MAX_AMOUNT_MINOR, Money.parseMinor("999999999.99"))
        assertNull(Money.parseMinor("1000000000.00"))
        val boundary = LedgerEntry("limit", "测试", Money.MAX_AMOUNT_MINOR, "其他",
            PaymentChannel.MANUAL, 1L, EntryState.CONFIRMED, null)
        assertNull(boundary.validationError())
        org.junit.Assert.assertNotNull(boundary.copy(amountMinor = Money.MAX_AMOUNT_MINOR + 1).validationError())
    }
}
