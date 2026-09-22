package pl.visualizer.montaz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Everything applied to a photo's pixels, in order: brightness/contrast, greyscale, printer midtone lift, sharpening. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val midtones: Float = 0f,
    val monochrome: Boolean = false,
    val sharpen: Float = 0f,
) {
    val isIdentity get() = brightness == 0f && contrast == 1f && midtones == 0f && !monochrome && sharpen == 0f
}

/** The photo file is missing or damaged; the UI words the message in the current language. */
class UnreadablePhotoException : IllegalStateException("Photo file cannot be decoded")

object ImageTools {
    // Sharpening radius as a physical size, so it looks the same on a 20 mm and a 150 mm print.
    private const val SHARPEN_RADIUS_MM = 0.08f

    fun size(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val orientation = ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        return if (orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270)) {
            options.outHeight to options.outWidth
        } else {
            options.outWidth to options.outHeight
        }
    }

    fun load(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadablePhotoException()
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw UnreadablePhotoException()

        val orientation = ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        if (matrix.isIdentity) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Visible window of a [width]×[height] image for a print of [ratio] (w/h). At zoom 1 the window is the largest
     * centred crop; [zoom] shrinks it and [cx]/[cy] (−1…1) move it across the whole image.
     */
    fun cropRect(width: Int, height: Int, ratio: Float, zoom: Float, cx: Float, cy: Float): Rect {
        val (fractionW, fractionH) = cropFraction(width, height, ratio, zoom)
        val windowW = width * fractionW
        val windowH = height * fractionH
        val centerX = width / 2f + cx.coerceIn(-1f, 1f) * (width - windowW) / 2f
        val centerY = height / 2f + cy.coerceIn(-1f, 1f) * (height - windowH) / 2f
        val left = (centerX - windowW / 2f).roundToInt().coerceIn(0, width - 1)
        val top = (centerY - windowH / 2f).roundToInt().coerceIn(0, height - 1)
        return Rect(left, top, (left + windowW.roundToInt()).coerceIn(left + 1, width), (top + windowH.roundToInt()).coerceIn(top + 1, height))
    }

    /** Window size as a fraction of the image width and height. */
    fun cropFraction(width: Int, height: Int, ratio: Float, zoom: Float): Pair<Float, Float> {
        val imageRatio = width.toFloat() / height
        val (baseW, baseH) = if (imageRatio > ratio) (ratio / imageRatio) to 1f else 1f to (imageRatio / ratio)
        val z = zoom.coerceAtLeast(1f)
        return baseW / z to baseH / z
    }

    fun process(source: Bitmap, adjustments: Adjustments, sharpenRadiusPx: Int = 1, checkCancel: () -> Unit = {}): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        if (!adjustments.isIdentity) {
            val lut = toneCurve(adjustments)
            for (y in 0 until height) {
                if (y % 64 == 0) checkCancel()
                val row = y * width
                for (x in 0 until width) {
                    val color = pixels[row + x]
                    var r = color shr 16 and 0xFF
                    var g = color shr 8 and 0xFF
                    var b = color and 0xFF
                    if (adjustments.monochrome) {
                        val luma = (r * 77 + g * 150 + b * 29) shr 8
                        r = luma; g = luma; b = luma
                    }
                    pixels[row + x] = (color and -0x1000000) or (lut[r] shl 16) or (lut[g] shl 8) or lut[b]
                }
            }
            if (adjustments.sharpen > 0f) sharpen(pixels, width, height, sharpenRadiusPx.coerceIn(1, 6), adjustments.sharpen, checkCancel)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun sharpenRadius(pixelsAcross: Int, millimetresAcross: Float) = (pixelsAcross / millimetresAcross * SHARPEN_RADIUS_MM).roundToInt().coerceAtLeast(1)

    /** 256-entry curve: linear brightness/contrast, then a gamma lift that counters printers darkening midtones. */
    fun toneCurve(adjustments: Adjustments) = IntArray(256) { value ->
        val linear = (adjustments.contrast * value + adjustments.brightness * 255f + (1f - adjustments.contrast) * 127.5f).coerceIn(0f, 255f)
        val lifted = if (adjustments.midtones > 0f) 255f * (linear / 255f).pow(1f / (1f + adjustments.midtones)) else linear
        lifted.roundToInt().coerceIn(0, 255)
    }

    // Unsharp mask: original + amount × (original − box blur), per channel.
    private fun sharpen(pixels: IntArray, width: Int, height: Int, radius: Int, amount: Float, checkCancel: () -> Unit) {
        val horizontal = IntArray(pixels.size)
        val blurred = IntArray(pixels.size)
        boxBlur(pixels, horizontal, width, height, radius, horizontalPass = true, checkCancel)
        boxBlur(horizontal, blurred, width, height, radius, horizontalPass = false, checkCancel)
        for (i in pixels.indices) {
            if (i % (width * 64) == 0) checkCancel()
            val color = pixels[i]
            val blur = blurred[i]
            val r = sharpenChannel(color shr 16 and 0xFF, blur shr 16 and 0xFF, amount)
            val g = sharpenChannel(color shr 8 and 0xFF, blur shr 8 and 0xFF, amount)
            val b = sharpenChannel(color and 0xFF, blur and 0xFF, amount)
            pixels[i] = (color and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun sharpenChannel(original: Int, soft: Int, amount: Float) = (original + amount * (original - soft)).roundToInt().coerceIn(0, 255)

    private fun boxBlur(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontalPass: Boolean, checkCancel: () -> Unit) {
        val lines = if (horizontalPass) height else width
        val length = if (horizontalPass) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            if (line % 64 == 0) checkCancel()
            val base = if (horizontalPass) line * width else line
            val stride = if (horizontalPass) 1 else width
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) {
                val c = input[base + k.coerceIn(0, length - 1) * stride]
                r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
            }
            for (p in 0 until length) {
                val here = base + p * stride
                output[here] = (input[here] and -0x1000000) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val outgoing = input[base + (p - radius).coerceIn(0, length - 1) * stride]
                val incoming = input[base + (p + radius + 1).coerceIn(0, length - 1) * stride]
                r += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
                g += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }

    /** Brightness and contrast that stretch the visible region's 0.5–99.5 % luminance range, within the slider limits. */
    fun autoLevels(bitmap: Bitmap, region: Rect): Pair<Float, Float> {
        val histogram = IntArray(256)
        val step = max(1, max(region.width(), region.height()) / 400)
        var count = 0
        for (y in region.top until region.bottom step step) for (x in region.left until region.right step step) {
            val c = bitmap.getPixel(x, y)
            histogram[((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8]++
            count++
        }
        fun percentile(fraction: Float): Int {
            var sum = 0
            for (v in 0..255) { sum += histogram[v]; if (sum >= count * fraction) return v }
            return 255
        }
        val low = percentile(0.005f)
        val high = percentile(0.995f).coerceAtLeast(low + 1)
        val contrast = (255f / (high - low)).coerceIn(0.5f, 1.5f)
        val brightness = ((-contrast * low - (1f - contrast) * 127.5f) / 255f).coerceIn(-0.4f, 0.4f)
        return brightness to contrast
    }

    fun effectiveDpi(file: File, widthMm: Float, heightMm: Float): Int {
        val (width, height) = size(file)
        if (width <= 0 || height <= 0) return 0
        return minOf((width * 25.4f / widthMm).toInt(), (height * 25.4f / heightMm).toInt())
    }
}
