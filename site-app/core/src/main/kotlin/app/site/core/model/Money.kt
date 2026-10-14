package app.site.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * Money on a site (S23 payables, S26 expenses). Mirrors payables, payments and expenses in the schema.
 * Amounts are paise. Only managers and accountants see any of it (app.can_see_money on the server).
 */
enum class PayableKind { ATTENDANCE, CUSTOM }

enum class PayableStatus { DUE, PARTLY_PAID, PAID, VOID }

data class Payable(
    val id: String,
    val projectId: String,
    val number: Int?,
    val kind: PayableKind,
    val amountPaise: Long,
    val paidPaise: Long = 0,
    val status: PayableStatus = PayableStatus.DUE,
    /** Attendance payables: whose wages and for which days. */
    val workerId: String? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    /** Custom payables: what it's for. */
    val title: String? = null,
    val createdAt: Instant,
) {
    val duePaise: Long get() = (amountPaise - paidPaise).coerceAtLeast(0)
}

enum class PaymentMode { CASH, UPI, BANK, CHEQUE, OTHER }

data class Payment(
    val id: String,
    val payableId: String,
    val amountPaise: Long,
    val paidOn: LocalDate,
    val mode: PaymentMode = PaymentMode.CASH,
    val reference: String? = null,
    val createdAt: Instant,
)

/** What a worker would be paid for a period, from attendance not already on a payable. */
data class WagePreview(val worker: Worker, val days: Double, val otHours: Double, val amountPaise: Long)

/** Spend that isn't a bill to pay: petty cash, sundries, things bought to keep. */
enum class ExpenseCategory { PETTY_SPEND, MISCELLANEOUS, ASSETS }

data class Expense(
    val id: String,
    val projectId: String,
    val category: ExpenseCategory,
    val amountPaise: Long,
    val spentOn: LocalDate,
    val note: String? = null,
    val createdAt: Instant,
)

/** The six buckets of S26, each rupee counted once (the server's expense_lines view). */
enum class SpendCategory { MATERIAL, SUBCONTRACTOR, ATTENDANCE, PETTY_SPEND, MISCELLANEOUS, ASSETS }

data class SpendSummary(val byCategory: Map<SpendCategory, Long>) {
    val totalPaise: Long get() = byCategory.values.sum()
}
