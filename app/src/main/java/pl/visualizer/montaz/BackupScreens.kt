package pl.visualizer.montaz

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** What the app reacts to; queued so nothing is lost while the activity is recreated. */
internal sealed interface BackupEvent {
    data class Message(val text: String) : BackupEvent
    data object Refresh : BackupEvent
    data object Browse : BackupEvent
    data object LeaveBrowse : BackupEvent
    /** Projects and settings were replaced: the screen restarts to read the restored settings. */
    data object Restored : BackupEvent
}

/**
 * Backup state and the long-running backup actions. One per process rather than per screen, so turning the phone
 * while browsing or restoring keeps the open archive, and work in progress finishes and reports back.
 */
@Stable
internal class BackupController private constructor(private val context: Context) {
    val backup = AutoBackup.get(context)
    private val store = ProjectStore(context) { backup.requestSync() }
    private val settings = context.getSharedPreferences(BackupFormat.SETTINGS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var sheet by mutableStateOf(false)
    var archive by mutableStateOf<BackupArchive?>(null)
        private set
    /** The open archive is the `backup.zip` of the backup folder: automatic writes wait while it is open. */
    private var archiveIsAutoFile = false
    var foreign by mutableStateOf<ForeignBackup?>(null)
    var progress by mutableStateOf<String?>(null)
    val events = mutableStateListOf<BackupEvent>()

    // Set by BackupHost each composition: the system pickers belong to the current activity.
    internal var enable: () -> Unit = {}
    internal var openFile: () -> Unit = {}
    internal var exportArchive: () -> Unit = {}

    private fun message(text: String) { events += BackupEvent.Message(text) }

    private suspend fun <T> busy(text: String, block: suspend () -> T): T {
        progress = text
        try { return block() } finally { progress = null }
    }

    private fun setArchive(opened: BackupArchive?, autoFile: Boolean) {
        archive?.close()
        val wasAutoFile = archiveIsAutoFile
        archive = opened
        archiveIsAutoFile = opened != null && autoFile
        if (wasAutoFile && !archiveIsAutoFile) backup.resume()
    }

    fun closeArchive() = setArchive(null, autoFile = false)

    fun openArchive(uri: Uri, autoFile: Boolean, lang: Lang) = scope.launch {
        try {
            val opened = busy(lang.tr("Otwieranie kopii…", "Opening the backup…")) {
                if (autoFile) backup.pause()
                withContext(Dispatchers.IO) { BackupArchive.open(context, uri) }
            }
            setArchive(opened, autoFile)
            sheet = false
            foreign = null
            events += BackupEvent.Browse
        } catch (error: Exception) {
            if (autoFile && !archiveIsAutoFile) backup.resume()
            message(if (error is NotABackupException) lang.tr("To nie jest plik kopii Wizualizatora ramy albo jest uszkodzony.", "This is not a Frame visualizer backup file, or it is damaged.")
                else lang.tr("Nie można otworzyć kopii: ", "Cannot open the backup: ") + (error.message ?: ""))
        }
    }

    fun openAutoFile(known: Uri?, lang: Lang) = scope.launch {
        val uri = known ?: backup.backupFileUri()
        if (uri == null) message(lang.tr("W folderze nie ma jeszcze kopii.", "There is no backup in the folder yet.")) else openArchive(uri, autoFile = true, lang)
    }

    fun connect(tree: Uri, lang: Lang) = scope.launch {
        try {
            when (val result = busy(lang.tr("Sprawdzanie folderu…", "Checking the folder…")) { backup.connect(tree) }) {
                AutoBackup.Connection.Fresh, AutoBackup.Connection.Ours -> message(lang.tr("Kopia automatyczna włączona.", "Automatic backup is on."))
                is AutoBackup.Connection.Foreign -> {
                    val info = withContext(Dispatchers.IO) { runCatching { BackupArchive.open(context, result.file).use { it.info } }.getOrNull() }
                    foreign = ForeignBackup(result.file, info)
                }
            }
        } catch (error: Exception) {
            message(lang.tr("Nie można użyć tego folderu: ", "Cannot use this folder: ") + (error.message ?: ""))
        }
    }

    fun export(uri: Uri, lang: Lang) = scope.launch {
        message(try {
            busy(lang.tr("Zapisywanie archiwum…", "Saving the archive…")) { backup.exportArchive(uri, lang) }
            lang.tr("Archiwum zapisane. Na komputerze rozpakuj je i otwórz index.html.", "Archive saved. On a computer, unpack it and open index.html.")
        } catch (error: Exception) {
            lang.tr("Błąd archiwum: ", "Archive error: ") + (error.message ?: lang.tr("nieznany błąd", "unknown error"))
        })
    }

    fun keepForeign(lang: Lang) = scope.launch {
        val info = foreign?.info
        val ok = busy(lang.tr("Zmiana nazwy starej kopii…", "Renaming the old backup…")) { backup.keepForeignAsArchive(info?.createdAt) }
        foreign = null
        message(if (ok) lang.tr("Stara kopia została w folderze jako archiwum. Zaczynam nową.", "The old backup stays in the folder as an archive. Starting a new one.")
            else lang.tr("Nie udało się zmienić nazwy starej kopii.", "Could not rename the old backup."))
    }

    fun restoreAll(lang: Lang) {
        val opened = archive ?: return
        val autoFile = archiveIsAutoFile
        scope.launch {
            try {
                val (projects, photos) = busy(lang.tr("Przywracanie projektów…", "Restoring projects…")) {
                    withContext(Dispatchers.IO) {
                        var photos = 0
                        opened.projects.forEach { project -> photos += store.importProject(project) { opened.photoBytes(project.id, it) } }
                        opened.restoreSettings(settings)
                        opened.projects.size to photos
                    }
                }
                val info = opened.info
                val complete = projects == info.listedProjects && photos == info.listedPhotos
                // Taking the backup file over makes it a mirror of the phone; only a complete restore may do that,
                // or whatever could not be read would be erased from the only copy that still has it.
                if (autoFile && complete) backup.adopt(info)
                closeArchive()
                events += BackupEvent.Restored
                if (!complete) message(lang.tr("Przywrócono ${photoCount(photos, lang)} z ${info.listedPhotos} — część kopii jest uszkodzona. ", "Restored ${photoCount(photos, lang)} of ${info.listedPhotos} — part of the backup is damaged. ") +
                    if (autoFile) lang.tr("Kopia automatyczna nie nadpisze tego pliku.", "The automatic backup will not overwrite this file.") else "")
            } catch (error: Exception) {
                events += BackupEvent.Refresh
                message(lang.tr("Błąd przywracania: ", "Restore error: ") + (error.message ?: ""))
            }
        }
    }

    fun importSelected(choices: Map<String, ImportMode>, lang: Lang) {
        val opened = archive ?: return
        scope.launch {
            try {
                val photos = busy(lang.tr("Wgrywanie projektów…", "Loading projects…")) {
                    withContext(Dispatchers.IO) {
                        opened.projects.filter { it.id in choices }.sumOf { project ->
                            BackupImport.import(store, opened, project, store.get(project.id), choices.getValue(project.id), lang)
                        }
                    }
                }
                closeArchive()
                events += BackupEvent.Refresh
                events += BackupEvent.LeaveBrowse
                message(lang.tr("Wgrano: ", "Loaded: ") + lang.count(choices.size, "projekt", "projekty", "projektów", "project", "projects") + ", " + photoCount(photos, lang) + ".")
            } catch (error: Exception) {
                events += BackupEvent.Refresh
                message(lang.tr("Błąd wgrywania: ", "Loading error: ") + (error.message ?: ""))
            }
        }
    }

    companion object {
        @Volatile private var instance: BackupController? = null
        fun get(context: Context): BackupController = instance ?: synchronized(this) {
            instance ?: BackupController(context.applicationContext).also { instance = it }
        }
    }
}

internal data class ForeignBackup(val file: Uri, val info: BackupInfo?)

/**
 * The system pickers and the backup dialogs. Placed once in the app so a picker's result still arrives after the
 * sheet that launched it has closed.
 */
@Composable
internal fun BackupHost(hasProjects: Boolean): BackupController {
    val context = LocalContext.current
    val lang = LocalLang.current
    val controller = remember { BackupController.get(context) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) controller.connect(uri, lang) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) controller.openArchive(uri, autoFile = false, lang) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) controller.export(uri, lang) }
    controller.enable = { treeLauncher.launch(AutoBackup.documentsUri) }
    controller.openFile = { openLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) }
    controller.exportArchive = { exportLauncher.launch("wizualizator_${SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date())}.zip") }

    val status by controller.backup.status.collectAsState()
    if (controller.sheet) BackupSheet(controller, status, onDismiss = { controller.sheet = false })
    controller.foreign?.let { found -> ForeignBackupDialog(controller, found, hasProjects) }
    controller.progress?.let { text ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        })
    }
    return controller
}

@Composable
private fun ForeignBackupDialog(controller: BackupController, found: ForeignBackup, hasProjects: Boolean) {
    val lang = LocalLang.current
    val info = found.info
    AlertDialog(
        onDismissRequest = { controller.foreign = null },
        icon = { StudioIcon(StudioSymbol.Archive, color = MaterialTheme.colorScheme.primary) },
        title = { Text(if (info != null) tr("Znaleziono kopię", "Backup found") else tr("Nieznany plik kopii", "Unknown backup file")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (info != null) lang.tr("Kopia z ${backupDateText(info.createdAt, lang)} (wersja ${info.appVersion}): ", "Backup from ${backupDateText(info.createdAt, lang)} (version ${info.appVersion}): ") +
                    lang.count(info.projectCount, "projekt", "projekty", "projektów", "project", "projects") + ", " + photoCount(info.photoCount, lang) + ". " +
                    tr("Kopia automatyczna jej nie nadpisze.", "The automatic backup will not overwrite it.")
                else tr("W folderze jest plik backup.zip, którego nie da się odczytać jako kopii. Kopia automatyczna go nie nadpisze.",
                    "The folder holds a backup.zip that cannot be read as a backup. The automatic backup will not overwrite it."))
                if (info != null) StudioAction(if (hasProjects) tr("Przejrzyj i wybierz", "Browse and choose") else tr("Przejrzyj i przywróć", "Browse and restore"),
                    StudioSymbol.Download, { controller.openAutoFile(found.file, lang) }, Modifier.fillMaxWidth())
                StudioAction(tr("Zachowaj ją i zacznij nową", "Keep it and start a new one"), StudioSymbol.Archive, { controller.keepForeign(lang) }, Modifier.fillMaxWidth(), secondary = true)
            }
        },
        confirmButton = { TextButton(onClick = { controller.foreign = null }) { Text(tr("Później", "Later")) } },
    )
}

/** "3 min ago", "at 14:05", or a date; refreshed every half minute. */
@Composable
private fun agoText(millis: Long): String {
    val lang = LocalLang.current
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val minutes = (now - millis) / 60_000
    return when {
        minutes < 1 -> lang.tr("przed chwilą", "just now")
        minutes < 60 -> lang.tr("$minutes min temu", "$minutes min ago")
        android.text.format.DateUtils.isToday(millis) -> lang.tr("dziś o ", "today at ") + SimpleDateFormat("HH:mm", lang.locale).format(Date(millis))
        else -> backupDateText(millis, lang)
    }
}

/** One line under the greeting: is the work safe? Tapping it opens the backup sheet. */
@Composable
internal fun BackupStrip(status: AutoBackup.Status, onOpen: () -> Unit, onArchive: () -> Unit, onDismissWarning: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    val warning = status.mode == AutoBackup.Mode.Blocked || status.problem != null
    val (symbol, text) = when {
        status.mode == AutoBackup.Mode.Off -> StudioSymbol.Shield to tr("Kopia automatyczna wyłączona · Włącz", "Automatic backup is off · Turn on")
        status.mode == AutoBackup.Mode.Blocked -> StudioSymbol.Warning to tr("W folderze kopii jest inna kopia · Zdecyduj", "Another backup is in the backup folder · Decide")
        status.problem == AutoBackup.Problem.NoAccess -> StudioSymbol.Warning to tr("Kopia nie zapisuje się: brak dostępu do folderu", "Backup is not saving: no folder access")
        status.problem != null -> StudioSymbol.Warning to tr("Kopia nie zapisała się · Szczegóły", "Backup failed · Details")
        status.running -> StudioSymbol.Shield to tr("Zapisywanie kopii…", "Saving backup…")
        status.lastSuccess == 0L -> StudioSymbol.Shield to tr("Kopia automatyczna włączona", "Automatic backup is on")
        else -> StudioSymbol.Check to tr("Kopia", "Backup") + " ${agoText(status.lastSuccess)} · ${sizeText(status.size, lang)}" +
            if (status.lowSpace) tr(" · mało miejsca", " · low storage") else ""
    }
    val tint = if (warning) colors.error else colors.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clip(CircleShape).background(if (warning) colors.errorContainer else colors.surfaceVariant).clickable(onClick = onOpen)
            .tutorialTarget("projects.backup").padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon(symbol, Modifier.size(15.dp), if (warning) colors.onErrorContainer else tint)
            Spacer(Modifier.width(7.dp))
            Text(text, color = if (warning) colors.onErrorContainer else tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(status.showSizeWarning) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.primaryContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(lang.tr("Kopia ma już ${sizeText(status.size, lang)}", "The backup is now ${sizeText(status.size, lang)}"), color = colors.onPrimaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(tr("Zrób archiwum i przenieś je na komputer. Potem możesz usunąć stare projekty z telefonu — kopia automatyczna się zmniejszy, a archiwum zawsze da się przejrzeć i wgrać z powrotem.",
                    "Make an archive and move it to a computer. Then you can delete old projects from the phone — the automatic backup shrinks, and the archive can always be browsed and loaded back."),
                    color = colors.onPrimaryContainer, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioAction(tr("Zrób archiwum", "Make archive"), StudioSymbol.Upload, onArchive, Modifier.weight(1f))
                    StudioAction(tr("Później", "Later"), StudioSymbol.Close, onDismissWarning, Modifier.weight(1f), secondary = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSheet(controller: BackupController, status: AutoBackup.Status, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    val backup = controller.backup
    var confirmDisable by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Kopia zapasowa", "Backup"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                Text(tr("Projekty, zdjęcia i ustawienia w jednym pliku ZIP. Na komputerze rozpakuj go i otwórz index.html — zobaczysz wszystkie projekty w przeglądarce.",
                    "Projects, photos and settings in one ZIP file. On a computer, unpack it and open index.html to see every project in a browser."),
                    color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
            }

            SectionTitle(tr("Kopia automatyczna", "Automatic backup"))
            when (status.mode) {
                AutoBackup.Mode.Off -> {
                    Hint(tr("Aktualizuje się sama po każdej zmianie, w jednym pliku Documents/${AutoBackup.FOLDER}/${AutoBackup.FILE}. Po ponownej instalacji aplikacji wskaż ten sam folder, a projekty wrócą.",
                        "Updates itself after every change, in one file: Documents/${AutoBackup.FOLDER}/${AutoBackup.FILE}. After reinstalling the app, pick the same folder and your projects come back."))
                    Hint(tr("Telefon zapyta o folder: zostań w Documents (albo wejdź do ${AutoBackup.FOLDER}) i dotknij „Użyj tego folderu”.",
                        "The phone asks for a folder: stay in Documents (or open ${AutoBackup.FOLDER}) and tap “Use this folder”."))
                    StudioAction(tr("Włącz kopię automatyczną", "Turn on automatic backup"), StudioSymbol.Shield, controller.enable, Modifier.fillMaxWidth())
                }
                AutoBackup.Mode.Blocked -> {
                    Hint(tr("W folderze ${status.folder} jest kopia z innej instalacji. Kopia automatyczna jej nie nadpisze — przejrzyj ją albo zachowaj jako archiwum i zacznij nową.",
                        "The folder ${status.folder} holds a backup from another install. The automatic backup will not overwrite it — browse it, or keep it as an archive and start a new one."), warning = true)
                    StudioAction(tr("Przejrzyj tę kopię", "Browse this backup"), StudioSymbol.Download, { controller.openAutoFile(null, lang) }, Modifier.fillMaxWidth())
                    StudioAction(tr("Zachowaj ją i zacznij nową", "Keep it and start a new one"), StudioSymbol.Archive, { controller.keepForeign(lang) }, Modifier.fillMaxWidth(), secondary = true)
                }
                AutoBackup.Mode.On -> {
                    InfoRow(tr("Folder", "Folder"), status.folder)
                    InfoRow(tr("Ostatni zapis", "Last saved"), when {
                        status.running -> tr("zapisywanie…", "saving…")
                        status.lastSuccess == 0L -> tr("jeszcze nie", "not yet")
                        else -> agoText(status.lastSuccess)
                    })
                    InfoRow(tr("Rozmiar", "Size"), sizeText(status.size, lang))
                    status.problem?.let { problem ->
                        Hint(when (problem) {
                            AutoBackup.Problem.NoAccess -> tr("Aplikacja straciła dostęp do folderu kopii. Wybierz go ponownie przyciskiem „Zmień folder”.",
                                "The app lost access to the backup folder. Pick it again with “Change folder”.")
                            AutoBackup.Problem.Failed -> tr("Ostatni zapis się nie udał: ", "The last save failed: ") + status.problemDetail
                        }, warning = true)
                    }
                    if (status.lowSpace) Hint(tr("Na telefonie zostało mało miejsca. Kopia zawiera drugi egzemplarz wszystkich zdjęć — zrób archiwum na komputer i usuń stare projekty.",
                        "The phone is low on storage. The backup holds a second copy of every photo — move an archive to a computer and delete old projects."), warning = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudioAction(tr("Zapisz teraz", "Save now"), StudioSymbol.Check, { backup.requestSync(0) }, Modifier.weight(1f), enabled = !status.running, secondary = true)
                        StudioAction(tr("Zmień folder", "Change folder"), StudioSymbol.Folder, controller.enable, Modifier.weight(1f), secondary = true)
                    }
                    StudioAction(tr("Przejrzyj kopię automatyczną", "Browse the automatic backup"), StudioSymbol.Download, { controller.openAutoFile(null, lang) }, Modifier.fillMaxWidth(), secondary = true)
                    TextButton(onClick = { confirmDisable = true }) { Text(tr("Wyłącz kopię automatyczną", "Turn off automatic backup"), color = colors.error) }
                }
            }

            HorizontalDivider(color = colors.outlineVariant)
            SectionTitle(tr("Archiwum", "Archive"))
            Hint(tr("Osobna kopia z dzisiejszą datą, zapisana tam, gdzie wskażesz — np. do przeniesienia na komputer. Archiwum się nie zmienia, więc potem możesz bezpiecznie usunąć stare projekty z telefonu.",
                "A separate copy with today's date, saved wherever you choose — e.g. to move to a computer. An archive never changes, so afterwards you can safely delete old projects from the phone."))
            StudioAction(tr("Zrób archiwum", "Make archive"), StudioSymbol.Upload, controller.exportArchive, Modifier.fillMaxWidth())

            HorizontalDivider(color = colors.outlineVariant)
            SectionTitle(tr("Wgraj kopię", "Load a backup"))
            Hint(tr("Otwórz kopię albo archiwum, przejrzyj projekty i zdjęcia, a potem wybierz, co wgrać. Nic, co jest już w aplikacji, nie zostanie nadpisane.",
                "Open a backup or an archive, browse its projects and photos, then choose what to load. Nothing already in the app is overwritten."))
            StudioAction(tr("Otwórz plik kopii", "Open a backup file"), StudioSymbol.Download, controller.openFile, Modifier.fillMaxWidth(), secondary = true)
        }
    }
    if (confirmDisable) AlertDialog(
        onDismissRequest = { confirmDisable = false },
        title = { Text(tr("Wyłączyć kopię automatyczną?", "Turn off automatic backup?")) },
        text = { Text(tr("Plik kopii zostaje w folderze, ale przestanie się aktualizować.", "The backup file stays in the folder but stops updating.")) },
        confirmButton = { TextButton(onClick = { backup.disable(); confirmDisable = false }) { Text(tr("Wyłącz", "Turn off"), color = colors.error) } },
        dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text(tr("Anuluj", "Cancel")) } },
    )
}

@Composable
private fun SectionTitle(text: String) = Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    if (warning) Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.errorContainer).padding(12.dp), color = colors.onErrorContainer, fontSize = 13.sp, lineHeight = 18.sp)
    else Text(text, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Read-only view of a backup: what it holds, how each project relates to the phone's, and a choice of what to load.
 * An empty app can take everything back at once, settings included.
 */
@Composable
internal fun BackupBrowseScreen(archive: BackupArchive, current: List<Project>, onBack: () -> Unit, onRestoreAll: () -> Unit, onImport: (Map<String, ImportMode>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    val existing = remember(current) { current.associateBy { it.id } }
    val choices = remember(archive) { mutableStateMapOf<String, ImportMode>() }
    val info = archive.info
    Column(Modifier.fillMaxSize()) {
        StudioTopBar(onBack, title = tr("Przegląd kopii", "Backup contents"))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioTitle(backupDateText(info.createdAt, lang))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricChip(StudioSymbol.Folder, lang.count(info.projectCount, "projekt", "projekty", "projektów", "project", "projects"))
                        MetricChip(StudioSymbol.Photo, photoCount(info.photoCount, lang))
                    }
                    Text((if (info.auto) tr("Kopia automatyczna", "Automatic backup") else tr("Archiwum", "Archive")) + " · " + tr("wersja", "version") + " " + info.appVersion,
                        color = colors.onSurfaceVariant, fontSize = 13.sp)
                    if (current.isEmpty() && archive.projects.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).background(colors.primaryContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(tr("Aplikacja jest pusta — możesz przywrócić wszystko: projekty, zdjęcia i ustawienia.", "The app is empty — you can restore everything: projects, photos and settings."),
                                color = colors.onPrimaryContainer, fontSize = 14.sp, lineHeight = 20.sp)
                            StudioAction(tr("Przywróć wszystko", "Restore everything"), StudioSymbol.Download, onRestoreAll, Modifier.fillMaxWidth())
                        }
                    }
                    Text(tr("Zaznacz projekty do wgrania. To, co już jest w aplikacji, zostaje nietknięte; dotknij projektu, żeby zobaczyć jego zdjęcia.",
                        "Select the projects to load. Whatever is already in the app stays untouched; tap a project to see its photos."),
                        color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(archive.projects, key = { it.id }) { project ->
                BackupProjectCard(archive, project, existing[project.id], choices[project.id]) { mode -> if (mode == null) choices.remove(project.id) else choices[project.id] = mode }
            }
            if (archive.projects.isEmpty()) item { StudioEmpty(tr("Kopia jest pusta", "The backup is empty"), tr("Nie ma w niej żadnych projektów.", "It holds no projects.")) }
        }
        StudioDock {
            StudioAction(if (choices.isEmpty()) tr("Wgraj zaznaczone", "Load selected") else tr("Wgraj zaznaczone", "Load selected") + " (${choices.size})",
                StudioSymbol.Download, { onImport(choices.toMap()) }, Modifier.fillMaxWidth(), enabled = choices.isNotEmpty())
        }
    }
}

@Composable
private fun BackupProjectCard(archive: BackupArchive, project: Project, existing: Project?, mode: ImportMode?, onChoice: (ImportMode?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    val state = remember(project, existing) { BackupImport.state(project, existing) }
    val missing = remember(project, existing) { BackupImport.missingPhotos(project, existing) }
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, if (mode != null) colors.primary else colors.outlineVariant, shape)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(StudioInk), contentAlignment = Alignment.Center) {
                val cover = project.photos.lastOrNull()
                if (cover != null) BackupPhoto(archive, project, cover, Modifier.fillMaxSize(), labels = false)
                else StudioIcon(StudioSymbol.Folder, Modifier.size(26.dp), Color(0xFFA6B096))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(project.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(photoCount(project.photos.size, lang) + " · " + SimpleDateFormat("d MMM yyyy", lang.locale).format(Date(project.createdAt)), color = colors.onSurfaceVariant, fontSize = 12.sp)
                val (badge, badgeColor) = when (state) {
                    ImportState.New -> tr("Nie ma go w aplikacji", "Not in the app") to colors.primary
                    ImportState.Same -> tr("Już jest w aplikacji", "Already in the app") to colors.onSurfaceVariant
                    ImportState.Different -> tr("W aplikacji jest inna wersja", "The app has a different version") to colors.error
                }
                Text(badge, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Checkbox(mode != null, onCheckedChange = { checked -> onChoice(if (checked) ImportMode.Copy else null) }, enabled = state != ImportState.Same)
        }
        if (state == ImportState.Different && mode != null) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == ImportMode.Copy, { onChoice(ImportMode.Copy) }, label = { Text(tr("Jako nowy projekt", "As a new project")) })
                    FilterChip(mode == ImportMode.Merge, { onChoice(ImportMode.Merge) }, enabled = missing > 0,
                        label = { Text(tr("Dołącz brakujące", "Add missing") + " ($missing)") })
                }
                Text(if (mode == ImportMode.Copy) tr("Powstanie osobny projekt z dopiskiem „z kopii”; obecny zostaje bez zmian.", "A separate project marked “from backup” is created; the current one stays as it is.")
                    else tr("Do projektu w aplikacji dojdą tylko zdjęcia, których w nim nie ma.", "Only the photos the app's project lacks are added to it."),
                    color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (project.photos.isEmpty()) Text(tr("Brak zdjęć.", "No photos."), color = colors.onSurfaceVariant, fontSize = 13.sp)
                project.photos.chunked(3).forEachIndexed { row, photos ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        photos.forEachIndexed { column, photo ->
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.fillMaxWidth().aspectRatio((photo.widthMm / photo.heightMm).coerceIn(0.6f, 1.6f)).clip(RoundedCornerShape(12.dp))) {
                                    BackupPhoto(archive, project, photo, Modifier.fillMaxSize(), labels = true)
                                    Text((row * 3 + column + 1).toString().padStart(2, '0'), Modifier.align(Alignment.TopStart).padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
                                }
                                Text(mmText(photo.widthMm, photo.heightMm), Modifier.padding(top = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
                                Text(listOf(photo.pn, photo.steps).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - photos.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** A photo read out of the backup on demand and shown through the app's own photo view. */
@Composable
private fun BackupPhoto(archive: BackupArchive, project: Project, photo: PhotoItem, modifier: Modifier, labels: Boolean) {
    val file by produceState<File?>(null, archive, project.id, photo.id) { value = withContext(Dispatchers.IO) { archive.photoFile(project, photo) } }
    val loaded = file
    if (loaded != null) StudioPhoto(photo, loaded, modifier, labels = labels)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        StudioIcon(StudioSymbol.Photo, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
