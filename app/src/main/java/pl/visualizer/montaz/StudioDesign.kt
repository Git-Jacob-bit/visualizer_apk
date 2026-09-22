package pl.visualizer.montaz

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal val StudioOrange = Color(0xFFD34F2F)
internal val StudioInk = Color(0xFF20231F)
internal val StudioPaper = Color(0xFFF7F7F2)
internal val LabelYellow = Color(0xFFFFD748)
internal val CameraBlack = Color(0xFF0C0E13)

internal fun dimensionText(value: Float) = if (value % 1f == 0f) value.toInt().toString() else value.toString()
internal fun mmText(width: Float, height: Float) = "${dimensionText(width)} × ${dimensionText(height)} mm"

// Polish needs three forms: 1 zdjęcie, 2–4 zdjęcia (but not 12–14), 5+ zdjęć.
internal fun plural(count: Int, one: String, few: String, many: String): String {
    val form = when {
        count == 1 -> one
        count % 10 in 2..4 && count % 100 !in 12..14 -> few
        else -> many
    }
    return "$count $form"
}

// Tabular digits keep dimensions aligned without the wide gaps of a monospace face.
internal val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalRouteScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

// The same photo morphs from its gallery tile into the editor's print preview.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedPhoto(photoId: String, shape: Shape): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val route = LocalRouteScope.current ?: return this
    return with(shared) {
        this@sharedPhoto.sharedBounds(rememberSharedContentState("photo-$photoId"), route,
            clipInOverlayDuringTransition = OverlayClip(shape))
    }
}

internal fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.96f) = composed {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "pressScale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

internal enum class StudioSymbol(val path: String) {
    Plus("M12,5 L12,19 M5,12 L19,12"),
    Back("M15,5 L8,12 L15,19"),
    Arrow("M5,12 L19,12 M13,6 L19,12 L13,18"),
    Sun("M16,12 A4,4 0,1 1,8 12 A4,4 0,1 1,16 12 M12,2 L12,4 M12,20 L12,22 M2,12 L4,12 M20,12 L22,12 M5,5 L6.5,6.5 M17.5,17.5 L19,19 M5,19 L6.5,17.5 M17.5,6.5 L19,5"),
    Moon("M20,14 A8,8 0,1 1,10 4 A7,7 0,0 0,20 14 Z"),
    Folder("M3,7 L3,19 Q3,21 5,21 L19,21 Q21,21 21,19 L21,8 Q21,6 19,6 L12,6 L10,3 L5,3 Q3,3 3,5 Z"),
    Photo("M5,3 L19,3 Q21,3 21,5 L21,19 Q21,21 19,21 L5,21 Q3,21 3,19 L3,5 Q3,3 5,3 Z M3,16 L9,10 L16,17 L19,14 L21,16 M17,7 A1,1 0,1 1,15 7 A1,1 0,1 1,17 7"),
    Camera("M3,7 L7,7 L9,4 L15,4 L17,7 L21,7 L21,20 L3,20 Z M16,13 A4,4 0,1 1,8 13 A4,4 0,1 1,16 13"),
    Print("M7,8 L7,3 L17,3 L17,8 M7,17 L3,17 L3,9 L21,9 L21,17 L17,17 M7,14 L17,14 L17,22 L7,22 Z M17,12 L18,12"),
    More("M5,12 L5.1,12 M12,12 L12.1,12 M19,12 L19.1,12"),
    Grid("M3,3 L9,3 L9,9 L3,9 Z M15,3 L21,3 L21,9 L15,9 Z M3,15 L9,15 L9,21 L3,21 Z M15,15 L21,15 L21,21 L15,21 Z"),
    Thirds("M4,4 L20,4 L20,20 L4,20 Z M9.3,4 L9.3,20 M14.7,4 L14.7,20 M4,9.3 L20,9.3 M4,14.7 L20,14.7"),
    Tag("M3,3 L12,3 L22,13 L13,22 L3,12 Z M8,7 L8,7.1"),
    Sliders("M4,3 L4,7 M4,13 L4,21 M1,7 L7,7 L7,13 L1,13 Z M12,3 L12,13 M12,19 L12,21 M9,13 L15,13 L15,19 L9,19 Z M20,3 L20,6 M20,12 L20,21 M17,6 L23,6 L23,12 L17,12 Z"),
    Ruler("M3,5 L21,5 L21,19 L3,19 Z M7,5 L7,11 M12,5 L12,9 M17,5 L17,11"),
    Check("M5,12 L10,17 L20,6"),
    Trash("M3,6 L21,6 M9,6 L9,3 L15,3 L15,6 M5,6 L6,21 L18,21 L19,6 M10,10 L10,17 M14,10 L14,17"),
    Edit("M4,16 L4,21 L9,21 L21,9 L16,4 Z M13,7 L18,12"),
    Swap("M4,8 L20,8 M16,4 L20,8 L16,12 M20,16 L4,16 M8,12 L4,16 L8,20"),
    Info("M22,12 A10,10 0,1 1,2 12 A10,10 0,1 1,22 12 M12,10 L12,17 M12,6 L12,6.1"),
    Reset("M4,7 A9,9 0,1 1,3 14 M4,2 L4,8 L10,8"),
    Close("M5,5 L19,19 M19,5 L5,19"),
    Help("M22,12 A10,10 0,1 1,2 12 A10,10 0,1 1,22 12 M9.3,9.2 A2.8,2.8 0,1 1,12 12 L12,14 M12,17.4 L12,17.5"),
    Crop("M6,2 L6,18 L22,18 M2,6 L18,6 L18,22"),
    Flash("M13,2 L4,14 L11,14 L10,22 L20,9 L13,9 Z"),
    FlashOff("M13,2 L9.5,6.7 M7.5,9.4 L4,14 L11,14 L10,22 L13.5,17.4 M15.8,14.3 L20,9 L13,9 Z M3,3 L21,21"),
}

@Composable
internal fun StudioIcon(symbol: StudioSymbol, modifier: Modifier = Modifier.size(22.dp), color: Color = MaterialTheme.colorScheme.onSurface) {
    val path = remember(symbol) { PathParser().parsePathString(symbol.path).toPath() }
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun StudioCircleButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                                border: Color = MaterialTheme.colorScheme.outlineVariant, background: Color = Color.Transparent,
                                content: @Composable BoxScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.size(48.dp).pressScale(source, 0.9f).clip(CircleShape).background(background).border(1.dp, border, CircleShape)
        .semantics { contentDescription = description }
        .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center, content = content)
}

@Composable
internal fun StudioIconButton(symbol: StudioSymbol, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StudioCircleButton(description, onClick, modifier) { StudioIcon(symbol) }
}

@Composable
internal fun StudioTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurface, fontSize = 34.sp, lineHeight = 38.sp,
        letterSpacing = (-1.3).sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun StudioAction(text: String, symbol: StudioSymbol, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                          secondary: Boolean = false, onDark: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val container = when {
        secondary && onDark -> Color.White.copy(alpha = 0.12f)
        secondary -> colors.surfaceVariant
        else -> colors.primary
    }
    val content = when {
        secondary && onDark -> Color.White
        secondary -> colors.onSurface
        else -> colors.onPrimary
    }
    Button(onClick, modifier.heightIn(min = 56.dp).pressScale(source), enabled = enabled, shape = RoundedCornerShape(18.dp),
        interactionSource = source, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)) {
        StudioIcon(symbol, color = if (!enabled) colors.onSurface.copy(alpha = 0.38f) else content)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun StudioDock(content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.background(colors.background)) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

internal val DockClearance = 104.dp

@Composable
internal fun StudioTopBar(onBack: (() -> Unit)?, title: String? = null, center: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            StudioIconButton(StudioSymbol.Back, tr("Wróć", "Back"), onBack)
            Spacer(Modifier.width(14.dp))
        }
        Box(Modifier.weight(1f)) {
            when {
                center != null -> center()
                title != null -> Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

// Adding a photo is three screens; the indicator makes that sequence visible.
@Composable
internal fun FlowSteps(current: Int, modifier: Modifier = Modifier, onDark: Boolean = false, showLabel: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val accent = if (onDark) LabelYellow else colors.primary
    val onAccent = if (onDark) StudioInk else colors.onPrimary
    val strong = if (onDark) Color.White else colors.onSurface
    val muted = if (onDark) Color.White.copy(alpha = 0.5f) else colors.onSurfaceVariant
    val line = if (onDark) Color.White.copy(alpha = 0.22f) else colors.outlineVariant
    val description = tr("Krok $current z 3", "Step $current of 3")
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        listOf(tr("Wymiar", "Size"), tr("Zdjęcie", "Photo"), tr("Opis", "Details")).forEachIndexed { index, label ->
            val step = index + 1
            if (index > 0) Box(Modifier.padding(horizontal = 6.dp).width(14.dp).height(1.dp).background(if (step <= current) accent else line))
            val fill by animateColorAsState(if (step <= current) accent else Color.Transparent, label = "stepFill")
            Box(Modifier.size(22.dp).clip(CircleShape).background(fill).border(1.dp, if (step <= current) accent else line, CircleShape), contentAlignment = Alignment.Center) {
                if (step < current) StudioIcon(StudioSymbol.Check, Modifier.size(13.dp), onAccent)
                else Text("$step", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (step == current) onAccent else muted)
            }
            if (step == current && showLabel) {
                Spacer(Modifier.width(7.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = strong, maxLines = 1)
            }
        }
    }
}

// Same drawing as the launcher icon (res/drawable/launcher_foreground.xml), cropped to its visible 72 dp.
@Composable
internal fun StudioLogo(modifier: Modifier = Modifier.size(38.dp)) {
    Canvas(modifier.clip(RoundedCornerShape(12.dp))) {
        drawRect(LabelYellow)
        val unit = size.width / 72f
        scale(unit, unit, pivot = Offset.Zero) {
            translate(-18f + 3f, -18f + 2f) {
                rotate(-11f, pivot = Offset(48f, 50f)) {
                    drawRoundRect(StudioPaper, Offset(35f, 31f), Size(26f, 38f), CornerRadius(2f))
                    drawRoundRect(StudioInk, Offset(35f, 31f), Size(26f, 38f), CornerRadius(2f), style = Stroke(2.5f))
                }
                drawRoundRect(StudioInk, Offset(44f, 38f), Size(26f, 38f), CornerRadius(2f))
                drawRect(StudioPaper, Offset(47.5f, 41.5f), Size(11f, 5f))
                drawRect(StudioOrange, Offset(60f, 68f), Size(6.5f, 5f))
            }
        }
    }
}

@Composable
internal fun StudioEmpty(title: String, text: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(104.dp).border(1.dp, colors.outlineVariant, RoundedCornerShape(30.dp)).padding(18.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            StudioIcon(StudioSymbol.Photo, Modifier.size(32.dp), colors.primary)
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, textAlign = TextAlign.Center)
        Text(text, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

// Decoded previews stay in memory so the editor can show the gallery thumbnail at once while the
// full-size preview decodes; otherwise the shared transition would morph into an empty frame.
private val photoCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
internal fun StudioPhoto(photo: PhotoItem, file: File, modifier: Modifier = Modifier, labels: Boolean = false, thumbnail: Boolean = true) {
    val key = "${file.path}:${file.lastModified()}"
    val maxSide = if (thumbnail) 600 else 1600
    val bitmap by produceState(photoCache.get("$key:$maxSide") ?: photoCache.get("$key:600"), key, maxSide) {
        if (photoCache.get("$key:$maxSide") != null) return@produceState
        withContext(Dispatchers.IO) { runCatching { ImageTools.load(file, maxSide) }.getOrNull() }
            ?.let { photoCache.put("$key:$maxSide", it); value = it }
    }
    // Same pixel pipeline as the PDF, so the preview shows what will be printed. Processed thumbnails are cached;
    // the full-size editor preview keeps its last frame on screen while a new one is computed.
    val profile = LocalPrintProfile.current
    val adjustments = profile.adjustments(photo, full = !thumbnail)
    val thumbnailKey = "$key:600:${profile.adjustments(photo, full = false)}"
    var shown by remember(key) { mutableStateOf(photoCache.get("$key:$maxSide:$adjustments") ?: photoCache.get(thumbnailKey) ?: bitmap) }
    LaunchedEffect(bitmap, adjustments, photo.widthMm, photo.cropZoom) {
        val source = bitmap ?: return@LaunchedEffect
        val cacheKey = "$key:$maxSide:$adjustments"
        photoCache.get(cacheKey)?.let { shown = it; return@LaunchedEffect }
        if (adjustments.isIdentity) { shown = source; return@LaunchedEffect }
        if (shown != null) delay(40)
        val window = ImageTools.cropFraction(source.width, source.height, photo.widthMm / photo.heightMm, photo.cropZoom).first * source.width
        val result = withContext(Dispatchers.Default) {
            val context = coroutineContext
            ImageTools.process(source, adjustments, ImageTools.sharpenRadius(window.roundToInt(), photo.widthMm)) { context.ensureActive() }
        }
        if (thumbnail) photoCache.put(cacheKey, result)
        shown = result
    }
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = remember(shown) { shown?.asImageBitmap() }
        if (image != null) Canvas(Modifier.fillMaxSize()) {
            val crop = ImageTools.cropRect(image.width, image.height, size.width / size.height, photo.cropZoom, photo.cropX, photo.cropY)
            drawImage(image, IntOffset(crop.left, crop.top), IntSize(crop.width(), crop.height()),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
        } else StudioIcon(StudioSymbol.Photo, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (labels) PrintLabelPreview(photo)
    }
}

// Use the PDF's physical text sizes so a narrow photo has the same label proportions in the editor.
// Labels glide to a newly chosen corner instead of jumping.
@Composable
private fun PrintLabelPreview(photo: PhotoItem) {
    val motion = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    val pnX by animateFloatAsState(if (photo.pnCorner.endsWith("R")) 1f else 0f, motion, label = "pnX")
    val pnY by animateFloatAsState(if (photo.pnCorner.startsWith("B")) 1f else 0f, motion, label = "pnY")
    val stepsX by animateFloatAsState(if (photo.stepsCorner.endsWith("R")) 1f else 0f, motion, label = "stepsX")
    val stepsY by animateFloatAsState(if (photo.stepsCorner.startsWith("B")) 1f else 0f, motion, label = "stepsY")
    Canvas(Modifier.fillMaxSize()) {
        val unit = size.width / (photo.widthMm * 72f / 25.4f)
        val padding = 0.65f * 72f / 25.4f * unit
        listOf(
            Triple(photo.pn, Offset(pnX, pnY), android.graphics.Color.WHITE),
            Triple(photo.steps, Offset(stepsX, stepsY), android.graphics.Color.rgb(255, 213, 74)),
        ).forEach { (text, corner, background) ->
            if (text.isNotBlank()) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    textSize = 10f * unit
                }
                while (paint.measureText(text) > size.width - 2 * padding && paint.textSize > 5.5f * unit) paint.textSize -= 0.5f * unit
                val width = paint.measureText(text) + 2 * padding
                val height = paint.fontMetrics.descent - paint.fontMetrics.ascent + 2 * padding
                val left = corner.x * (size.width - width)
                val top = corner.y * (size.height - height)
                val canvas = drawContext.canvas.nativeCanvas
                canvas.drawRect(left, top, left + width, top + height, android.graphics.Paint().apply { color = background })
                canvas.drawRect(left, top, left + width, top + height, android.graphics.Paint().apply { color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE; strokeWidth = 0.9f * unit })
                canvas.drawText(text, left + padding, top + padding - paint.fontMetrics.ascent, paint)
            }
        }
    }
}
