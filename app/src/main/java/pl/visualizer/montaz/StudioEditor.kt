package pl.visualizer.montaz

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_CROP_ZOOM = 4f

@Composable
internal fun ModernPhotoEditor(photo: PhotoItem, file: File, isNew: Boolean, onBack: () -> Unit, onSave: (PhotoItem) -> Unit, onDelete: () -> Unit, onPrinter: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var pn by rememberSaveable(photo.id) { mutableStateOf(photo.pn) }
    var steps by rememberSaveable(photo.id) { mutableStateOf(photo.steps) }
    var brightness by rememberSaveable(photo.id) { mutableStateOf(photo.brightness) }
    var contrast by rememberSaveable(photo.id) { mutableStateOf(photo.contrast) }
    var pnCorner by rememberSaveable(photo.id) { mutableStateOf(photo.pnCorner) }
    var stepsCorner by rememberSaveable(photo.id) { mutableStateOf(photo.stepsCorner) }
    var cropZoom by rememberSaveable(photo.id) { mutableStateOf(photo.cropZoom) }
    var cropX by rememberSaveable(photo.id) { mutableStateOf(photo.cropX) }
    var cropY by rememberSaveable(photo.id) { mutableStateOf(photo.cropY) }
    var tool by rememberSaveable(photo.id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var discard by remember { mutableStateOf(false) }
    val valid = pn.isBlank() || pn.matches(Regex("[0-9]{10}"))
    val preview = photo.copy(pn = pn, steps = steps, brightness = brightness, contrast = contrast, pnCorner = pnCorner, stepsCorner = stepsCorner, cropZoom = cropZoom, cropX = cropX, cropY = cropY)
    val dirty = preview != photo
    val focus = LocalFocusManager.current
    val dpi by produceState<Int?>(null, file.path, photo.widthMm, photo.heightMm) {
        value = withContext(Dispatchers.IO) { runCatching { ImageTools.effectiveDpi(file, photo.widthMm, photo.heightMm) }.getOrDefault(0) }
    }
    val imageSize by produceState<Pair<Int, Int>?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { ImageTools.size(file) }.getOrNull() }
    }
    val cropDpi = dpi?.let { (it / cropZoom).toInt() }
    val ratio = photo.widthMm / photo.heightMm
    // Pinch zooms the print window, drag moves it; pan is converted to the −1…1 travel of the window.
    fun transform(pan: Offset, zoomChange: Float, width: Float, height: Float) {
        cropZoom = (cropZoom * zoomChange).coerceIn(1f, MAX_CROP_ZOOM)
        val (imageWidth, imageHeight) = imageSize ?: return
        val (fractionW, fractionH) = ImageTools.cropFraction(imageWidth, imageHeight, ratio, cropZoom)
        if (fractionW < 0.999f) cropX = (cropX - 2f * pan.x / width * fractionW / (1f - fractionW)).coerceIn(-1f, 1f)
        if (fractionH < 0.999f) cropY = (cropY - 2f * pan.y / height * fractionH / (1f - fractionH)).coerceIn(-1f, 1f)
    }
    val back = { if (dirty) discard = true else onBack() }
    val lang = LocalLang.current
    TutorialTour("editor", Tours.editor(lang))
    SmartHint("editor.crop", tool == 1, TutorialStep("editor.stage", tr("Kadrowanie palcami", "Framing with your fingers"),
        tr("Rozsuń palce na podglądzie, aby przybliżyć, i przeciągnij, aby przesunąć kadr. Żółta ramka to granica wydruku.",
            "Spread your fingers on the preview to zoom in and drag to move the framing. The yellow frame is the print edge.")), delayMillis = 500)
    SmartHint("editor.light", tool == 3, TutorialStep("editor.auto", tr("Automatyczna korekta", "Automatic correction"),
        tr("Dobiera jasność i kontrast do widocznego kadru. Ciemnienie drukarki kompensujesz osobno w ustawieniach drukarki.",
            "Sets brightness and contrast for the visible framing. Printer darkening is compensated separately in the printer settings.")), delayMillis = 500)
    SmartHint("hint.dpi", cropDpi != null && cropDpi in 1 until 300, TutorialStep("editor.stage", tr("Za mała rozdzielczość", "Resolution too low"),
        tr("Ten kadr ma mniej niż 300 DPI, więc na wydruku może być nieostry. Zmniejsz przybliżenie albo zrób zdjęcie z bliższej odległości.",
            "This framing is below 300 DPI, so the print may look soft. Zoom out or take the photo from closer.")))
    SmartHint("hint.clash", pn.isNotBlank() && steps.isNotBlank() && pnCorner == stepsCorner, TutorialStep("editor.tabs", tr("Oznaczenia nachodzą na siebie", "Labels overlap"),
        tr("PN i kroki są w tym samym narożniku. W zakładce Układ wybierz dla nich różne rogi.", "PN and steps share a corner. Pick different corners in the Layout tab.")))
    BackHandler(dirty) { discard = true }

    Column(Modifier.fillMaxSize()) {
        StudioTopBar(back, title = if (isNew) null else tr("Edycja zdjęcia", "Edit photo"), center = if (isNew) ({ FlowSteps(3, showLabel = false) }) else null) {
            val source = remember { MutableInteractionSource() }
            HelpButton("editor", Tours.editor(lang))
            Button(onClick = { focus.clearFocus(); onSave(preview.copy(pn = pn.trim(), steps = steps.trim())) }, Modifier.tutorialTarget("editor.save").pressScale(source), enabled = valid,
                interactionSource = source, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                Text(tr("Zapisz", "Save"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth > maxHeight && maxWidth > 600.dp
            val panelHeight = minOf(320.dp, maxHeight * 0.53f)
            val panel: @Composable (Modifier) -> Unit = { modifier ->
                EditorTools(tool, { focus.clearFocus(); tool = it }, modifier) { current ->
                    when (current) {
                        0 -> {
                            OutlinedTextField(pn, { pn = it.filter { char -> char in '0'..'9' }.take(10) }, label = { Text(tr("Numer PN", "PN number")) },
                                trailingIcon = { Text("${pn.length}/10", Modifier.padding(end = 12.dp), color = if (valid) colors.onSurfaceVariant else colors.error, fontSize = 11.sp, style = TabularNumbers) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = !valid, shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                            OutlinedTextField(steps, { steps = it }, label = { Text(tr("Kroki", "Steps")) }, placeholder = { Text(tr("np. S1, S2", "e.g. S1, S2")) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                            Text(if (valid) tr("Puste pole nie pojawi się na zdjęciu.", "An empty field does not appear on the photo.") else tr("Wpisz 10 cyfr PN lub pozostaw pole puste.", "Enter 10 PN digits or leave the field empty."), fontSize = 12.sp, color = if (valid) colors.onSurfaceVariant else colors.error)
                        }
                        1 -> {
                            AdjustmentSlider(tr("Przybliżenie", "Zoom"), "${lang.decimal(cropZoom)}×", cropZoom, 1f..MAX_CROP_ZOOM) { cropZoom = it }
                            Text(tr("Rozsuń palce na podglądzie, aby przybliżyć, i przeciągnij, aby przesunąć kadr. Wymiar wydruku się nie zmienia.", "Spread your fingers on the preview to zoom and drag to move the framing. The printed size stays the same."),
                                fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                            InfoLine(tr("Rozdzielczość kadru", "Framing resolution"), cropDpi?.let { "$it DPI" } ?: tr("Sprawdzanie…", "Checking…"))
                            if (cropDpi != null && cropDpi < 300) Text(tr("Poniżej 300 DPI wydruk może być nieostry. Zmniejsz przybliżenie albo zrób zdjęcie z bliska.", "Below 300 DPI the print may look soft. Zoom out or take the photo from closer."), color = colors.error, fontSize = 12.sp, lineHeight = 18.sp)
                            TextButton(onClick = { cropZoom = 1f; cropX = 0f; cropY = 0f }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                StudioIcon(StudioSymbol.Reset, Modifier.size(16.dp), colors.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Resetuj kadr", "Reset framing"), fontSize = 13.sp)
                            }
                        }
                        2 -> {
                            val clash = pn.isNotBlank() && steps.isNotBlank() && pnCorner == stepsCorner
                            CornerPicker(tr("Numer PN", "PN number"), pnCorner, Color.White) { pnCorner = it }
                            HorizontalDivider(color = colors.outlineVariant)
                            CornerPicker(tr("Kroki", "Steps"), stepsCorner, LabelYellow) { stepsCorner = it }
                            Text(if (clash) tr("Oznaczenia zajmują ten sam narożnik.", "Both labels are in the same corner.") else tr("Wybierz narożnik dla każdego oznaczenia.", "Pick a corner for each label."), fontSize = 12.sp,
                                color = if (clash) colors.error else colors.onSurfaceVariant)
                        }
                        3 -> {
                            AdjustmentSlider(tr("Jasność", "Brightness"), "${(brightness * 100).toInt()}%", brightness, -0.4f..0.4f) { brightness = it }
                            AdjustmentSlider(tr("Kontrast", "Contrast"), "${(contrast * 100).toInt()}%", contrast, 0.5f..1.5f) { contrast = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        val levels = withContext(Dispatchers.Default) {
                                            runCatching {
                                                val bitmap = ImageTools.load(file, 800)
                                                ImageTools.autoLevels(bitmap, ImageTools.cropRect(bitmap.width, bitmap.height, ratio, cropZoom, cropX, cropY))
                                            }.getOrNull()
                                        }
                                        levels?.let { (b, c) -> brightness = b; contrast = c }
                                    }
                                }, Modifier.tutorialTarget("editor.auto"), shape = RoundedCornerShape(14.dp)) {
                                    StudioIcon(StudioSymbol.Sliders, Modifier.size(16.dp), colors.onSecondaryContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tr("Automatycznie", "Automatic"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = { brightness = 0f; contrast = 1f }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    StudioIcon(StudioSymbol.Reset, Modifier.size(16.dp), colors.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("Resetuj", "Reset"), fontSize = 13.sp)
                                }
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Rozjaśnienie pod druk, wyostrzanie i druk czarno-biały ustawisz dla całej drukarki.", "Print brightening, sharpening and black-and-white printing are set for the whole printer."), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                                TextButton(onClick = onPrinter) { Text(tr("Drukarka", "Printer"), fontSize = 13.sp) }
                            }
                        }
                        else -> {
                            InfoLine(tr("Wymiar wydruku", "Print size"), mmText(photo.widthMm, photo.heightMm))
                            InfoLine(tr("Rozdzielczość", "Resolution"), cropDpi?.let { "$it DPI" } ?: tr("Sprawdzanie…", "Checking…"))
                            if (cropDpi != null && cropDpi < 300) Text(tr("Rozdzielczość poniżej 300 DPI. Sprawdź ostrość wydruku.", "Resolution below 300 DPI. Check the print sharpness."), color = colors.error, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            FilledTonalButton(onClick = onDelete, shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)) {
                                StudioIcon(StudioSymbol.Trash, Modifier.size(17.dp), colors.onErrorContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(tr("Usuń zdjęcie", "Delete photo"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    EditorStage(preview, file, Modifier.weight(1f).fillMaxHeight(), if (tool == 1) ::transform else null)
                    panel(Modifier.width(340.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    EditorStage(preview, file, Modifier.weight(1f).fillMaxWidth(), if (tool == 1) ::transform else null)
                    panel(Modifier.fillMaxWidth().height(panelHeight))
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr("Odrzucić zmiany?", "Discard changes?")) }, text = { Text(tr("Zmiany w oznaczeniach i korekcie obrazu nie zostały zapisane.", "Changes to labels and image correction have not been saved.")) },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text(tr("Odrzuć", "Discard")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(tr("Edytuj dalej", "Keep editing")) } })
}

@Composable
private fun EditorStage(photo: PhotoItem, file: File, modifier: Modifier, onTransform: ((Offset, Float, Float, Float) -> Unit)?) {
    val transform by rememberUpdatedState(onTransform)
    val gridAlpha by animateFloatAsState(if (onTransform != null) 1f else 0f, label = "cropGrid")
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().tutorialTarget("editor.stage").clip(RoundedCornerShape(24.dp)).background(Color(0xFF1B1E1C)), contentAlignment = Alignment.Center) {
            val ratio = photo.widthMm / photo.heightMm
            val width = minOf((maxWidth - 36.dp).coerceAtLeast(1.dp), (maxHeight - 36.dp).coerceAtLeast(1.dp) * ratio)
            Box(Modifier.width(width).height(width / ratio).sharedPhoto(photo.id, RectangleShape).shadow(14.dp)
                .pointerInput(onTransform != null) {
                    if (onTransform != null) detectTransformGestures { _, pan, zoom, _ -> transform?.invoke(pan, zoom, size.width.toFloat(), size.height.toFloat()) }
                }) {
                StudioPhoto(photo, file, Modifier.fillMaxSize(), labels = true, thumbnail = false)
                if (gridAlpha > 0f) Canvas(Modifier.fillMaxSize()) {
                    val line = Color.White.copy(alpha = 0.55f * gridAlpha)
                    for (i in 1..2) {
                        drawLine(line, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.dp.toPx())
                        drawLine(line, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.dp.toPx())
                    }
                    drawRect(LabelYellow.copy(alpha = gridAlpha), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon(StudioSymbol.Ruler, Modifier.size(16.dp), colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(mmText(photo.widthMm, photo.heightMm), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
            Spacer(Modifier.weight(1f))
            Text(tr("Podgląd wydruku", "Print preview"), fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun EditorTools(tool: Int, onTool: (Int) -> Unit, modifier: Modifier, content: @Composable ColumnScope.(Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tools = listOf(StudioSymbol.Tag to tr("Dane", "Details"), StudioSymbol.Crop to tr("Kadr", "Framing"), StudioSymbol.Grid to tr("Układ", "Layout"), StudioSymbol.Sliders to tr("Światło", "Light"), StudioSymbol.Info to tr("Plik", "File"))
    Column(modifier.background(colors.surface, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp).tutorialTarget("editor.tabs")) {
            val tabWidth = maxWidth / tools.size
            val indicatorX by animateDpAsState(tabWidth * tool + (tabWidth - 24.dp) / 2, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "toolIndicator")
            Row(Modifier.fillMaxWidth()) {
                tools.forEachIndexed { index, (icon, title) ->
                    val active = tool == index
                    val color by animateColorAsState(if (active) colors.primary else colors.onSurfaceVariant, label = "toolColor")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).semantics { selected = active }.clickable { onTool(index) }.padding(top = 10.dp, bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        StudioIcon(icon, Modifier.size(19.dp), color)
                        Text(title, color = color, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp).offset(x = indicatorX).width(24.dp).height(3.dp).background(colors.primary, RoundedCornerShape(2.dp)))
        }
        AnimatedContent(tool, Modifier.weight(1f).fillMaxWidth(), label = "toolPanel", transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(260)) { it / 6 * direction } + fadeIn(tween(220, delayMillis = 40))) togetherWith
                (slideOutHorizontally(tween(200)) { -it / 6 * direction } + fadeOut(tween(140))) using SizeTransform(clip = false)
        }) { current ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content(current)
            }
        }
    }
}

@Composable
private fun AdjustmentSlider(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(valueLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, style = TabularNumbers)
        }
        Slider(value, onValue, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CornerPicker(title: String, corner: String, marker: Color, onCorner: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("TL", "TR", "BL", "BR").forEach { position ->
                val active = position == corner
                val description = when (position) { "TL" -> tr("Lewy górny", "Top left"); "TR" -> tr("Prawy górny", "Top right"); "BL" -> tr("Lewy dolny", "Bottom left"); else -> tr("Prawy dolny", "Bottom right") }
                val background by animateColorAsState(if (active) colors.primaryContainer else colors.surfaceVariant, label = "cornerBg")
                val border by animateColorAsState(if (active) colors.primary else Color.Transparent, label = "cornerBorder")
                val source = remember { MutableInteractionSource() }
                Box(Modifier.weight(1f).height(44.dp).pressScale(source).clip(RoundedCornerShape(10.dp)).background(background)
                    .border(1.5.dp, border, RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "$title: $description"; selected = active }
                    .clickable(interactionSource = source, indication = null) { onCorner(position) }, contentAlignment = Alignment.Center) {
                    // The marker keeps its print colour (white PN, yellow steps); only the tile shows selection.
                    Canvas(Modifier.size(26.dp, 19.dp)) {
                        val width = size.width * 0.42f
                        val height = size.height * 0.42f
                        val offset = Offset(if (position.endsWith("R")) size.width - width else 0f, if (position.startsWith("B")) size.height - height else 0f)
                        drawRect(marker, offset, Size(width, height))
                        drawRect(StudioInk, offset, Size(width, height), style = Stroke(0.8.dp.toPx()))
                        drawRect(if (active) colors.primary else colors.onSurfaceVariant, style = Stroke(1.dp.toPx()))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
    }
}
