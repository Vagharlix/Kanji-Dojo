package ua.syt0r.kanji.presentation.common.resources.string

val Int.withLeading0: String
    get() = if (this >= 10) toString() else "0$this"
/** Formats [value] with [decimals] digits after the decimal point (multiplatform friendly). */
fun formatDecimal(value: Double, decimals: Int = 1): String {
    if (value.isNaN() || value.isInfinite()) return "-"
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val rounded = kotlin.math.round(kotlin.math.abs(value) * factor).toLong()
    val sign = if (value < 0 && rounded != 0L) "-" else ""
    val whole = rounded / factor
    if (decimals == 0) return "$sign$whole"
    val fraction = (rounded % factor).toString().padStart(decimals, '0')
    return "$sign$whole.$fraction"
}

/** 0.9123 -> "91.2%" */
fun formatPercent(ratio: Double?, decimals: Int = 1): String {
    return ratio?.let { formatDecimal(it * 100, decimals) + "%" } ?: "-"
}

/** Human friendly stability/interval: days below 30, months below a year, years above. */
fun formatStatsDays(days: Double, dayUnit: String, monthUnit: String, yearUnit: String): String {
    return when {
        days < 1 -> formatDecimal(days, 2) + dayUnit
        days < 30 -> formatDecimal(days, 1) + dayUnit
        days < 365 -> formatDecimal(days / 30.4, 1) + monthUnit
        else -> formatDecimal(days / 365.0, 1) + yearUnit
    }
}
