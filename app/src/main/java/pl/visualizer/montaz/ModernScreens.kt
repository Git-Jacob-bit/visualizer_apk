package pl.visualizer.montaz

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ZoomState
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun ModernCameraScreen(widthMm: Float, heightMm: Float, store: ProjectStore, projectId: String, onBack: () -> Unit, onSaved: (File) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val lang = LocalLang.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(Modifier.fillMaxSize().background(CameraBlack).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Dostęp do aparatu", "Camera access"), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(tr("Aparat jest potrzebny do fotografowania kroków montażu.", "The camera is needed to photograph assembly steps."), color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(20.dp))
            StudioAction(tr("Zezwól", "Allow"), StudioSymbol.Camera, { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
        return
    }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            // Focus and zoom are driven from Compose so the UI can show and lock them.
            isTapToFocusEnabled = false
            isPinchToZoomEnabled = false
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    // Debug builds only: a still image stands in for the live preview so manual screenshots show no real surroundings.
    val demoPreview = remember { if (BuildConfig.DEBUG) File(context.filesDir, "demo_preview.jpg").takeIf { it.exists() } else null }
    val mainExecutor = remember { java.util.concurrent.Executor { command -> Handler(Looper.getMainLooper()).post(command) } }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var zoomRange by remember { mutableStateOf(if (demoPreview != null) 1f..5f else 1f..1f) }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = Observer<ZoomState> { state -> zoom = state.zoomRatio; zoomRange = state.minZoomRatio..state.maxZoomRatio }
        controller.zoomState.observe(lifecycleOwner, observer)
        onDispose { controller.zoomState.removeObserver(observer) }
    }
    var focus by remember { mutableStateOf<FocusMark?>(null) }
    val ringScale = remember { Animatable(1f) }
    // Tap focuses and meters that spot and keeps it locked (no auto-cancel) until the user unlocks it.
    fun focusAt(position: Offset) {
        if (demoPreview != null) {
            focus = FocusMark(position, FocusState.Locked)
            scope.launch { ringScale.snapTo(1.5f); ringScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
            return
        }
        val view = previewView ?: return
        val control = controller.cameraControl ?: return
        val mark = FocusMark(position, FocusState.Focusing)
        focus = mark
        scope.launch { ringScale.snapTo(1.5f); ringScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
        val point = view.meteringPointFactory.createPoint(position.x, position.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build()
        val result = control.startFocusAndMetering(action)
        // With auto-cancel disabled the lens stays locked at the chosen distance even when the HAL reports
        // "not focused" (Pixel does this for sharp scenes), so only a rejected request counts as a failure.
        result.addListener({
            val applied = runCatching { result.get() }.isSuccess
            if (focus === mark) focus = mark.copy(state = if (applied) FocusState.Locked else FocusState.Failed)
        }, mainExecutor)
    }
    fun unlockFocus() {
        controller.cameraControl?.cancelFocusAndMetering()
        focus = null
    }
    fun setZoom(ratio: Float) { controller.setZoomRatio(ratio.coerceIn(zoomRange.start, zoomRange.endInclusive)) }
    var busy by remember { mutableStateOf(false) }
    var flash by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    val shutterFlash = remember { Animatable(0f) }
    val gridAlpha by animateFloatAsState(if (grid) 1f else 0f, label = "gridAlpha")
    val shutterSource = remember { MutableInteractionSource() }
    val shutterFill by animateColorAsState(if (busy) Color.White.copy(alpha = 0.35f) else Color.White, label = "shutterFill")

    TutorialTour("camera", Tours.camera(lang))
    SmartHint("hint.smallframe", minOf(widthMm, heightMm) <= 30f, TutorialStep("camera.frame", tr("Mały kadr", "Small shot"),
        tr("Podejdź tak, by detal wypełnił ramkę, i dotknij go, żeby zablokować ostrość. Gdy obraz się rozmywa, cofnij się i użyj 2×.",
            "Move in until the detail fills the frame and tap it to lock focus. If the image blurs, step back and use 2×.")), delayMillis = 2600)
    Column(Modifier.fillMaxSize().background(CameraBlack)) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            CameraButton(StudioSymbol.Back, tr("Wróć do wymiarów", "Back to size"), onClick = onBack)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FlowSteps(2, onDark = true) }
            CameraButton(StudioSymbol.Thirds, if (grid) tr("Ukryj siatkę", "Hide grid") else tr("Pokaż siatkę", "Show grid"), Modifier.tutorialTarget("camera.grid"), selected = grid) { grid = !grid }
            Spacer(Modifier.width(8.dp))
            CameraButton(if (flash) StudioSymbol.Flash else StudioSymbol.FlashOff, if (flash) tr("Wyłącz lampę", "Flash off") else tr("Włącz lampę", "Flash on"), Modifier.tutorialTarget("camera.flash"), selected = flash) {
                flash = !flash
                controller.imageCaptureFlashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val (frameWidth, frameHeight) = fitFrame(maxWidth, maxHeight, widthMm / heightMm)
            Box(Modifier.width(frameWidth).height(frameHeight).tutorialTarget("camera.frame").clip(RoundedCornerShape(10.dp))) {
                // COMPATIBLE (TextureView) lets the preview fade and slide with the screen transition.
                if (demoPreview != null) StudioPhoto(PhotoItem("demo", demoPreview.name, widthMm, heightMm), demoPreview, Modifier.fillMaxSize(), thumbnail = false)
                else AndroidView(factory = { PreviewView(it).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                    previewView = this
                } }, modifier = Modifier.fillMaxSize())
                val ringColor by animateColorAsState(when (focus?.state) {
                    FocusState.Locked -> LabelYellow
                    FocusState.Failed -> Color(0xFFFF6F7D)
                    else -> Color.White
                }, label = "focusRing")
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { focusAt(it) } }
                    .pointerInput(Unit) { detectTransformGestures { _, _, zoomChange, _ -> if (zoomChange != 1f) setZoom(zoom * zoomChange) } }) {
                    focus?.let { mark ->
                        val radius = 34.dp.toPx() * ringScale.value
                        drawCircle(ringColor, radius, mark.position, style = Stroke(2.dp.toPx()))
                        drawCircle(ringColor, 2.5.dp.toPx(), mark.position)
                    }
                    if (gridAlpha > 0f) {
                        val line = Color.White.copy(alpha = 0.45f * gridAlpha)
                        for (i in 1..2) {
                            drawLine(line, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.dp.toPx())
                            drawLine(line, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.dp.toPx())
                        }
                    }
                }
                Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(10.dp)))
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = shutterFlash.value)))
                HelpButton("camera", Tours.camera(lang), Modifier.align(Alignment.TopEnd).padding(8.dp), onDark = true)
                androidx.compose.animation.AnimatedVisibility(focus?.state == FocusState.Locked || focus?.state == FocusState.Failed, Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                    val locked = focus?.state != FocusState.Failed
                    Row(Modifier.clip(CircleShape).background(if (locked) LabelYellow else Color.Black.copy(alpha = 0.6f)).clickable { unlockFocus() }
                        .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (locked) tr("Ostrość zablokowana", "Focus locked") else tr("Nie udało się ustawić ostrości, dotknij ponownie", "Could not focus, tap again"), color = if (locked) CameraBlack else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        StudioIcon(StudioSymbol.Close, Modifier.size(16.dp), if (locked) CameraBlack else Color.White)
                    }
                }
                if (zoomRange.endInclusive >= 2f) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).tutorialTarget("camera.zoom").clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ZoomChip("1×", zoom < 1.5f) { setZoom(1f) }
                    ZoomChip(if (zoom >= 1.5f && kotlin.math.abs(zoom - 2f) > 0.05f) "${lang.decimal(zoom)}×" else "2×", zoom >= 1.5f) { setZoom(2f) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mmText(widthMm, heightMm), color = Color.White, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold)
            Text(tr("Dotknij kadru, aby ustawić i zablokować ostrość", "Tap the frame to set and lock focus"), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(84.dp).tutorialTarget("camera.shutter").pressScale(shutterSource, 0.9f).clip(CircleShape).border(3.dp, Color.White, CircleShape)
                .semantics { contentDescription = lang.tr("Zrób zdjęcie", "Take photo") }
                .clickable(interactionSource = shutterSource, indication = null, enabled = !busy) {
                    busy = true
                    scope.launch { shutterFlash.snapTo(0.85f); shutterFlash.animateTo(0f, tween(280)) }
                    val file = store.newCaptureFile(projectId)
                    if (demoPreview != null) { demoPreview.copyTo(file, overwrite = true); busy = false; onSaved(file); return@clickable }
                    val executor = java.util.concurrent.Executor { command -> Handler(Looper.getMainLooper()).post(command) }
                    controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { busy = false; onSaved(file) }
                        override fun onError(exception: ImageCaptureException) { busy = false; file.delete(); onError(lang.tr("Nie udało się zrobić zdjęcia: ", "Could not take the photo: ") + exception.message) }
                    })
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(66.dp).clip(CircleShape).background(shutterFill))
                if (busy) CircularProgressIndicator(Modifier.fillMaxSize(), color = LabelYellow, strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(10.dp))
            Text(if (busy) tr("Zapisywanie…", "Saving…") else tr("Dotknij, aby zrobić zdjęcie", "Tap to take a photo"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Shown right after capture, so a blurred shot can be retaken instead of saved and deleted.
@Composable
internal fun ModernReviewScreen(file: File, widthMm: Float, heightMm: Float, onRetake: () -> Unit, onUse: () -> Unit) {
    val preview = remember(file.path, widthMm, heightMm) { PhotoItem("review", file.name, widthMm, heightMm) }
    val lang = LocalLang.current
    TutorialTour("review", Tours.review(lang))
    Column(Modifier.fillMaxSize().background(CameraBlack)) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            CameraButton(StudioSymbol.Back, tr("Wróć do aparatu", "Back to camera"), onClick = onRetake)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FlowSteps(2, onDark = true) }
            HelpButton("review", Tours.review(lang), onDark = true)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val (frameWidth, frameHeight) = fitFrame(maxWidth, maxHeight, widthMm / heightMm)
            StudioPhoto(preview, file, Modifier.width(frameWidth).height(frameHeight).tutorialTarget("review.photo").clip(RoundedCornerShape(10.dp)), thumbnail = false)
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mmText(widthMm, heightMm), color = Color.White, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold)
            Text(tr("Tak zdjęcie trafi na wydruk", "This is how the photo will print"), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioAction(tr("Powtórz", "Retake"), StudioSymbol.Reset, onRetake, Modifier.weight(1f).tutorialTarget("review.retake"), secondary = true, onDark = true)
                StudioAction(tr("Użyj zdjęcia", "Use photo"), StudioSymbol.Check, onUse, Modifier.weight(1.3f).tutorialTarget("review.use"))
            }
        }
    }
}

private enum class FocusState { Focusing, Locked, Failed }
private data class FocusMark(val position: Offset, val state: FocusState)

@Composable
private fun ZoomChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent, label = "zoomChip")
    Box(Modifier.size(44.dp).clip(CircleShape).background(background).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) LabelYellow else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, style = TabularNumbers)
    }
}

private fun fitFrame(maxWidth: Dp, maxHeight: Dp, ratio: Float): Pair<Dp, Dp> {
    val width = minOf((maxWidth - 32.dp).coerceAtLeast(1.dp), (maxHeight - 36.dp).coerceAtLeast(1.dp) * ratio)
    return width to width / ratio
}

@Composable
private fun CameraButton(symbol: StudioSymbol, description: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) LabelYellow else Color.White.copy(alpha = 0.14f), label = "cameraButton")
    StudioCircleButton(description, onClick, modifier, border = Color.Transparent, background = background) {
        StudioIcon(symbol, Modifier.size(22.dp), if (selected) CameraBlack else Color.White)
    }
}
