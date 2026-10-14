package app.site.core

import app.site.core.data.AppError
import app.site.core.data.fake.FakeBackend
import app.site.core.logic.Stock
import app.site.core.logic.Validate
import app.site.core.model.AdjustmentKind
import app.site.core.model.Material
import app.site.core.model.MaterialLine
import app.site.core.model.NewAdjustment
import app.site.core.model.NewGrn
import app.site.core.model.NewMaterial
import app.site.core.model.NewMaterialIssue
import app.site.core.model.NewProject
import app.site.core.model.NewTask
import app.site.core.model.StockEntryKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T06:30:00Z"), ZoneId.of("Asia/Kolkata"))

    private suspend fun signedIn(): Triple<FakeBackend, String, String> {
        val b = FakeBackend(clock, latencyMs = 0, seed = false)
        b.verifyOtp("9812345678", FakeBackend.DEMO_OTP).getOrThrow()
        val p = b.create(NewProject("Tower")).getOrThrow()
        val t = b.create(NewTask(p.id, "Brickwork")).getOrThrow()
        return Triple(b, p.id, t.id)
    }

    private fun reason(r: Result<*>) = (r.exceptionOrNull() as AppError.Invalid).reason

    @Test fun stockIsInMinusOutPlusCorrections() = runTest {
        val (b, p, t) = signedIn()
        val cement = b.addMaterial(NewMaterial("Cement", "OPC 53", "bag")).getOrThrow()
        b.adjust(NewAdjustment(p, cement.id, AdjustmentKind.OPENING, 40.0, b.today())).getOrThrow()
        b.receive(NewGrn(p, b.today(), listOf(MaterialLine(cement.id, 200.0)), challanNo = "CH-1")).getOrThrow()
        b.issue(NewMaterialIssue(p, t, b.today(), listOf(MaterialLine(cement.id, 60.0)))).getOrThrow()
        b.adjust(NewAdjustment(p, cement.id, AdjustmentKind.USED, 5.5, b.today())).getOrThrow()
        assertEquals(174.5, b.stock(p).first().single().inStock)

        // a count records only the difference
        b.adjust(NewAdjustment(p, cement.id, AdjustmentKind.COUNTED, 170.0, b.today())).getOrThrow()
        assertEquals(170.0, b.stock(p).first().single().inStock)
        val kinds = b.history(p, cement.id).first().map { it.kind }.toSet()
        assertEquals(setOf(StockEntryKind.OPENING, StockEntryKind.RECEIVED, StockEntryKind.ISSUED, StockEntryKind.USED, StockEntryKind.CORRECTED), kinds)
        assertEquals(-4.5, b.history(p, cement.id).first().first { it.kind == StockEntryKind.CORRECTED }.qty)
    }

    @Test fun aCountThatMatchesRecordsNothing() = runTest {
        val (b, p, _) = signedIn()
        val sand = b.addMaterial(NewMaterial("Sand", uom = "m3")).getOrThrow()
        b.receive(NewGrn(p, b.today(), listOf(MaterialLine(sand.id, 12.0)))).getOrThrow()
        b.adjust(NewAdjustment(p, sand.id, AdjustmentKind.COUNTED, 12.0, b.today())).getOrThrow()
        assertEquals(1, b.history(p, sand.id).first().size)
    }

    @Test fun cantIssueMoreThanTheSiteHolds() = runTest {
        val (b, p, t) = signedIn()
        val steel = b.addMaterial(NewMaterial("TMT bar", "12mm", "kg")).getOrThrow()
        b.receive(NewGrn(p, b.today(), listOf(MaterialLine(steel.id, 100.0)))).getOrThrow()
        val tooMuch = b.issue(NewMaterialIssue(p, t, b.today(), listOf(MaterialLine(steel.id, 100.5))))
        assertEquals(AppError.Reason.NOT_ENOUGH_STOCK, reason(tooMuch))
        assertEquals(AppError.Reason.NOT_ENOUGH_STOCK, reason(b.adjust(NewAdjustment(p, steel.id, AdjustmentKind.USED, 101.0, b.today()))))
        // all of it is fine
        b.issue(NewMaterialIssue(p, t, b.today(), listOf(MaterialLine(steel.id, 100.0)))).getOrThrow()
        assertEquals(0.0, b.stock(p).first().single().inStock)
    }

    @Test fun theSameMaterialTwiceIsOneLine() = runTest {
        val (b, p, t) = signedIn()
        val bricks = b.addMaterial(NewMaterial("Bricks", uom = "nos")).getOrThrow()
        b.receive(NewGrn(p, b.today(), listOf(MaterialLine(bricks.id, 1000.0)))).getOrThrow()
        // 600 + 500 is more than 1000 even though each line alone fits
        val r = b.issue(NewMaterialIssue(p, t, b.today(), listOf(MaterialLine(bricks.id, 600.0), MaterialLine(bricks.id, 500.0))))
        assertEquals(AppError.Reason.NOT_ENOUGH_STOCK, reason(r))
        val ok = b.issue(NewMaterialIssue(p, t, b.today(), listOf(MaterialLine(bricks.id, 600.0), MaterialLine(bricks.id, 300.0)))).getOrThrow()
        assertEquals(listOf(MaterialLine(bricks.id, 900.0)), ok.lines)
    }

    @Test fun documentsGetNumbersPerSite() = runTest {
        val (b, p, t) = signedIn()
        val m = b.addMaterial(NewMaterial("Cement", uom = "bag")).getOrThrow()
        assertEquals(1, b.receive(NewGrn(p, b.today(), listOf(MaterialLine(m.id, 10.0)))).getOrThrow().number)
        assertEquals(2, b.receive(NewGrn(p, b.today(), listOf(MaterialLine(m.id, 10.0)))).getOrThrow().number)
        assertEquals(1, b.issue(NewMaterialIssue(p, t, b.today(), listOf(MaterialLine(m.id, 5.0)))).getOrThrow().number)
        assertEquals(2, b.grns(p).first().size)
        assertEquals(1, b.issues(p).first().size)
    }

    @Test fun refusals() = runTest {
        val (b, p, t) = signedIn()
        val m = b.addMaterial(NewMaterial("Cement", uom = "bag")).getOrThrow()
        assertEquals(AppError.Reason.REQUIRED, reason(b.receive(NewGrn(p, b.today(), emptyList()))))
        assertEquals(AppError.Reason.NOT_POSITIVE, reason(b.receive(NewGrn(p, b.today(), listOf(MaterialLine(m.id, 0.0))))))
        assertEquals(AppError.Reason.NOT_POSITIVE, reason(b.receive(NewGrn(p, b.today(), listOf(MaterialLine(m.id, Double.NaN))))))
        assertEquals(AppError.Reason.IN_FUTURE, reason(b.receive(NewGrn(p, b.today().plusDays(1), listOf(MaterialLine(m.id, 1.0))))))
        assertEquals(AppError.Reason.REQUIRED, reason(b.issue(NewMaterialIssue(p, "no-such-task", b.today(), listOf(MaterialLine(m.id, 1.0))))))
        assertEquals(AppError.Reason.NOT_POSITIVE, reason(b.adjust(NewAdjustment(p, m.id, AdjustmentKind.OPENING, -3.0, b.today()))))
        assertTrue(b.stock(p).first().isEmpty())
        assertTrue(t.isNotEmpty())
    }

    @Test fun catalogueMergesTheSameMaterialTypedDifferently() = runTest {
        val (b, _, _) = signedIn()
        val a = b.addMaterial(NewMaterial("Cement", "OPC  53", "bag")).getOrThrow()
        val again = b.addMaterial(NewMaterial(" cement ", "opc 53", "Bag")).getOrThrow()
        assertEquals(a, again)
        assertEquals(1, b.catalogue().first().size)
        // another phone computes the same id offline
        assertEquals(a.id, Material.idFor(a.orgId, "CEMENT", "Opc 53", "BAG"))
        assertTrue(a.id != Material.idFor(a.orgId, "Cement", "OPC 43", "bag"))
    }

    @Test fun materialFieldsAreChecked() {
        assertEquals(AppError.Reason.REQUIRED, (Validate.material(NewMaterial("  ", uom = "bag")).exceptionOrNull() as AppError.Invalid).reason)
        assertIs<AppError.Invalid>(Validate.material(NewMaterial("Cement", uom = " ")).exceptionOrNull())
        assertIs<AppError.Invalid>(Validate.material(NewMaterial("x".repeat(81), uom = "bag")).exceptionOrNull())
        assertEquals("River sand", Validate.material(NewMaterial(" River   sand ", uom = "m3")).getOrThrow().name)
    }

    @Test fun stockRules() {
        assertNull(Stock.correction(12.0, 12.0002))
        assertEquals(-0.5, Stock.correction(12.0, 11.5))
        assertTrue(Stock.canIssue(listOf(MaterialLine("a", 10.0)), mapOf("a" to 10.0)).isSuccess)
        assertTrue(Stock.canIssue(listOf(MaterialLine("a", 10.01)), mapOf("a" to 10.0)).isFailure)
        assertTrue(Stock.canIssue(listOf(MaterialLine("b", 1.0)), mapOf("a" to 10.0)).isFailure)
    }

    @Test fun seedTowerHasStock() = runTest {
        val b = FakeBackend(clock, latencyMs = 0)
        val tower = b.projects().first().first { it.name == "Green Park Tower B" }
        val stock = b.stock(tower.id).first().associate { it.material.name to it.inStock }
        assertEquals(mapOf("Cement" to 135.0, "Red bricks" to 2800.0, "River sand" to 6.0, "TMT bar" to 1500.0), stock)
    }
}
