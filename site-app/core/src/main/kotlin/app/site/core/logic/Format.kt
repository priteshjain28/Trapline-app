package app.site.core.logic

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Formatting that screens and reports share. Locale-aware text lives in Android resources; these are the numbers. */
object Format {
    /** ₹1,19,999 (Indian grouping). Paise shown only when there are any: ₹1,234.50 */
    fun rupees(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val p = abs(paise)
        val whole = indianGroup(p / 100)
        val frac = p % 100
        return if (frac == 0L) "$sign₹$whole" else "$sign₹$whole.${frac.toString().padStart(2, '0')}"
    }

    /** 1,19,999 */
    fun indianGroup(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$rest,$last3"
    }

    /** "12", "12.5", "0.25": no trailing zeros, at most 3 decimals. */
    fun qty(q: Double): String {
        val r = Math.round(q * 1000) / 1000.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    /** Parse what people type for money: "800", "800.5", "1,200" -> paise. Null if not a number. */
    /** For a form field: "8000" or "8000.5", no symbol or grouping; parseRupees reads it back. */
    fun rupeesPlain(paise: Long): String {
        val r = paise / 100
        val p = paise % 100
        return if (p == 0L) r.toString() else "$r." + p.toString().padStart(2, '0').trimEnd('0')
    }

    fun parseRupees(text: String): Long? {
        val clean = text.replace(",", "").replace("₹", "").trim()
        if (clean.isEmpty()) return null
        val v = clean.toBigDecimalOrNull() ?: return null
        if (v.signum() < 0) return null
        return v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    }

    fun parseQty(text: String): Double? = text.replace(",", "").trim().toDoubleOrNull()?.takeIf { it >= 0 }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val longFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun day(d: LocalDate, locale: Locale = Locale.ENGLISH): String = d.format(dayFmt.withLocale(locale))
    fun short(d: LocalDate, locale: Locale = Locale.ENGLISH): String = d.format(shortFmt.withLocale(locale))
    fun long(d: LocalDate, locale: Locale = Locale.ENGLISH): String = d.format(longFmt.withLocale(locale))

    /** "1 Oct – 31 Mar", or with years when they differ from [today]'s. */
    fun range(from: LocalDate, to: LocalDate, today: LocalDate, locale: Locale = Locale.ENGLISH): String {
        if (from == to) return long(from, locale)
        val sameYear = from.year == to.year && to.year == today.year
        return if (sameYear) "${short(from, locale)} – ${short(to, locale)}" else "${long(from, locale)} – ${long(to, locale)}"
    }

    fun initials(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "?" }
}
