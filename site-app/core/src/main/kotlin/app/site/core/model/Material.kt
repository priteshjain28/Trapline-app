package app.site.core.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Material on site. Mirrors materials, grns(+lines), material_issues(+lines) and stock_adjustments in
 * supabase/migrations/20261014000000_init.sql. Stock is never stored: it is the sum of what came in, what went out
 * and corrections, the same rule as the server's stock_movements ledger. Quantities are in the material's unit.
 */

/** One entry in the organisation's catalogue: "cement", spec "OPC 53", unit "bag". */
data class Material(
    val id: String,
    val orgId: String,
    val name: String,
    val spec: String = "",
    val uom: String,
    val category: String? = null,
) {
    val label: String get() = if (spec.isBlank()) name else "$name · $spec"

    companion object {
        /**
         * The same material typed on two phones gets the same id, so offline adds merge instead of clashing on the
         * catalogue's (org, name, spec, unit) key. Case and spacing don't matter.
         */
        fun idFor(orgId: String, name: String, spec: String, uom: String): String {
            fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
            return UUID.nameUUIDFromBytes("material|$orgId|${norm(name)}|${norm(spec)}|${norm(uom)}".toByteArray()).toString()
        }
    }
}

data class NewMaterial(val name: String, val spec: String = "", val uom: String, val category: String? = null)

/** A material and how much of it this site holds. */
data class StockItem(val material: Material, val inStock: Double, val lastMovementOn: LocalDate?)

data class MaterialLine(val materialId: String, val qty: Double)

/** Goods received at site (a "Site GRN": no purchase order behind it). */
data class Grn(
    val id: String,
    val projectId: String,
    /** Per-project number, assigned when the row reaches the server. Null while pending. */
    val number: Int?,
    val receivedOn: LocalDate,
    val challanNo: String? = null,
    val notes: String? = null,
    val receivedBy: String? = null,
    val lines: List<MaterialLine>,
    val createdAt: Instant,
)

data class NewGrn(
    val projectId: String,
    val receivedOn: LocalDate,
    val lines: List<MaterialLine>,
    val challanNo: String? = null,
    val notes: String? = null,
)

/** Material handed out for a task. */
data class MaterialIssue(
    val id: String,
    val projectId: String,
    val number: Int?,
    val taskId: String,
    val issuedOn: LocalDate,
    val issuedBy: String? = null,
    val lines: List<MaterialLine>,
    val createdAt: Instant,
)

data class NewMaterialIssue(val projectId: String, val taskId: String, val issuedOn: LocalDate, val lines: List<MaterialLine>)

enum class AdjustmentKind {
    /** What was already on site when you started using the app. */
    OPENING,
    /** Used up without a formal issue (e.g. cement into a slab). */
    USED,
    /** You counted the stock: [NewAdjustment.qty] is the count, and the difference is recorded. */
    COUNTED,
}

data class NewAdjustment(
    val projectId: String,
    val materialId: String,
    val kind: AdjustmentKind,
    val qty: Double,
    val date: LocalDate,
    val taskId: String? = null,
    val note: String? = null,
)

enum class StockEntryKind { RECEIVED, ISSUED, OPENING, USED, CORRECTED }

/** One line in a material's history on a site. [qty] is signed: + came in, - went out. */
data class StockEntry(
    val id: String,
    val kind: StockEntryKind,
    val qty: Double,
    val date: LocalDate,
    val number: Int? = null,
    val taskId: String? = null,
    val note: String? = null,
    val createdAt: Instant,
)
