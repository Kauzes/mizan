package dev.kauzes.mizan.merchant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyInputTest {

    @Test
    fun `whole and fractional amounts become minor units exactly`() {
        assertEquals(12500L, MoneyInput.minorUnits("125"))
        assertEquals(12550L, MoneyInput.minorUnits("125.50"))
        assertEquals(12550L, MoneyInput.minorUnits("125.5"))
        assertEquals(50L, MoneyInput.minorUnits(".50"))
    }

    @Test
    fun `the amount floating point gets wrong is right`() {
        // 8.29 * 100 in a Double is 828.9999999999999, which a truncation sends as 828.
        assertEquals(829L, MoneyInput.minorUnits("8.29"))
    }

    @Test
    fun `a comma is a decimal separator too`() {
        assertEquals(12550L, MoneyInput.minorUnits("125,50"))
    }

    @Test
    fun `anything that is not an amount is refused rather than guessed`() {
        listOf("", " ", "0", "0.00", "12.345", "1.2.3", "12a", "-5", "1,250.00", ".").forEach {
            assertNull("'$it' should not be an amount", MoneyInput.minorUnits(it))
        }
    }

    @Test
    fun `minor units read back the way a merchant writes them`() {
        assertEquals("125.50", MoneyInput.format(12550))
        assertEquals("0.05", MoneyInput.format(5))
        assertEquals("1250.00", MoneyInput.format(125000))
    }
}
