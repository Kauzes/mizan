package dev.kauzes.mizan.merchant.domain

/**
 * An amount as a merchant types it, into minor units, without floating point.
 *
 * The platform carries money as a whole number of minor units and never a decimal (the design rules). A
 * phone that parsed "8.29" as a Double and multiplied by a hundred would send 828, which is how the
 * console found this once. So the digits are handled as digits.
 *
 * Accepts either a point or a comma as the decimal separator, because a merchant in Türkiye types a comma,
 * with at most [fractionDigits] after it. Anything else, and zero, is not an amount.
 */
object MoneyInput {

    fun minorUnits(typed: String, fractionDigits: Int = 2): Long? {
        val text = typed.trim()
        if (text.isEmpty()) return null
        val parts = text.split('.', ',')
        if (parts.size > 2) return null

        val whole = parts[0]
        val fraction = parts.getOrNull(1) ?: ""
        if (whole.isEmpty() && fraction.isEmpty()) return null
        if (!whole.all(Char::isDigit) || !fraction.all(Char::isDigit)) return null
        if (fraction.length > fractionDigits) return null
        if (whole.length > 13) return null

        val minor = (whole.ifEmpty { "0" } + fraction.padEnd(fractionDigits, '0')).toLong()
        return minor.takeIf { it > 0 }
    }

    /** Minor units back into what a merchant reads: 12550 as "125.50". */
    fun format(minor: Long, fractionDigits: Int = 2): String {
        val digits = minor.toString().padStart(fractionDigits + 1, '0')
        return digits.dropLast(fractionDigits) + "." + digits.takeLast(fractionDigits)
    }
}
