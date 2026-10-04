package pl.visualizer.montaz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Every photo as its own JPEG in the editor's framing, named PN_step, optionally with the printed labels. */
object ZipExporter {
    private const val LONG_SIDE_PX = 2000

    fun export(project: Project, store: ProjectStore, output: OutputStream, withLabels: Boolean, lang: Lang = Lang.PL) {
        require(project.photos.isNotEmpty()) { lang.tr("Projekt nie zawiera zdjęć.", "The project has no photos.") }
        val names = fileNames(project.photos, lang)
        ZipOutputStream(output).use { zip ->
            // JPEGs are already compressed; storing them uncompressed saves time without growing the file.
            zip.setLevel(0)
            project.photos.forEachIndexed { index, photo ->
                zip.putNextEntry(ZipEntry(names[index]))
                render(project, photo, store, withLabels, lang).let { bitmap ->
                    try { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, zip) } finally { bitmap.recycle() }
                }
                zip.closeEntry()
            }
        }
    }

    /** "PN_step.jpg"; a missing part is left out and repeated names get _2, _3… */
    internal fun fileNames(photos: List<PhotoItem>, lang: Lang): List<String> {
        val used = mutableMapOf<String, Int>()
        return photos.mapIndexed { index, photo ->
            val base = listOf(photo.pn, photo.steps).map(::part).filter { it.isNotEmpty() }.joinToString("_")
                .ifEmpty { lang.tr("zdjecie_${index + 1}", "photo_${index + 1}") }
            val count = (used[base] ?: 0) + 1
            used[base] = count
            (if (count == 1) base else "${base}_$count") + ".jpg"
        }
    }

    // Steps such as "6, 7" become "6-7": keeps the underscore free as the PN/step separator.
    private fun part(text: String) = text.trim().replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-').take(60)

    private fun render(project: Project, photo: PhotoItem, store: ProjectStore, withLabels: Boolean, lang: Lang): Bitmap {
        val bitmap = ImageTools.load(store.photoFile(project.id, photo), (LONG_SIDE_PX * photo.cropZoom).toInt())
        try {
            val source = ImageTools.cropRect(bitmap.width, bitmap.height, photo.widthMm / photo.heightMm, photo.cropZoom, photo.cropX, photo.cropY)
            val cropped = Bitmap.createBitmap(bitmap, source.left, source.top, source.width(), source.height())
            val processed = ImageTools.process(cropped, Adjustments(photo.brightness, photo.contrast))
            if (cropped !== bitmap) cropped.recycle()
            val scale = (LONG_SIDE_PX.toFloat() / max(processed.width, processed.height)).coerceAtMost(1f)
            val result = Bitmap.createBitmap((processed.width * scale).roundToInt(), (processed.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
            Canvas(result).apply {
                drawBitmap(processed, null, RectF(0f, 0f, result.width.toFloat(), result.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                if (withLabels) {
                    // Draw in print points so the labels keep the same proportion to the photo as on the cut-out sheet.
                    val box = RectF(0f, 0f, PdfExporter.pt(photo.widthMm), PdfExporter.pt(photo.heightMm))
                    scale(result.width / box.width(), result.height / box.height())
                    if (photo.pn.isNotBlank()) PdfExporter.drawLabel(this, photo.pn, box, photo.pnCorner, photo.pnScale, photo.pnColor, lang)
                    if (photo.steps.isNotBlank()) PdfExporter.drawLabel(this, photo.steps, box, photo.stepsCorner, photo.stepsScale, PdfExporter.stepsYellow, lang)
                }
            }
            processed.recycle()
            return result
        } finally {
            bitmap.recycle()
        }
    }
}
