package pl.visualizer.montaz

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun photoCount(count: Int, lang: Lang) = lang.count(count, "zdjęcie", "zdjęcia", "zdjęć", "photo", "photos")

@Composable
private fun BrandTopBar(darkTheme: Boolean, onToggleTheme: () -> Unit, onToggleLanguage: () -> Unit, onPrinter: () -> Unit, onAbout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Four round buttons plus the wordmark only fit side by side from ~400 dp; below that the "/ studio" part is dropped.
    val roomy = maxWidth >= 400.dp
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onAbout).tutorialTarget("projects.about").padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioLogo()
            Spacer(Modifier.width(10.dp))
            Text("frame", fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            if (roomy) Text(" / studio", fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        HelpButton("projects", Tours.projects(lang), Modifier.tutorialTarget("projects.help"))
        Spacer(Modifier.width(4.dp))
        StudioIconButton(StudioSymbol.Print, tr("Ustawienia drukarki", "Printer settings"), onPrinter, Modifier.tutorialTarget("projects.printer"))
        Spacer(Modifier.width(4.dp))
        StudioCircleButton(tr("Zmień język na angielski", "Switch language to Polish"), onToggleLanguage, Modifier.tutorialTarget("projects.language")) {
            Crossfade(lang, label = "languageLabel") { current ->
                Text(current.code.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
        }
        Spacer(Modifier.width(4.dp))
        val rotation by animateFloatAsState(if (darkTheme) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "themeRotation")
        StudioCircleButton(if (darkTheme) tr("Włącz jasny motyw", "Switch to light theme") else tr("Włącz ciemny motyw", "Switch to dark theme"), onToggleTheme, Modifier.tutorialTarget("projects.theme")) {
            Crossfade(darkTheme, label = "themeIcon") { dark ->
                StudioIcon(if (dark) StudioSymbol.Sun else StudioSymbol.Moon, Modifier.size(22.dp).graphicsLayer { rotationZ = rotation })
            }
        }
    }
    }
}

// Greets by time of day and says where the user left off; a first-time user is welcomed and pointed at the first step.
@Composable
private fun Greeting(projects: List<Project>) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val title = when {
        projects.isEmpty() -> tr("Witaj w frame / studio", "Welcome to frame / studio")
        hour in 5..11 -> tr("Dzień dobry", "Good morning")
        hour in 12..17 -> tr("Dzień dobry", "Good afternoon")
        else -> tr("Dobry wieczór", "Good evening")
    }
    val latest = projects.firstOrNull()
    val subtitle = when {
        latest == null -> tr("Utwórz pierwszy projekt, aby dodać zdjęcia ramy.", "Create your first project to add photos of a frame.")
        projects.size == 1 -> tr("Masz jeden projekt: „${latest.name}”.", "You have one project: “${latest.name}”.")
        else -> "${lang.count(projects.size, "projekt", "projekty", "projektów", "project", "projects")}. " +
            tr("Najnowszy: „${latest.name}”.", "Latest: “${latest.name}”.")
    }
    val appear = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(appear, enter = fadeIn(androidx.compose.animation.core.tween(500)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(500)) { it / 3 }) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedContent(title, label = "greetingTitle", transitionSpec = { fadeIn() togetherWith fadeOut() }) { StudioTitle(it) }
            Text(subtitle, color = colors.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ModernProjectsScreen(projects: List<Project>, store: ProjectStore, darkTheme: Boolean, onToggleTheme: () -> Unit, onToggleLanguage: () -> Unit, onPrinter: () -> Unit, onAbout: () -> Unit, suggestPrinter: Boolean, onCreate: () -> Unit, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    TutorialTour("projects", Tours.projects(lang))
    SmartHint("hint.printer", suggestPrinter, TutorialStep("projects.printer", tr("Wydruk za ciemny lub nieostry?", "Print too dark or soft?"),
        tr("Większość drukarek przyciemnia tony średnie. Wydrukuj stronę testową z ustawień drukarki i dobierz rozjaśnienie raz dla wszystkich zdjęć.",
            "Most printers darken the midtones. Print the test page from the printer settings and pick the brightening once for all photos.")))
    Column(Modifier.fillMaxSize()) {
        BrandTopBar(darkTheme, onToggleTheme, onToggleLanguage, onPrinter, onAbout)
        Greeting(projects)
        if (projects.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                StudioEmpty(tr("Miejsce na pierwszy projekt", "Room for your first project"),
                    tr("Zbierz zdjęcia montażowe w jednym miejscu.\nOd pomiaru do gotowego arkusza.", "Keep assembly photos in one place.\nFrom measuring to a ready sheet."))
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                lazyItemsIndexed(projects, key = { _, project -> project.id }) { index, project ->
                    ProjectCover(project, store, Modifier.animateItem().then(if (index == 0) Modifier.tutorialTarget("projects.card") else Modifier)) { onOpen(project.id) }
                }
            }
        }
        StudioDock {
            StudioAction(tr("Nowy projekt", "New project"), StudioSymbol.Plus, onCreate, Modifier.fillMaxWidth().tutorialTarget("projects.new"))
        }
    }
}

@Composable
private fun ProjectCover(project: Project, store: ProjectStore, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    val date = remember(project.createdAt, lang) { SimpleDateFormat("d MMM yyyy", lang.locale).format(Date(project.createdAt)) }
    val source = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().pressScale(source, 0.98f).clip(RoundedCornerShape(24.dp)).background(colors.surface)
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
        .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(StudioInk)) {
            val photos = project.photos.takeLast(3)
            if (photos.isNotEmpty()) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    photos.forEachIndexed { index, photo ->
                        StudioPhoto(photo, store.photoFile(project.id, photo), Modifier.weight(if (index == 0) 1.65f else 1f).fillMaxHeight())
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.60f)))))
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val gap = 24.dp.toPx()
                    var x = 0f
                    while (x < size.width) { drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height)); x += gap }
                    var y = 0f
                    while (y < size.height) { drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y)); y += gap }
                }
                StudioIcon(StudioSymbol.Folder, Modifier.align(Alignment.Center).size(44.dp), Color(0xFFA6B096))
            }
            Text(photoCount(project.photos.size, lang), Modifier.align(Alignment.BottomStart).padding(16.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            StudioIcon(StudioSymbol.Arrow, Modifier.align(Alignment.BottomEnd).padding(20.dp).size(20.dp), Color.White)
        }
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(project.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun ModernProjectScreen(project: Project, store: ProjectStore, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit, onExport: () -> Unit, onDelete: () -> Unit, onRename: (String) -> Unit, onPrinter: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var newName by remember(project.id) { mutableStateOf(project.name) }
    val lang = LocalLang.current
    val pageCount = remember(project) { runCatching { PdfExporter.layout(project).size }.getOrNull() }
    TutorialTour("project", Tours.project(lang))
    SmartHint("hint.pdf", project.photos.size >= 2, TutorialStep("project.pdf", tr("Gotowe do druku?", "Ready to print?"),
        tr("Zapisz PDF i drukuj w skali 100%, bez dopasowania do strony. Linijką 50 mm w nagłówku arkusza sprawdzisz skalę.",
            "Save the PDF and print at 100% scale, without fit to page. The 50 mm ruler in the sheet header lets you check the scale.")))
    Column(Modifier.fillMaxSize()) {
        StudioTopBar(onBack) {
            HelpButton("project", Tours.project(lang))
            Box {
                StudioIconButton(StudioSymbol.More, tr("Opcje projektu", "Project options"), { menuOpen = true }, Modifier.tutorialTarget("project.menu"))
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(tr("Ustawienia drukarki", "Printer settings")) }, leadingIcon = { StudioIcon(StudioSymbol.Print) }, onClick = { menuOpen = false; onPrinter() })
                    DropdownMenuItem(text = { Text(tr("Zmień nazwę", "Rename")) }, leadingIcon = { StudioIcon(StudioSymbol.Edit) }, onClick = { menuOpen = false; newName = project.name; renaming = true })
                    DropdownMenuItem(text = { Text(tr("Usuń projekt", "Delete project"), color = MaterialTheme.colorScheme.error) }, leadingIcon = { StudioIcon(StudioSymbol.Trash, color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StudioTitle(project.name)
            Row(Modifier.tutorialTarget("project.chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip(StudioSymbol.Photo, photoCount(project.photos.size, lang))
                MetricChip(StudioSymbol.Print, pageCount?.let { lang.count(it, "arkusz A4", "arkusze A4", "arkuszy A4", "A4 sheet", "A4 sheets") } ?: tr("Brak arkuszy", "No sheets"))
            }
        }
        if (project.photos.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                StudioEmpty(tr("Zacznij od pierwszego kadru", "Start with the first shot"), tr("Zmierz miejsce na ramie i dodaj zdjęcie.\nKażde ujęcie zachowa swój wymiar.", "Measure the spot on the frame and add a photo.\nEach shot keeps its own size."))
            }
        } else {
            // Tiles keep each photo's real proportion; extreme strips are clamped so the grid stays scannable.
            LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(148.dp), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = 20.dp) {
                itemsIndexed(project.photos, key = { _, photo -> photo.id }) { index, photo ->
                    PhotoTile(index, photo, store, project.id, Modifier.animateItem().then(if (index == 0) Modifier.tutorialTarget("project.tile") else Modifier)) { onEdit(photo.id) }
                }
            }
        }
        StudioDock {
            StudioAction("PDF", StudioSymbol.Print, onExport, Modifier.weight(0.85f).tutorialTarget("project.pdf"), enabled = project.photos.isNotEmpty(), secondary = true)
            StudioAction(tr("Dodaj zdjęcie", "Add photo"), StudioSymbol.Plus, onAdd, Modifier.weight(1.2f).tutorialTarget("project.add"))
        }
    }
    if (renaming) AlertDialog(onDismissRequest = { renaming = false }, icon = { StudioIcon(StudioSymbol.Edit) }, title = { Text(tr("Nazwa projektu", "Project name")) },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true, shape = RoundedCornerShape(16.dp)) },
        confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = { onRename(newName); renaming = false }) { Text(tr("Zapisz", "Save")) } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text(tr("Anuluj", "Cancel")) } })
}

@Composable
private fun PhotoTile(index: Int, photo: PhotoItem, store: ProjectStore, projectId: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.pressScale(source, 0.97f).clip(shape).clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick).padding(bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio((photo.widthMm / photo.heightMm).coerceIn(0.6f, 1.6f)).sharedPhoto(photo.id, shape).clip(shape)) {
            StudioPhoto(photo, store.photoFile(projectId, photo), Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)))))
            Text((index + 1).toString().padStart(2, '0'), Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
            if (photo.steps.isNotBlank()) Text(photo.steps, Modifier.align(Alignment.BottomStart).padding(10.dp).background(LabelYellow, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
                color = StudioInk, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(mmText(photo.widthMm, photo.heightMm), Modifier.padding(top = 10.dp, start = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
        Text(photo.pn.ifBlank { tr("Bez numeru PN", "No PN number") }, Modifier.padding(top = 3.dp, start = 6.dp), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, style = TabularNumbers)
    }
}

@Composable
internal fun MetricChip(symbol: StudioSymbol, text: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.background(colors.surfaceVariant, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioIcon(symbol, Modifier.size(14.dp), colors.onSurfaceVariant)
        Text(text, color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ModernDimensionsScreen(onBack: () -> Unit, onContinue: (Float, Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var width by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val w = width.replace(',', '.').toFloatOrNull()
    val h = height.replace(',', '.').toFloatOrNull()
    val widthOk = w != null && w > 0f && w <= 190f
    val heightOk = h != null && h > 0f && h <= 269f
    val valid = widthOk && heightOk
    val showError = (width.isNotBlank() && !widthOk) || (height.isNotBlank() && !heightOk)
    Column(Modifier.fillMaxSize()) {
        val lang = LocalLang.current
        StudioTopBar(onBack, center = { FlowSteps(1, Modifier.tutorialTarget("dims.steps")) }) { HelpButton("dimensions", Tours.dimensions(lang)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioTitle(tr("Dobry kadr zaczyna\nsię od wymiaru.", "A good shot starts\nwith a measurement."))
                Text(tr("Wpisz zmierzone miejsce na ramie.", "Enter the measured spot on the frame."), color = colors.onSurfaceVariant, fontSize = 14.sp)
            }
            TutorialTour("dimensions", Tours.dimensions(lang))
            DimensionDrawing(if (valid) w!! / h!! else 2f / 3f, if (valid) mmText(w!!, h!!) else tr("Twój kadr", "Your shot"))
            Row(Modifier.fillMaxWidth().tutorialTarget("dims.fields"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                DimensionField(tr("Szerokość", "Width"), width, { width = it }, Modifier.weight(1f), ImeAction.Next)
                StudioIconButton(StudioSymbol.Swap, tr("Zamień szerokość z wysokością", "Swap width and height"), { val old = width; width = height; height = old }, Modifier.padding(bottom = 4.dp).tutorialTarget("dims.swap"))
                DimensionField(tr("Wysokość", "Height"), height, { height = it }, Modifier.weight(1f), ImeAction.Done)
            }
            Row(Modifier.fillMaxWidth().tutorialTarget("dims.presets"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(20 to 30, 30 to 40, 50 to 70).forEach { (presetW, presetH) ->
                    val selected = w == presetW.toFloat() && h == presetH.toFloat()
                    val background by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceVariant, label = "presetBg")
                    val content by animateColorAsState(if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant, label = "presetText")
                    val source = remember { MutableInteractionSource() }
                    Box(Modifier.weight(1f).height(44.dp).pressScale(source).clip(RoundedCornerShape(12.dp)).background(background)
                        .clickable(interactionSource = source, indication = LocalIndication.current) { width = presetW.toString(); height = presetH.toString(); focus.clearFocus() },
                        contentAlignment = Alignment.Center) {
                        Text("$presetW × $presetH", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = content, style = TabularNumbers)
                    }
                }
            }
            AnimatedVisibility(showError,enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(tr("Podaj dodatnie wymiary, maks. 190 × 269 mm.", "Enter positive sizes, max. 190 × 269 mm."), color = colors.error, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioIcon(StudioSymbol.Info, Modifier.size(17.dp), colors.onSurfaceVariant)
                Text(tr("Wymiary w milimetrach określają rozmiar wydruku. Zmiana rozmiaru wymaga nowego zdjęcia.", "Sizes in millimetres set the printed size. Changing the size needs a new photo."), color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
        StudioDock {
            StudioAction(tr("Przejdź do aparatu", "Open camera"), StudioSymbol.Camera, { focus.clearFocus(); onContinue(w!!, h!!) }, Modifier.fillMaxWidth().tutorialTarget("dims.go"), enabled = valid)
        }
    }
}

@Composable
private fun DimensionField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier, action: ImeAction) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onValue, modifier = modifier, singleLine = true, label = { Text(label) }, placeholder = { Text("0") },
        suffix = { Text("mm", fontSize = 12.sp) }, shape = RoundedCornerShape(16.dp),
        textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = action),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
}

// The frame springs to the new proportion while the user types, so the shape change is visible.
@Composable
private fun DimensionDrawing(ratio: Float, title: String) {
    val colors = MaterialTheme.colorScheme
    val animated by animateFloatAsState(ratio, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow), label = "frameRatio")
    BoxWithConstraints(Modifier.fillMaxWidth().height(192.dp).tutorialTarget("dims.drawing").clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant)) {
        val areaHeight = maxHeight - 64.dp
        val frameHeight = minOf(areaHeight, (maxWidth - 64.dp) / animated).coerceAtLeast(4.dp)
        val frameWidth = (frameHeight * animated).coerceAtLeast(4.dp)
        Canvas(Modifier.fillMaxSize()) {
            val gap = 16.dp.toPx()
            var x = gap
            while (x < size.width) {
                var y = gap
                while (y < size.height) { drawCircle(colors.outline.copy(alpha = 0.32f), 0.7.dp.toPx(), Offset(x, y)); y += gap }
                x += gap
            }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 20.dp + (areaHeight - frameHeight) / 2).width(frameWidth).height(frameHeight)
            .background(colors.surface, RoundedCornerShape(5.dp)).border(1.5.dp, colors.primary, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            if (frameWidth > 28.dp && frameHeight > 28.dp) StudioIcon(StudioSymbol.Photo, Modifier.size(22.dp), colors.primary)
        }
        Text(title, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), fontSize = 13.sp, color = colors.primary, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
    }
}
