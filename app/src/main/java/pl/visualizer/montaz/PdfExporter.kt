package pl.visualizer.montaz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max

/**
 * Cut-out sheet: every photo at its exact physical size with a dashed cut line on its edge, corner crop marks
 * and a numbered caption, plus a 50 mm scale ruler in the header so a wrong print scale is caught before cutting.
 */
object PdfExporter {
    private const val PAGE_W = 595f // A4 in PostScript points
    private const val PAGE_H = 842f
    private fun pt(mm: Float) = mm * 72f / 25.4f
    private val margin = pt(12f)
    private val contentTop = pt(40f)
    private val contentBottom = PAGE_H - pt(14f)
    // Gaps leave room for the crop marks (1–4 mm outside each corner) so neighbours never share a mark.
    private val gapX = pt(9f)
    // The caption sits below the crop marks, so it may run into the gap beside a narrow piece.
    private val captionBand = pt(10f)
    private val gapY = pt(5f)
    private val ink = Color.rgb(32, 35, 31)
    private val grey = Color.rgb(102, 107, 97)
    private val stepsYellow = Color.rgb(255, 211, 48)

    data class Placement(val photo: PhotoItem, val box: RectF, val number: Int)

    fun layout(project: Project, lang: Lang = Lang.PL): List<List<Placement>> {
        require(project.photos.isNotEmpty()) { lang.tr("Projekt nie zawiera zdjęć.", "The project has no photos.") }
        val pages = mutableListOf<MutableList<Placement>>()
        var page = mutableListOf<Placement>()
        var x = margin
        var y = contentTop
        var rowHeight = 0f
        for ((index, photo) in project.photos.withIndex()) {
            val width = pt(photo.widthMm)
            val height = pt(photo.heightMm)
            require(width > 0f && height > 0f && width <= PAGE_W - 2 * margin && height + captionBand <= contentBottom - contentTop) {
                lang.tr("Zdjęcie ${mmText(photo.widthMm, photo.heightMm)} jest większe niż obszar druku A4.",
                    "The ${mmText(photo.widthMm, photo.heightMm)} photo is larger than the printable A4 area.")
            }
            val labelArea = RectF(0f, 0f, width, height)
            if (photo.pn.isNotBlank()) labelPaint(photo.pn, labelArea, lang)
            if (photo.steps.isNotBlank()) labelPaint(photo.steps, labelArea, lang)
            if (x > margin && x + width > PAGE_W - margin) {
                x = margin
                y += rowHeight + gapY
                rowHeight = 0f
            }
            if (y + height + captionBand > contentBottom) {
                pages += page
                page = mutableListOf()
                x = margin
                y = contentTop
                rowHeight = 0f
            }
            page += Placement(photo, RectF(x, y, x + width, y + height), index + 1)
            x += width + gapX
            rowHeight = max(rowHeight, height + captionBand)
        }
        pages += page
        return pages
    }

    fun export(project: Project, store: ProjectStore, output: OutputStream, profile: PrintProfile, lang: Lang = Lang.PL) {
        val pages = layout(project, lang)
        val document = PdfDocument()
        try {
            pages.forEachIndexed { pageIndex, placements ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageIndex + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val subtitle = listOf(
                    lang.tr("Wycinanka", "Cut-out sheet"),
                    lang.tr("arkusz ${pageIndex + 1} z ${pages.size}", "sheet ${pageIndex + 1} of ${pages.size}"),
                    lang.count(project.photos.size, "zdjęcie", "zdjęcia", "zdjęć", "photo", "photos"),
                    DateFormat.getDateInstance(DateFormat.MEDIUM, lang.locale).format(Date()),
                ).joinToString("   ·   ")
                drawHeader(canvas, project.name.ifBlank { lang.tr("Projekt", "Project") }, subtitle, lang)
                drawInstruction(canvas, lang.tr("Drukuj w skali 100%, bez dopasowania do strony. Tnij wzdłuż linii przerywanej, od znacznika do znacznika.",
                    "Print at 100% scale, without fit to page. Cut along the dashed line, from mark to mark."))
                placements.forEach { placement -> drawPiece(canvas, project, placement, store, profile, lang) }
                drawFooter(canvas, lang.tr("Arkusz ${pageIndex + 1} / ${pages.size}", "Sheet ${pageIndex + 1} / ${pages.size}"))
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun drawPiece(canvas: Canvas, project: Project, placement: Placement, store: ProjectStore, profile: PrintProfile, lang: Lang) {
        val photo = placement.photo
        val box = placement.box
        // Decode enough pixels for ~600 dpi inside the (possibly zoomed) crop window.
        val targetPixels = ceil(max(photo.widthMm, photo.heightMm) / 25.4f * 600f * photo.cropZoom).toInt().coerceAtLeast(500)
        val bitmap = ImageTools.load(store.photoFile(project.id, photo), targetPixels)
        try {
            val source = ImageTools.cropRect(bitmap.width, bitmap.height, box.width() / box.height(), photo.cropZoom, photo.cropX, photo.cropY)
            val cropped = Bitmap.createBitmap(bitmap, source.left, source.top, source.width(), source.height())
            val processed = ImageTools.process(cropped, profile.adjustments(photo, full = true), ImageTools.sharpenRadius(cropped.width, photo.widthMm))
            canvas.drawBitmap(processed, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
            processed.recycle()
            if (cropped !== bitmap) cropped.recycle()
        } finally {
            bitmap.recycle()
        }
        if (photo.pn.isNotBlank()) drawLabel(canvas, photo.pn, box, photo.pnCorner, Color.WHITE, lang)
        if (photo.steps.isNotBlank()) drawLabel(canvas, photo.steps, box, photo.stepsCorner, stepsYellow, lang)
        drawCutLine(canvas, box)
        drawCaption(canvas, placement)
    }

    // Numbered disc (matches the gallery number), then size and PN as far as the piece is wide.
    private fun drawCaption(canvas: Canvas, placement: Placement) {
        val box = placement.box
        val photo = placement.photo
        val diameter = pt(4.4f)
        val left = box.left
        val right = minOf(box.right + gapX - pt(2.5f), PAGE_W - margin)
        val centreY = box.bottom + pt(4.8f) + diameter / 2f
        canvas.drawCircle(left + diameter / 2f, centreY, diameter / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textSize = 6.2f; textAlign = Paint.Align.CENTER }
        canvas.drawText(placement.number.toString().padStart(2, '0'), left + diameter / 2f, centreY + 2.2f, number)
        var x = left + diameter + pt(1.2f)
        val size = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.DEFAULT_BOLD; textSize = 7.5f }
        val sizeText = mmText(photo.widthMm, photo.heightMm)
        if (x + size.measureText(sizeText) > right) return
        canvas.drawText(sizeText, x, centreY + 2.6f, size)
        x += size.measureText(sizeText) + pt(1.8f)
        if (photo.pn.isNotBlank()) {
            val pn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grey; textSize = 7f }
            val pnText = "PN ${photo.pn}"
            if (x + pn.measureText(pnText) <= right) canvas.drawText(pnText, x, centreY + 2.6f, pn)
        }
    }

    /** One A4 with grey steps printed through each midtone lift, to pick the value that suits a printer. */
    fun exportTestPage(output: OutputStream, profile: PrintProfile, lang: Lang = Lang.PL) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            drawHeader(canvas, lang.tr("Strona testowa drukarki", "Printer test page"),
                DateFormat.getDateInstance(DateFormat.MEDIUM, lang.locale).format(Date()), lang)
            drawInstruction(canvas, lang.tr("Wydrukuj w skali 100% na docelowej drukarce i papierze.", "Print at 100% scale on the target printer and paper."))
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 8.5f }
            val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
            var y = contentTop + pt(2f)
            listOf(
                lang.tr("Wybierz wiersz, w którym pola 80% i 90% są jeszcze wyraźnie różne, a przejścia wyglądają równo.",
                    "Pick the row where the 80% and 90% patches are still clearly different and the steps look even."),
                lang.tr("Ustaw tę wartość w aplikacji: Drukarka → Rozjaśnienie pod druk.", "Set that value in the app: Printer → Print brightening."),
            ).forEach { canvas.drawText(it, margin, y, text); y += pt(4.8f) }
            y += pt(5f)
            val labelWidth = pt(22f)
            val cell = (PAGE_W - 2 * margin - labelWidth) / 11f
            val cellHeight = pt(14f)
            val small = Paint(text).apply { textSize = 6.5f; color = grey }
            for (step in 0..10) canvas.drawText("${step * 10}%", margin + labelWidth + cell * step + 2f, y, small)
            y += pt(2f)
            PrintProfile.TEST_STEPS.forEach { lift ->
                val curve = ImageTools.toneCurve(Adjustments(midtones = lift))
                val current = kotlin.math.abs(lift - profile.midtones) < 0.001f
                canvas.drawText("${(lift * 100).toInt()}%${if (current) "  ◀" else ""}", margin, y + cellHeight / 2f + 3f, if (current) bold else text)
                for (step in 0..10) {
                    val value = curve[(255 * (1f - step / 10f)).toInt()]
                    canvas.drawRect(RectF(margin + labelWidth + cell * step, y, margin + labelWidth + cell * (step + 1), y + cellHeight), Paint().apply { color = Color.rgb(value, value, value) })
                }
                canvas.drawRect(RectF(margin + labelWidth, y, PAGE_W - margin, y + cellHeight), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 0.45f })
                y += cellHeight + pt(4f)
            }
            y += pt(6f)
            canvas.drawText(lang.tr("Drobny tekst i linie (czytelność małych wydruków)", "Small text and lines (legibility of small prints)"), margin, y, bold)
            y += pt(5f)
            listOf(4f, 5f, 6f, 7f).forEach { size ->
                canvas.drawText("PN 1234567890   S1, S2   ${lang.tr("rozmiar", "size")} ${size.toInt()} pt", margin, y, Paint(text).apply { textSize = size })
                y += size + pt(2f)
            }
            y += pt(3f)
            listOf(0.1f, 0.2f, 0.3f, 0.5f).forEachIndexed { index, width ->
                val lineY = y + index * pt(3f)
                canvas.drawLine(margin, lineY, margin + pt(60f), lineY, Paint().apply { color = ink; strokeWidth = pt(width) })
                canvas.drawText("${lang.decimal(width)} mm", margin + pt(64f), lineY + 2.5f, small)
            }
            drawFooter(canvas, lang.tr("Strona testowa", "Test page"))
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, photoBox: RectF, corner: String, background: Int, lang: Lang) {
        val padding = pt(0.65f)
        val paint = labelPaint(text, photoBox, lang)
        val width = paint.measureText(text) + 2 * padding
        val height = paint.fontMetrics.descent - paint.fontMetrics.ascent + 2 * padding
        val left = if (corner.endsWith("R")) photoBox.right - width else photoBox.left
        val top = if (corner.startsWith("B")) photoBox.bottom - height else photoBox.top
        val labelBox = RectF(left, top, left + width, top + height)
        canvas.drawRect(labelBox, Paint().apply { color = background })
        canvas.drawRect(labelBox, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 0.9f })
        canvas.drawText(text, left + padding, top + padding - paint.fontMetrics.ascent, paint)
    }

    private fun labelPaint(text: String, photoBox: RectF, lang: Lang): Paint {
        val padding = pt(0.65f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 10f
        }
        while (paint.measureText(text) > photoBox.width() - 2 * padding && paint.textSize > 5.5f) paint.textSize -= 0.5f
        val height = paint.fontMetrics.descent - paint.fontMetrics.ascent + 2 * padding
        require(paint.measureText(text) <= photoBox.width() - 2 * padding && height <= photoBox.height()) {
            lang.tr("Oznaczenie „$text” nie mieści się czytelnie na zdjęciu.", "The label “$text” does not fit legibly on the photo.")
        }
        return paint
    }

    // Dashed cut line exactly on the photo edge, plus solid crop marks 1–4 mm outside each corner for a paper cutter.
    private fun drawCutLine(canvas: Canvas, box: RectF) {
        canvas.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = Paint.Style.STROKE; strokeWidth = 0.6f
            pathEffect = DashPathEffect(floatArrayOf(pt(1.2f), pt(0.9f)), 0f)
        })
        val marks = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 0.5f }
        val near = pt(1f)
        val far = pt(4f)
        for (x in listOf(box.left, box.right)) {
            canvas.drawLine(x, box.top - far, x, box.top - near, marks)
            canvas.drawLine(x, box.bottom + near, x, box.bottom + far, marks)
        }
        for (y in listOf(box.top, box.bottom)) {
            canvas.drawLine(box.left - far, y, box.left - near, y, marks)
            canvas.drawLine(box.right + near, y, box.right + far, y, marks)
        }
    }

    private fun drawHeader(canvas: Canvas, title: String, subtitle: String, lang: Lang) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.DEFAULT_BOLD; textSize = 15f }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grey; textSize = 8f }
        val rulerLeft = PAGE_W - margin - pt(50f)
        var shown = title
        while (titlePaint.measureText(shown) > rulerLeft - margin - pt(8f) && shown.length > 4) shown = shown.dropLast(2) + "…"
        canvas.drawText(shown, margin, pt(17f), titlePaint)
        canvas.drawText(subtitle, margin, pt(23f), subPaint)
        drawRuler(canvas, rulerLeft, pt(15f), lang)
    }

    // 50 mm with millimetre ticks: measure it after printing to confirm the print was not scaled.
    private fun drawRuler(canvas: Canvas, left: Float, top: Float, lang: Lang) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 0.5f }
        canvas.drawLine(left, top, left + pt(50f), top, line.apply { strokeWidth = 0.8f })
        line.strokeWidth = 0.4f
        for (mm in 0..50) {
            val length = when { mm % 10 == 0 -> pt(3f); mm % 5 == 0 -> pt(2f); else -> pt(1.1f) }
            canvas.drawLine(left + pt(mm.toFloat()), top, left + pt(mm.toFloat()), top + length, line)
        }
        val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grey; textSize = 5.5f; textAlign = Paint.Align.CENTER }
        for (cm in 0..5) canvas.drawText("${cm * 10}", left + pt(cm * 10f), top + pt(5.4f), digits)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.DEFAULT_BOLD; textSize = 6.5f }
        canvas.drawText(lang.tr("Kontrola skali: 50 mm", "Scale check: 50 mm"), left, top - pt(1.6f), label)
    }

    private fun drawInstruction(canvas: Canvas, text: String) {
        val bar = RectF(margin, pt(28f), PAGE_W - margin, pt(34f))
        canvas.drawRoundRect(bar, pt(1.5f), pt(1.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 242, 234) })
        drawScissors(canvas, bar.left + pt(2.5f), bar.centerY())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 7.5f }
        var shown = text
        while (paint.measureText(shown) > bar.width() - pt(12f) && shown.length > 10) shown = shown.dropLast(2) + "…"
        canvas.drawText(shown, bar.left + pt(9f), bar.centerY() + 2.6f, paint)
    }

    private fun drawScissors(canvas: Canvas, x: Float, y: Float) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 0.8f }
        val r = pt(0.8f)
        canvas.drawCircle(x + r, y - pt(1.1f), r, stroke)
        canvas.drawCircle(x + r, y + pt(1.1f), r, stroke)
        val blades = Path().apply {
            moveTo(x + 2 * r, y - pt(0.8f)); lineTo(x + pt(4.6f), y + pt(1.2f))
            moveTo(x + 2 * r, y + pt(0.8f)); lineTo(x + pt(4.6f), y - pt(1.2f))
        }
        canvas.drawPath(blades, stroke)
    }

    private fun drawFooter(canvas: Canvas, sheet: String) {
        val y = PAGE_H - pt(7f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grey; textSize = 6.5f }
        canvas.drawText("frame / studio  ${BuildConfig.VERSION_NAME}", margin, y, paint)
        canvas.drawText(sheet, PAGE_W - margin - paint.measureText(sheet), y, paint)
    }
}
