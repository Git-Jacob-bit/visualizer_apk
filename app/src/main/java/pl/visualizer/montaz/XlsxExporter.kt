package pl.visualizer.montaz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max

/** Visualisation workbook: a blank step template plus every photo on the same white square, ready to paste. */
object XlsxExporter {
    private const val SQUARE_PX = 1000
    private const val SPARE_BLOCKS = 3
    private const val ASSEMBLY_BLOCKS = 5
    private val numberPattern = Regex("""\d+""")

    fun export(project: Project, store: ProjectStore, output: OutputStream, lang: Lang = Lang.PL) {
        require(project.photos.isNotEmpty()) { lang.tr("Projekt nie zawiera zdjęć.", "The project has no photos.") }
        val ordered = project.photos.withIndex().sortedWith(compareBy({ firstStep(it.value) ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        val photos = ordered.map { XlsxPhoto(square(project, it, store), it.pn, it.steps) }
        val name = project.name.ifBlank { lang.tr("Projekt", "Project") }
        XlsxWriter.write(output, templateSteps(project.photos), ASSEMBLY_BLOCKS, photos, XlsxTexts(
            visualSheet = lang.tr("wizualizacje", "visualisations"),
            photoSheet = lang.tr("zdjęcia", "photos"),
            title = name,
            photoTitle = lang.tr("$name — zdjęcia", "$name — photos"),
            previous = lang.tr("Poprzedni komponent", "Previous component"),
            current = lang.tr("Aktualny komponent", "Current component"),
            description = lang.tr("Opis", "Description"),
            assembly = lang.tr("Montaż przewodu", "Cable assembly"),
            componentNo = lang.tr("Nr komponentu", "Component no."),
            sensorNo = lang.tr("Nr czujnika", "Sensor no."),
            stepNo = lang.tr("Nr kroku", "Step no."),
            stepPrefix = lang.tr("Krok:", "Step:"),
        ))
    }

    /** Every step number written on a photo, in order; without any, one numbered block per photo. Spares follow. */
    internal fun templateSteps(photos: List<PhotoItem>): List<Int?> {
        val numbers = photos.flatMap { photo -> numberPattern.findAll(photo.steps).mapNotNull { it.value.toIntOrNull() }.toList() }.distinct().sorted()
        val steps = numbers.ifEmpty { (1..photos.size).toList() }
        return steps + List(SPARE_BLOCKS) { null }
    }

    private fun firstStep(photo: PhotoItem) = numberPattern.find(photo.steps)?.value?.toIntOrNull()

    // The framing chosen in the editor, with its brightness/contrast, centred on white; nothing is cut away.
    private fun square(project: Project, photo: PhotoItem, store: ProjectStore): ByteArray {
        val bitmap = ImageTools.load(store.photoFile(project.id, photo), (SQUARE_PX * photo.cropZoom).toInt().coerceAtLeast(SQUARE_PX))
        val canvasBitmap = Bitmap.createBitmap(SQUARE_PX, SQUARE_PX, Bitmap.Config.ARGB_8888)
        try {
            val source = ImageTools.cropRect(bitmap.width, bitmap.height, photo.widthMm / photo.heightMm, photo.cropZoom, photo.cropX, photo.cropY)
            val cropped = Bitmap.createBitmap(bitmap, source.left, source.top, source.width(), source.height())
            val processed = ImageTools.process(cropped, Adjustments(photo.brightness, photo.contrast))
            val scale = SQUARE_PX.toFloat() / max(processed.width, processed.height)
            val w = processed.width * scale
            val h = processed.height * scale
            Canvas(canvasBitmap).apply {
                drawColor(Color.WHITE)
                drawBitmap(processed, null, RectF((SQUARE_PX - w) / 2f, (SQUARE_PX - h) / 2f, (SQUARE_PX + w) / 2f, (SQUARE_PX + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            processed.recycle()
            if (cropped !== bitmap) cropped.recycle()
            return ByteArrayOutputStream().use { stream ->
                canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                stream.toByteArray()
            }
        } finally {
            canvasBitmap.recycle()
            bitmap.recycle()
        }
    }
}
