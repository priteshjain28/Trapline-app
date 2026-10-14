package app.site.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.site.R
import app.site.core.logic.Format
import app.site.core.logic.ReportContent
import app.site.core.model.ReportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Draws a [ReportContent] to an A4 PDF with Android's own text engine (which shapes Devanagari and other
 * Indian scripts correctly). Stand-in for the server-side HTML -> PDF worker in architecture.md.
 * Colours here are print colours (black on white) on purpose: reports are printed and forwarded.
 */
class PdfReportRenderer(private val context: Context) {
    private val pageW = 595
    private val pageH = 842
    private val margin = 40f
    private val contentW get() = (pageW - 2 * margin).toInt()

    private fun paint(size: Float, bold: Boolean = false, grey: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        color = if (grey) Color.rgb(0x4a, 0x57, 0x65) else Color.rgb(0x10, 0x18, 0x20)
    }

    private val title = paint(18f, bold = true)
    private val h2 = paint(13f, bold = true)
    private val body = paint(10.5f)
    private val muted = paint(9.5f, grey = true)
    private val rulePaint = Paint().apply { color = Color.rgb(0xd7, 0xdc, 0xe2); strokeWidth = 0.8f }

    suspend fun render(reportId: String, content: ReportContent): File = withContext(Dispatchers.IO) {
        val out = File(File(context.filesDir, "reports").apply { mkdirs() }, "$reportId.pdf")
        val doc = PdfDocument()
        val w = Writer(doc)
        try {
            header(w, content)
            when {
                content.isEmpty -> w.text(s(R.string.report_pdf_nothing), body)
                content.type == ReportType.DETAILED -> detailed(w, content)
                else -> summary(w, content)
            }
            material(w, content)
            w.finish()
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        out
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun header(w: Writer, c: ReportContent) {
        w.text(c.project.name, title)
        val kind = s(if (c.type == ReportType.DETAILED) R.string.report_type_detailed else R.string.report_type_summary)
        val period = if (c.from == c.to) Format.long(c.from) else "${Format.long(c.from)} – ${Format.long(c.to)}"
        w.text("$kind · $period", muted)
        c.project.address?.let { w.text(it, muted) }
        w.gap(6f)
        w.rule()
        val t = c.totals
        val parts = buildList {
            if (c.parts.taskUpdates) add(s(R.string.report_pdf_total_updates, t.updates))
            if (c.parts.photos) add(s(R.string.report_pdf_total_photos, t.photos))
            if (c.parts.attendance) add(s(R.string.report_pdf_total_worker_days, Format.qty(t.workerDays)))
            if (c.parts.wages) add(s(R.string.report_pdf_total_wages, Format.rupees(t.wagesPaise)))
        }
        if (parts.isNotEmpty()) w.text(parts.joinToString("   ·   "), body)
        w.gap(10f)
    }

    private fun detailed(w: Writer, c: ReportContent) {
        for (day in c.days) {
            w.ensure(60f)
            w.text(Format.day(day.date), h2)
            day.attendance?.let { a ->
                val ot = if (a.otHours > 0) " · " + s(R.string.report_pdf_ot_hours, Format.qty(a.otHours)) else ""
                w.text(s(R.string.report_pdf_headcount, a.present, a.halfDay, a.absent) + ot, muted)
            }
            for (u in day.updates) {
                val qty = u.qtyDone?.let { " — ${Format.qty(it)}${u.unit?.let { un -> " $un" } ?: ""}" }
                    ?: u.progressPct?.let { " — $it%" } ?: ""
                val num = u.taskNumber?.let { "#$it " } ?: ""
                w.text("• $num${u.taskName}$qty", body)
                val sub = listOfNotNull(u.remarks?.let { "“$it”" }, u.author.ifBlank { null }).joinToString(" — ")
                if (sub.isNotEmpty()) w.text(sub, muted, indent = 10f)
            }
            if (day.photos.isNotEmpty()) w.photos(day.photos)
            w.gap(8f)
            w.rule()
            w.gap(8f)
        }
    }

    private fun material(w: Writer, c: ReportContent) {
        if (c.materials.isEmpty()) return
        w.ensure(60f)
        w.gap(6f)
        w.text(s(R.string.report_pdf_material), h2)
        for (m in c.materials) {
            w.text("• ${m.name} — " + s(R.string.report_pdf_material_row, Format.qty(m.received), Format.qty(m.out), Format.qty(m.onSiteNow), m.uom), body)
        }
    }

    private fun summary(w: Writer, c: ReportContent) {
        if (c.taskSummary.isNotEmpty()) {
            w.text(s(R.string.report_pdf_work_done), h2)
            for (t in c.taskSummary) {
                val num = t.taskNumber?.let { "#$it " } ?: ""
                val qty = if (t.qtyInPeriod > 0) "${Format.qty(t.qtyInPeriod)}${t.unit?.let { " $it" } ?: ""} · " else ""
                w.text("• $num${t.taskName} — $qty${s(R.string.report_pdf_progress_now, t.progressNow)}", body)
            }
            w.gap(10f)
        }
        if (c.workerSummary.isNotEmpty()) {
            w.text(s(R.string.report_pdf_labour), h2)
            for (r in c.workerSummary) {
                val wages = if (c.parts.wages) " · ${Format.rupees(r.wagesPaise)}" else ""
                val ot = if (r.otHours > 0) " · " + s(R.string.report_pdf_ot_hours, Format.qty(r.otHours)) else ""
                w.text("• ${r.name}${r.skill?.let { " ($it)" } ?: ""} — ${s(R.string.report_pdf_days, Format.qty(r.days))}$ot$wages", body)
            }
        }
    }

    /** Flowing writer: wraps text, starts new pages, adds page footers. */
    private inner class Writer(private val doc: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page = newPage()
        private var canvas: Canvas = page.canvas
        private var y = margin

        private fun newPage(): PdfDocument.Page {
            pageNo += 1
            return doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        }

        private fun footer() {
            canvas.drawText(s(R.string.report_pdf_page, pageNo), margin, pageH - 20f, muted)
        }

        fun ensure(h: Float) {
            if (y + h > pageH - margin - 20f) {
                footer()
                doc.finishPage(page)
                page = newPage()
                canvas = page.canvas
                y = margin
            }
        }

        fun text(t: String, p: TextPaint, indent: Float = 0f) {
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, p, (contentW - indent).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
            ensure(layout.height.toFloat())
            canvas.save()
            canvas.translate(margin + indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 3f
        }

        fun gap(h: Float) { y += h }

        fun rule() {
            ensure(4f)
            canvas.drawLine(margin, y, pageW - margin, y, rulePaint)
            y += 4f
        }

        fun photos(uris: List<String>) {
            val cols = 4
            val gapX = 8f
            val cellW = (contentW - gapX * (cols - 1)) / cols
            val cellH = cellW * 0.75f
            uris.chunked(cols).forEach { row ->
                ensure(cellH + 6f)
                row.forEachIndexed { i, uri ->
                    val left = margin + i * (cellW + gapX)
                    val dst = RectF(left, y, left + cellW, y + cellH)
                    val bmp = decode(Uri.parse(uri), 400)
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, centreCrop(bmp, dst), dst, null)
                        bmp.recycle()
                    } else {
                        canvas.drawRect(dst, rulePaint)
                    }
                }
                y += cellH + 6f
            }
        }

        fun finish() {
            footer()
            doc.finishPage(page)
        }
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun centreCrop(b: Bitmap, dst: RectF): Rect {
        val target = dst.width() / dst.height()
        val src = b.width.toFloat() / b.height
        return if (src > target) {
            val w = (b.height * target).toInt()
            val x = (b.width - w) / 2
            Rect(x, 0, x + w, b.height)
        } else {
            val h = (b.width / target).toInt()
            val yy = (b.height - h) / 2
            Rect(0, yy, b.width, yy + h)
        }
    }
}
