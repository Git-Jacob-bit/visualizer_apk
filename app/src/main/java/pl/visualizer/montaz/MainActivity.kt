package pl.visualizer.montaz

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val settings = remember { getSharedPreferences("visualizer_settings", MODE_PRIVATE) }
            var darkTheme by remember { mutableStateOf(settings.getBoolean("dark_theme", false)) }
            // First launch follows the phone's language; afterwards the in-app choice wins.
            var lang by remember { mutableStateOf(Lang.fromCode(settings.getString("language", null)) ?: Lang.system()) }
            CompositionLocalProvider(LocalLang provides lang) {
                MaterialTheme(colorScheme = animatedScheme(if (darkTheme) DarkColors else LightColors)) {
                    val store = remember { ProjectStore(this@MainActivity) }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        VisualizerApp(store, settings, darkTheme,
                            onToggleTheme = {
                                darkTheme = !darkTheme
                                settings.edit().putBoolean("dark_theme", darkTheme).apply()
                            },
                            onToggleLanguage = {
                                lang = lang.other
                                settings.edit().putString("language", lang.code).apply()
                            })
                    }
                }
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = StudioOrange, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4D9), onPrimaryContainer = Color(0xFF81321E),
    secondary = Color(0xFF59634F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E6D9), onSecondaryContainer = StudioInk,
    tertiary = Color(0xFF59634F), onTertiary = Color.White,
    background = StudioPaper, onBackground = StudioInk, surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEBEDE5),
    onSurface = StudioInk, onSurfaceVariant = Color(0xFF666B61),
    outline = Color(0xFF9CA395),
    outlineVariant = Color(0xFFE1E4DA),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF7A1A14),
    inverseSurface = Color(0xFF2E332C), inverseOnSurface = Color(0xFFF1F2E9), inversePrimary = Color(0xFFFFA083),
    surfaceTint = StudioOrange,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F5EF),
    surfaceContainer = Color(0xFFF0F2EA), surfaceContainerHigh = Color(0xFFEBEDE5), surfaceContainerHighest = Color(0xFFE5E8DE),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA083), onPrimary = Color(0xFF482113),
    primaryContainer = Color(0xFF493024), onPrimaryContainer = Color(0xFFFFCEBA),
    secondary = Color(0xFFB7C4A8), onSecondary = Color(0xFF243020),
    secondaryContainer = Color(0xFF363E32), onSecondaryContainer = Color(0xFFF1F2E9),
    tertiary = Color(0xFFB7C4A8), onTertiary = Color(0xFF243020),
    background = Color(0xFF151815), onBackground = Color(0xFFF1F2E9), surface = Color(0xFF20241F),
    surfaceVariant = Color(0xFF2A3028),
    onSurface = Color(0xFFF1F2E9), onSurfaceVariant = Color(0xFFA5AD9D),
    outline = Color(0xFF76816E), outlineVariant = Color(0xFF353D31),
    // A cooler red than the salmon accent, so destructive actions never read as primary ones.
    error = Color(0xFFFF6F7D), onError = Color(0xFF4A0A12),
    errorContainer = Color(0xFF5A1C24), onErrorContainer = Color(0xFFFFD9DC),
    inverseSurface = Color(0xFFE4E6DC), inverseOnSurface = StudioInk, inversePrimary = StudioOrange,
    surfaceTint = Color(0xFFFFA083),
    surfaceContainerLowest = Color(0xFF101310), surfaceContainerLow = Color(0xFF1B1F1A),
    surfaceContainer = Color(0xFF20241F), surfaceContainerHigh = Color(0xFF282E25), surfaceContainerHighest = Color(0xFF30372D),
)

// Theme switch cross-fades the palette instead of flashing.
@Composable
private fun animatedScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(320)
    @Composable fun a(color: Color) = animateColorAsState(color, spec, label = "scheme").value
    return target.copy(
        primary = a(target.primary), onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer), onPrimaryContainer = a(target.onPrimaryContainer),
        secondaryContainer = a(target.secondaryContainer),
        background = a(target.background), onBackground = a(target.onBackground),
        surface = a(target.surface), onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant), onSurfaceVariant = a(target.onSurfaceVariant),
        outline = a(target.outline), outlineVariant = a(target.outlineVariant),
        surfaceContainer = a(target.surfaceContainer), surfaceContainerHigh = a(target.surfaceContainerHigh),
    )
}

private const val PROJECTS = "projects"
private const val PROJECT = "project"
private const val DIMENSIONS = "dimensions"
private const val CAMERA = "camera"
private const val REVIEW = "review"
private const val EDIT = "edit"
private const val WELCOME = "welcome"
private const val MANUAL = "manual"
private val depth = mapOf(WELCOME to -1, MANUAL to 1, PROJECTS to 0, PROJECT to 1, DIMENSIONS to 2, CAMERA to 3, REVIEW to 4, EDIT to 5)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun VisualizerApp(store: ProjectStore, settings: android.content.SharedPreferences, darkTheme: Boolean, onToggleTheme: () -> Unit, onToggleLanguage: () -> Unit) {
    val context = LocalContext.current
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(store.all()) }
    var route by rememberSaveable { mutableStateOf(if (settings.getBoolean("welcome_done", false)) PROJECTS else WELCOME) }
    val tutorial = remember { TutorialController(settings) }
    var showAbout by remember { mutableStateOf(false) }
    // Shown once after an update; the About sheet reopens it as the full history.
    val newReleases = remember(lang) { unseenReleases(settings, lang) }
    var showWhatsNew by remember { mutableStateOf(newReleases.isNotEmpty()) }
    var showHistory by remember { mutableStateOf(false) }
    var exportedOnce by remember { mutableStateOf(settings.getBoolean("exported_once", false)) }
    var projectId by rememberSaveable { mutableStateOf("") }
    var photoId by rememberSaveable { mutableStateOf("") }
    var newPhoto by rememberSaveable { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf("") }
    var widthMm by rememberSaveable { mutableStateOf(20f) }
    var heightMm by rememberSaveable { mutableStateOf(30f) }
    var message by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var showDeleteProject by remember { mutableStateOf(false) }
    var showDeletePhoto by remember { mutableStateOf(false) }
    var exportProject by remember { mutableStateOf<Project?>(null) }
    var askZipLabels by remember { mutableStateOf(false) }
    var zipLabels by remember { mutableStateOf(false) }
    var printProfile by remember { mutableStateOf(PrintProfile.load(settings)) }
    var showPrinter by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbar.showSnackbar(message)
            message = ""
        }
    }
    val cameraRoute = route == CAMERA || route == REVIEW
    LaunchedEffect(Unit) { if (newReleases.isEmpty()) markChangelogSeen(settings) }

    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme && !cameraRoute
                isAppearanceLightNavigationBars = !darkTheme && !cameraRoute
            }
        }
    }

    fun refresh() { projects = store.all() }
    val project = projects.firstOrNull { it.id == projectId }
    val photo = project?.photos?.firstOrNull { it.id == photoId }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val selected = exportProject
        if (uri != null && selected != null) {
            scope.launch {
                message = lang.tr("Tworzenie PDF…", "Creating PDF…")
                message = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { PdfExporter.export(selected, store, it, printProfile, lang) }
                            ?: error(lang.tr("Nie można otworzyć pliku docelowego.", "Cannot open the target file."))
                    }
                    exportedOnce = true
                    settings.edit().putBoolean("exported_once", true).apply()
                    lang.tr("PDF zapisany. Drukuj w skali 100%.", "PDF saved. Print at 100% scale.")
                } catch (error: Exception) {
                    lang.tr("Błąd PDF: ", "PDF error: ") + (if (error is UnreadablePhotoException) lang.tr("nie można odczytać jednego ze zdjęć.", "one of the photos cannot be read.") else error.message ?: lang.tr("nieznany błąd", "unknown error"))
                }
            }
        }
    }

    val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val selected = exportProject
        if (uri != null && selected != null) {
            scope.launch {
                message = lang.tr("Tworzenie pliku Excel…", "Creating the Excel file…")
                message = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { XlsxExporter.export(selected, store, it, lang) }
                            ?: error(lang.tr("Nie można otworzyć pliku docelowego.", "Cannot open the target file."))
                    }
                    lang.tr("Plik Excel zapisany.", "Excel file saved.")
                } catch (error: Exception) {
                    lang.tr("Błąd Excela: ", "Excel error: ") + (if (error is UnreadablePhotoException) lang.tr("nie można odczytać jednego ze zdjęć.", "one of the photos cannot be read.") else error.message ?: lang.tr("nieznany błąd", "unknown error"))
                }
            }
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val selected = exportProject
        if (uri != null && selected != null) {
            scope.launch {
                message = lang.tr("Pakowanie zdjęć…", "Packing the photos…")
                message = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { ZipExporter.export(selected, store, it, zipLabels, lang) }
                            ?: error(lang.tr("Nie można otworzyć pliku docelowego.", "Cannot open the target file."))
                    }
                    lang.tr("Archiwum ZIP zapisane.", "ZIP archive saved.")
                } catch (error: Exception) {
                    lang.tr("Błąd ZIP: ", "ZIP error: ") + (if (error is UnreadablePhotoException) lang.tr("nie można odczytać jednego ze zdjęć.", "one of the photos cannot be read.") else error.message ?: lang.tr("nieznany błąd", "unknown error"))
                }
            }
        }
    }

    val testPageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { PdfExporter.exportTestPage(it, printProfile, lang) } ?: error(lang.tr("Nie można otworzyć pliku docelowego.", "Cannot open the target file."))
                }
                lang.tr("Strona testowa zapisana. Drukuj w skali 100%.", "Test page saved. Print at 100% scale.")
            } catch (error: Exception) {
                lang.tr("Błąd PDF: ", "PDF error: ") + (if (error is UnreadablePhotoException) lang.tr("nie można odczytać jednego ze zdjęć.", "one of the photos cannot be read.") else error.message ?: lang.tr("nieznany błąd", "unknown error"))
            }
        }
    }

    fun discardCapture() {
        if (pendingCapture.isNotBlank()) File(pendingCapture).delete()
        pendingCapture = ""
    }
    fun back() {
        route = when (route) {
            REVIEW -> { discardCapture(); CAMERA }
            CAMERA -> DIMENSIONS
            EDIT, DIMENSIONS -> PROJECT
            MANUAL -> PROJECTS
            else -> PROJECTS
        }
    }
    BackHandler(route != PROJECTS && route != WELCOME) { back() }

    val outerBackground by animateColorAsState(if (cameraRoute) CameraBlack else MaterialTheme.colorScheme.background, label = "outerBackground")
    val snackbarLift by animateDpAsState(if (route == PROJECTS || route == PROJECT || route == DIMENSIONS) DockClearance else 24.dp, label = "snackbarLift")

    CompositionLocalProvider(LocalTutorial provides tutorial) {
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().background(outerBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransition provides this, LocalPrintProfile provides printProfile) {
                AnimatedContent(route, Modifier.fillMaxSize(), label = "route", transitionSpec = {
                    val direction = if ((depth[targetState] ?: 0) >= (depth[initialState] ?: 0)) 1 else -1
                    (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 5 * direction } + fadeIn(tween(240, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 8 * direction } + fadeOut(tween(180)))
                }) { current ->
                    CompositionLocalProvider(LocalRouteScope provides this) {
                        when (current) {
                            WELCOME -> WelcomeScreen(tutorial.enabled, tutorial::updateEnabled, onToggleLanguage) {
                                settings.edit().putBoolean("welcome_done", true).apply()
                                route = PROJECTS
                            }
                            MANUAL -> ManualScreen(onBack = ::back)
                            PROJECTS -> ModernProjectsScreen(projects, store, darkTheme, onToggleTheme, onToggleLanguage, onPrinter = { showPrinter = true }, onAbout = { showAbout = true },
                                suggestPrinter = exportedOnce && printProfile == PrintProfile(),
                                onCreate = { showCreate = true }, onOpen = { projectId = it; message = ""; route = PROJECT })
                            PROJECT -> if (project != null) ModernProjectScreen(
                                project, store,
                                onBack = ::back,
                                onAdd = { route = DIMENSIONS; message = "" },
                                onEdit = { photoId = it; newPhoto = false; route = EDIT; message = "" },
                                onExportPdf = {
                                    try {
                                        PdfExporter.layout(project, lang)
                                        exportProject = project
                                        pdfLauncher.launch("${safeFileName(project.name, lang)}_${lang.tr("wycinanka", "cutout")}.pdf")
                                    } catch (error: Exception) { message = error.message ?: lang.tr("Nie można przygotować PDF.", "Cannot prepare the PDF.") }
                                },
                                onExportXlsx = {
                                    exportProject = project
                                    xlsxLauncher.launch("${safeFileName(project.name, lang)}_${lang.tr("wizualizacje", "visualisations")}.xlsx")
                                },
                                onExportZip = { exportProject = project; askZipLabels = true },
                                onDelete = { showDeleteProject = true },
                                onRename = { name -> store.rename(project.id, name); refresh() },
                                onPrinter = { showPrinter = true },
                            )
                            DIMENSIONS -> ModernDimensionsScreen(onBack = ::back, onContinue = { w, h -> widthMm = w; heightMm = h; route = CAMERA })
                            CAMERA -> if (project != null) ModernCameraScreen(
                                widthMm, heightMm, store, project.id,
                                onBack = ::back,
                                onSaved = { file -> pendingCapture = file.path; route = REVIEW },
                                onError = { message = it },
                            )
                            REVIEW -> if (project != null && pendingCapture.isNotBlank()) ModernReviewScreen(
                                File(pendingCapture), widthMm, heightMm,
                                onRetake = ::back,
                                onUse = {
                                    try {
                                        val added = store.addPhoto(project.id, File(pendingCapture), widthMm, heightMm)
                                        pendingCapture = ""
                                        refresh()
                                        photoId = added.id
                                        newPhoto = true
                                        route = EDIT
                                    } catch (error: Exception) { message = error.message ?: lang.tr("Nie zapisano zdjęcia.", "The photo was not saved.") }
                                },
                            )
                            EDIT -> if (project != null && photo != null) ModernPhotoEditor(
                                photo, store.photoFile(project.id, photo), newPhoto,
                                onBack = ::back,
                                onSave = { updated -> store.updatePhoto(project.id, updated); refresh(); route = PROJECT; message = lang.tr("Zdjęcie zapisane.", "Photo saved.") },
                                onDelete = { showDeletePhoto = true },
                                onPrinter = { showPrinter = true },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = snackbarLift))
    }
    TutorialOverlay(tutorial)
    }
    }

    if (showAbout) {
        CompositionLocalProvider(LocalTutorial provides tutorial) {
            AboutSheet(tutorial, onManual = { showAbout = false; route = MANUAL }, onChangelog = { showAbout = false; showHistory = true },
                onWelcome = { showAbout = false; route = WELCOME }, onDismiss = { showAbout = false })
        }
    }
    // A fresh install starts on the welcome screen, so the list of changes waits until the app itself is introduced.
    if (showWhatsNew && route != WELCOME) {
        ChangelogSheet(newReleases, history = false, onDismiss = { showWhatsNew = false; markChangelogSeen(settings) })
    }
    if (showHistory) ChangelogSheet(changelog(lang), history = true, onDismiss = { showHistory = false })
    if (showPrinter) {
        CompositionLocalProvider(LocalPrintProfile provides printProfile) {
            PrinterSheet(printProfile, onChange = { printProfile = it; it.save(settings) },
                onTestPage = { testPageLauncher.launch(lang.tr("strona-testowa-drukarki.pdf", "printer-test-page.pdf")) }, onDismiss = { showPrinter = false })
        }
    }
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            icon = { StudioIcon(StudioSymbol.Folder, color = MaterialTheme.colorScheme.primary) },
            title = { Text(tr("Nowy projekt", "New project")) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(tr("Nazwa projektu", "Project name")) }, placeholder = { Text(tr("np. Rama montażowa A12", "e.g. Assembly frame A12")) }, shape = RoundedCornerShape(16.dp), singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                val created = store.create(name)
                refresh(); projectId = created.id; route = PROJECT; showCreate = false; name = ""
            }) { Text(tr("Utwórz", "Create")) } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(tr("Anuluj", "Cancel")) } },
        )
    }
    if (showDeleteProject && project != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProject = false },
            title = { Text(tr("Usunąć projekt?", "Delete project?")) },
            text = { Text(tr("Zdjęcia i dane projektu zostaną trwale usunięte z telefonu.", "The project's photos and data will be permanently deleted from the phone.")) },
            confirmButton = { TextButton(onClick = { store.delete(project.id); refresh(); showDeleteProject = false; route = PROJECTS }) { Text(tr("Usuń", "Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteProject = false }) { Text(tr("Anuluj", "Cancel")) } },
        )
    }
    if (askZipLabels && project != null) {
        fun pack(labels: Boolean) {
            askZipLabels = false
            zipLabels = labels
            zipLauncher.launch("${safeFileName(project.name, lang)}_${lang.tr("zdjecia", "photos")}.zip")
        }
        AlertDialog(
            onDismissRequest = { askZipLabels = false },
            icon = { StudioIcon(StudioSymbol.Photo) },
            title = { Text(tr("PN i nr kroku na zdjęciach?", "PN and step on the photos?")) },
            text = { Text(tr("Każde zdjęcie trafi do archiwum w swoim kadrze, z nazwą PN_nrKroku. Oznaczenia mogą być widoczne na zdjęciach tak jak na wycinance.",
                "Each photo goes into the archive in its own framing, named PN_step. The labels can be shown on the photos just like on the cut-out sheet.")) },
            confirmButton = { TextButton(onClick = { pack(true) }) { Text(tr("Tak, z oznaczeniami", "Yes, with labels")) } },
            dismissButton = { TextButton(onClick = { pack(false) }) { Text(tr("Nie, bez oznaczeń", "No, without labels")) } },
        )
    }
    if (showDeletePhoto && project != null && photo != null) {
        AlertDialog(
            onDismissRequest = { showDeletePhoto = false },
            title = { Text(tr("Usunąć zdjęcie?", "Delete photo?")) },
            text = { Text(tr("Oryginalne zdjęcie zostanie trwale usunięte.", "The original photo will be permanently deleted.")) },
            confirmButton = { TextButton(onClick = { store.deletePhoto(project.id, photo.id); refresh(); showDeletePhoto = false; route = PROJECT }) { Text(tr("Usuń", "Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeletePhoto = false }) { Text(tr("Anuluj", "Cancel")) } },
        )
    }
}

private fun safeFileName(name: String, lang: Lang) = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(60).ifBlank { lang.tr("projekt", "project") }
