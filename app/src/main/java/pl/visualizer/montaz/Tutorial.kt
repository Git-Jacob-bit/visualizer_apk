package pl.visualizer.montaz

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** One coach mark. [target] is a [tutorialTarget] id; null shows a centred card without a spotlight. */
data class TutorialStep(val target: String?, val title: String, val text: String)

/**
 * Runs per-screen tours and one-off contextual hints. Every tour or hint is shown once (remembered in
 * preferences); the help button on each screen replays that screen's tour on demand.
 */
class TutorialController(private val settings: SharedPreferences) {
    val targets = mutableStateMapOf<String, Rect>()
    var enabled by mutableStateOf(settings.getBoolean("tips_enabled", true))
        private set
    var steps by mutableStateOf<List<TutorialStep>>(emptyList())
        private set
    var index by mutableStateOf(0)
        private set
    private var key = ""

    val active get() = steps.isNotEmpty()
    val current get() = steps.getOrNull(index)

    fun seen(key: String) = settings.getBoolean("tip_$key", false)

    fun start(key: String, tour: List<TutorialStep>, force: Boolean = false) {
        if (active || (!force && (!enabled || seen(key)))) return
        // Skip steps whose element is not on screen (e.g. no project yet), so a tour never points at nothing.
        val available = tour.filter { step -> step.target == null || targets[step.target]?.let { it.width > 0f && it.height > 0f } == true }
        if (available.isEmpty()) return
        this.key = key
        index = 0
        steps = available
    }

    fun next() { if (index < steps.lastIndex) index++ else finish() }
    fun previous() { if (index > 0) index-- }

    fun finish() {
        if (key.isNotEmpty()) settings.edit().putBoolean("tip_$key", true).apply()
        steps = emptyList()
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        settings.edit().putBoolean("tips_enabled", value).apply()
    }

    fun resetSeen() {
        val editor = settings.edit()
        settings.all.keys.filter { it.startsWith("tip_") }.forEach(editor::remove)
        editor.apply()
    }
}

internal val LocalTutorial = staticCompositionLocalOf<TutorialController?> { null }

internal fun Modifier.tutorialTarget(id: String) = composed {
    val tutorial = LocalTutorial.current
    if (tutorial == null) Modifier else {
        DisposableEffect(id) { onDispose { tutorial.targets.remove(id) } }
        Modifier.onGloballyPositioned { tutorial.targets[id] = it.boundsInWindow() }
    }
}

/** Starts [steps] the first time this screen is shown (after the screen transition settles). */
@Composable
internal fun TutorialTour(key: String, steps: List<TutorialStep>) {
    val tutorial = LocalTutorial.current ?: return
    LaunchedEffect(key) {
        delay(750)
        tutorial.start(key, steps)
    }
}

/** A one-off tip that appears only when [condition] becomes true and nothing else is being explained. */
@Composable
internal fun SmartHint(key: String, condition: Boolean, step: TutorialStep, delayMillis: Long = 1400) {
    val tutorial = LocalTutorial.current ?: return
    LaunchedEffect(condition, tutorial.active) {
        if (condition && !tutorial.active && !tutorial.seen(key)) {
            delay(delayMillis)
            tutorial.start(key, listOf(step))
        }
    }
}

@Composable
internal fun HelpButton(key: String, steps: List<TutorialStep>, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val tutorial = LocalTutorial.current
    if (onDark) {
        StudioCircleButton(tr("Pokaż wskazówki", "Show tips"), { tutorial?.start(key, steps, force = true) }, modifier, border = Color.Transparent, background = Color.Black.copy(alpha = 0.45f)) {
            StudioIcon(StudioSymbol.Help, Modifier.size(22.dp), Color.White)
        }
    } else {
        StudioIconButton(StudioSymbol.Help, tr("Pokaż wskazówki", "Show tips"), { tutorial?.start(key, steps, force = true) }, modifier)
    }
}

@Composable
internal fun TutorialOverlay(tutorial: TutorialController) {
    val step = tutorial.current
    var shown by remember { mutableStateOf<TutorialStep?>(null) }
    if (step != null) shown = step
    AnimatedVisibility(step != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        val current = shown ?: return@AnimatedVisibility
        BackHandler { tutorial.finish() }
        val density = LocalDensity.current
        var origin by remember { mutableStateOf(Offset.Zero) }
        var overlaySize by remember { mutableStateOf(Size.Zero) }
        val padding = with(density) { 8.dp.toPx() }
        val hole = current.target?.let { tutorial.targets[it] }?.translate(-origin)?.inflate(padding)
        // The spotlight glides between elements instead of jumping.
        val motion = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
        val centre = Offset(overlaySize.width / 2f, overlaySize.height / 2f)
        val left by animateFloatAsState(hole?.left ?: centre.x, motion, label = "holeLeft")
        val top by animateFloatAsState(hole?.top ?: centre.y, motion, label = "holeTop")
        val right by animateFloatAsState(hole?.right ?: centre.x, motion, label = "holeRight")
        val bottom by animateFloatAsState(hole?.bottom ?: centre.y, motion, label = "holeBottom")
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "pulseValue")

        Box(Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow(); overlaySize = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
            .pointerInput(Unit) { detectTapGestures { tutorial.next() } }) {
            Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                drawRect(Color.Black.copy(alpha = 0.68f))
                if (hole != null) {
                    val radius = CornerRadius(16.dp.toPx())
                    drawRoundRect(Color.Transparent, Offset(left, top), Size(right - left, bottom - top), radius, blendMode = BlendMode.Clear)
                    val grow = 4.dp.toPx() + 10.dp.toPx() * pulse
                    drawRoundRect(LabelYellow.copy(alpha = (1f - pulse) * 0.9f), Offset(left - grow, top - grow), Size(right - left + 2 * grow, bottom - top + 2 * grow),
                        CornerRadius(radius.x + grow), style = Stroke(2.dp.toPx()))
                }
            }
            TutorialCard(tutorial, current, if (hole != null) Rect(left, top, right, bottom) else null, overlaySize)
        }
    }
}

@Composable
private fun TutorialCard(tutorial: TutorialController, step: TutorialStep, hole: Rect?, overlaySize: Size) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    var cardHeight by remember { mutableStateOf(0) }
    val margin = with(density) { 16.dp.toPx() }
    val topLimit = insets.getTop(density) + margin
    val bottomLimit = overlaySize.height - insets.getBottom(density) - margin - cardHeight
    // Place the card on the side of the spotlight with more room.
    val y = when {
        hole == null -> (overlaySize.height - cardHeight) / 2f
        hole.center.y < overlaySize.height / 2f -> hole.bottom + margin
        else -> hole.top - margin - cardHeight
    }.coerceIn(topLimit, maxOf(topLimit, bottomLimit))
    val total = tutorial.steps.size
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).widthIn(max = 420.dp)
        .offset { IntOffset(0, y.roundToInt()) }.onSizeChanged { cardHeight = it.height }
        .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(22.dp), color = colors.surface, shadowElevation = 12.dp) {
        AnimatedContent(step, label = "tutorialCard", transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) }) { shownStep ->
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (total > 1) Text(tr("${tutorial.index + 1} z $total", "${tutorial.index + 1} of $total"), color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
                Text(shownStep.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, modifier = Modifier.padding(end = 8.dp))
                Text(shownStep.text, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (total > 1 && tutorial.index < total - 1) TextButton(onClick = tutorial::finish) { Text(tr("Pomiń", "Skip")) }
                    Spacer(Modifier.weight(1f))
                    if (tutorial.index > 0) TextButton(onClick = tutorial::previous) { Text(tr("Wstecz", "Back")) }
                    TextButton(onClick = tutorial::next) { Text(if (tutorial.index < total - 1) tr("Dalej", "Next") else tr("Rozumiem", "Got it"), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** Tour texts, one list per screen and language. */
internal object Tours {
    private fun step(lang: Lang, target: String, plTitle: String, enTitle: String, plText: String, enText: String) =
        TutorialStep(target, lang.tr(plTitle, enTitle), lang.tr(plText, enText))

    fun projects(lang: Lang) = listOf(
        step(lang, "projects.new", "Nowy projekt", "New project",
            "Projekt zbiera zdjęcia jednej ramy montażowej. Zacznij tutaj i nadaj mu nazwę, np. numer ramy.",
            "A project collects the photos of one assembly frame. Start here and give it a name, e.g. the frame number."),
        step(lang, "projects.card", "Twoje projekty", "Your projects",
            "Dotknij karty, aby otworzyć projekt. Okładka pokazuje ostatnie zdjęcia.",
            "Tap a card to open the project. The cover shows the latest photos."),
        step(lang, "projects.printer", "Drukarka", "Printer",
            "Gdy wydruki wychodzą za ciemne albo nieostre, tu ustawisz kompensację dla wszystkich zdjęć.",
            "When prints come out too dark or soft, set the compensation for all photos here."),
        step(lang, "projects.language", "Język", "Language",
            "Przełącza całą aplikację między polskim i angielskim, także arkusz PDF.",
            "Switches the whole app between Polish and English, including the PDF sheet."),
        step(lang, "projects.theme", "Motyw", "Theme",
            "Przełącza jasny i ciemny wygląd aplikacji.",
            "Switches between the light and dark look."),
        step(lang, "projects.about", "O aplikacji", "About",
            "Dotknij logo, aby zobaczyć wersję, instrukcję obsługi i ustawienia wskazówek.",
            "Tap the logo to see the version, the user manual and the tip settings."),
        step(lang, "projects.help", "Wskazówki", "Tips",
            "Ten przycisk jest na każdym ekranie i pokazuje, do czego służą jego elementy.",
            "This button is on every screen and explains what its parts do."),
    )

    fun project(lang: Lang) = listOf(
        step(lang, "project.add", "Dodaj zdjęcie", "Add photo",
            "Każde zdjęcie ma własny wymiar w milimetrach. Aplikacja poprowadzi Cię przez trzy kroki: wymiar, zdjęcie, opis.",
            "Each photo has its own size in millimetres. The app guides you through three steps: size, photo, details."),
        step(lang, "project.tile", "Zdjęcie w galerii", "Photo in the gallery",
            "Kafelek zachowuje proporcje wydruku. Dotknij go, aby opisać, wykadrować lub poprawić zdjęcie.",
            "The tile keeps the print proportions. Tap it to label, frame or adjust the photo."),
        step(lang, "project.chips", "Podsumowanie", "Summary",
            "Liczba zdjęć i arkuszy A4, na których zmieszczą się w PDF.",
            "The number of photos and of A4 sheets they fill in the PDF."),
        step(lang, "project.pdf", "Wycinanka PDF", "Cut-out PDF",
            "Tworzy arkusze A4 ze zdjęciami w dokładnym rozmiarze, z liniami cięcia i linijką kontrolną 50 mm.",
            "Creates A4 sheets with the photos at their exact size, with cut lines and a 50 mm check ruler."),
        step(lang, "project.menu", "Opcje projektu", "Project options",
            "Zmiana nazwy, usunięcie projektu i ustawienia drukarki.",
            "Rename or delete the project, and the printer settings."),
    )

    fun dimensions(lang: Lang) = listOf(
        step(lang, "dims.steps", "Trzy kroki", "Three steps",
            "Wymiar, zdjęcie, opis. Wskaźnik pokazuje, na którym etapie jesteś.",
            "Size, photo, details. The indicator shows where you are."),
        step(lang, "dims.fields", "Wymiar miejsca", "Size of the spot",
            "Wpisz zmierzoną szerokość i wysokość w milimetrach. Tyle zdjęcie będzie miało na wydruku (maks. 190 × 269 mm).",
            "Enter the measured width and height in millimetres. The photo will print at exactly this size (max. 190 × 269 mm)."),
        step(lang, "dims.swap", "Zamiana", "Swap",
            "Zamienia szerokość z wysokością, gdy miejsce jest poziome zamiast pionowego.",
            "Swaps width and height when the spot is landscape instead of portrait."),
        step(lang, "dims.presets", "Szybki wybór", "Quick pick",
            "Najczęstsze rozmiary jednym dotknięciem.",
            "The most common sizes in one tap."),
        step(lang, "dims.drawing", "Podgląd proporcji", "Proportion preview",
            "Ramka pokazuje kształt kadru, który zobaczysz w aparacie.",
            "The frame shows the shape you will see in the camera."),
        step(lang, "dims.go", "Do aparatu", "To the camera",
            "Przycisk aktywuje się, gdy oba wymiary są poprawne.",
            "The button turns on once both sizes are valid."),
    )

    fun camera(lang: Lang) = listOf(
        step(lang, "camera.frame", "Kadr wydruku", "Print frame",
            "Na wydruk trafi tylko to, co w ramce. Dotknij detalu, aby ustawić na nim ostrość i ją zablokować.",
            "Only what is inside the frame gets printed. Tap a detail to focus on it and lock the focus."),
        step(lang, "camera.zoom", "Przybliżenie", "Zoom",
            "Przełącz 1× lub 2× albo rozsuń palce. Przy małych częściach lepiej przybliżyć, niż podchodzić za blisko.",
            "Switch between 1× and 2× or spread your fingers. For small parts, zoom rather than moving in too close."),
        step(lang, "camera.grid", "Siatka", "Grid",
            "Linie podziału na trzy pomagają ustawić ramę prosto.",
            "Rule-of-thirds lines help you keep the frame straight."),
        step(lang, "camera.flash", "Lampa", "Flash",
            "Włącz przy słabym świetle. Na błyszczących częściach może dawać odblaski.",
            "Turn it on in low light. It can cause glare on shiny parts."),
        step(lang, "camera.shutter", "Zdjęcie", "Shutter",
            "Po zrobieniu zdjęcia zobaczysz podgląd i zdecydujesz, czy go użyć.",
            "After taking the photo you see a preview and decide whether to use it."),
    )

    fun review(lang: Lang) = listOf(
        step(lang, "review.photo", "Podgląd wydruku", "Print preview",
            "Tak zdjęcie zostanie przycięte do wymiaru z kroku 1.",
            "This is how the photo is cropped to the size from step 1."),
        step(lang, "review.retake", "Powtórz", "Retake",
            "Usuwa to ujęcie i wraca do aparatu.",
            "Deletes this shot and returns to the camera."),
        step(lang, "review.use", "Użyj zdjęcia", "Use photo",
            "Zapisuje zdjęcie w projekcie i przechodzi do opisu.",
            "Saves the photo to the project and moves on to the details."),
    )

    fun editor(lang: Lang) = listOf(
        step(lang, "editor.stage", "Podgląd wydruku", "Print preview",
            "Dokładnie tak zdjęcie z oznaczeniami trafi na arkusz, z uwzględnieniem ustawień drukarki.",
            "Exactly how the photo with its labels lands on the sheet, including the printer settings."),
        step(lang, "editor.tabs", "Narzędzia", "Tools",
            "Dane: numer PN i kroki. Kadr: przybliżenie i przesunięcie. Układ: narożniki oznaczeń. Światło: jasność i kontrast. Plik: rozdzielczość i usuwanie.",
            "Details: PN number and steps. Framing: zoom and position. Layout: label corners. Light: brightness and contrast. File: resolution and deleting."),
        step(lang, "editor.save", "Zapisz", "Save",
            "Zapisuje zmiany. Bez zapisu aplikacja zapyta, czy je odrzucić.",
            "Saves your changes. Without saving, the app asks whether to discard them."),
    )
}
