package pl.visualizer.montaz

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The automatic backup: one `backup.zip` in `Documents/wizualizator_ram_backup_auto/`, kept as a mirror of the app.
 *
 * Android ties a file in shared storage to the install that created it, so after a reinstall the app could neither
 * see nor overwrite its own old backup. The user therefore grants the folder once through the system picker; the
 * persisted grant lets the app read the old backup (restore) and keep writing to it. Every change in the projects or
 * settings schedules an update a few seconds later, and leaving the app writes it at once. The file is updated in
 * place (see [ZipUpdater]), so a large backup costs only the new photos and a few kilobytes of metadata.
 *
 * A `backup.zip` written by another install (another phone, or the app before a reinstall) is never overwritten:
 * the user restores it, browses it, or keeps it as an archive and starts a new one.
 */
class AutoBackup private constructor(private val context: Context) {
    enum class Mode { Off, Blocked, On }

    enum class Problem { NoAccess, Failed }

    data class Status(
        val mode: Mode = Mode.Off,
        val folder: String = "",
        val lastSuccess: Long = 0L,
        val size: Long = 0L,
        val running: Boolean = false,
        val problem: Problem? = null,
        val problemDetail: String = "",
        val lowSpace: Boolean = false,
        val dismissedLevel: Int = 0,
    ) {
        /** 0 below 1 GB, then 1, 2, 3… for every further 500 MB. */
        val warnLevel get() = if (size < WARN_BYTES) 0 else 1 + ((size - WARN_BYTES) / WARN_STEP).toInt()
        val showSizeWarning get() = mode == Mode.On && warnLevel > dismissedLevel
    }

    /** What the chosen folder held. */
    sealed interface Connection {
        data object Fresh : Connection
        data object Ours : Connection
        data class Foreign(val file: Uri) : Connection
    }

    private val resolver = context.contentResolver
    private val state = context.getSharedPreferences("backup_state", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences(BackupFormat.SETTINGS, Context.MODE_PRIVATE)
    private val store = ProjectStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    @Volatile private var dirty = false
    @Volatile private var paused = false
    private val _status = MutableStateFlow(storedStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    // SharedPreferences keeps listeners weakly; this field keeps it alive as long as the backup itself.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestSync() }

    init {
        settings.registerOnSharedPreferenceChangeListener(settingsListener)
        // Catches up on anything a failed or interrupted run missed.
        if (_status.value.mode == Mode.On) requestSync(0)
    }

    private val backupId: String
        get() = state.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { state.edit().putString(KEY_ID, it).apply() }

    private fun treeUri() = state.getString(KEY_TREE, null)?.let(Uri::parse)

    private fun storedStatus() = Status(
        mode = when {
            state.getString(KEY_TREE, null) == null -> Mode.Off
            state.getBoolean(KEY_FOREIGN, false) -> Mode.Blocked
            else -> Mode.On
        },
        folder = state.getString(KEY_FOLDER, "").orEmpty(),
        lastSuccess = state.getLong(KEY_LAST, 0L),
        size = state.getLong(KEY_SIZE, 0L),
        dismissedLevel = state.getInt(KEY_DISMISSED, 0),
    )

    /** Re-reads the persisted part of the status, keeping the live part (running, problem, low space). */
    private fun publish() = _status.update { live ->
        storedStatus().copy(running = live.running, problem = live.problem, problemDetail = live.problemDetail, lowSpace = live.lowSpace)
    }

    /** Schedules an update; repeated changes within the delay collapse into one write. Called from any thread. */
    fun requestSync(delayMillis: Long = DEBOUNCE_MILLIS) {
        if (_status.value.mode != Mode.On) return
        dirty = true
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(delayMillis)
                sync()
            }
        }
    }

    /** Writes pending changes now — called when the app goes to the background. */
    fun flush() {
        if (dirty && _status.value.mode == Mode.On) requestSync(0)
    }

    /**
     * Holds back writes while the app reads the backup file itself (browsing or restoring it): an update would move
     * the data under the open archive. Waits for a running update to finish first.
     */
    suspend fun pause() = mutex.withLock { paused = true }

    fun resume() {
        paused = false
        flush()
    }

    private suspend fun sync() = mutex.withLock {
        if (_status.value.mode != Mode.On || paused) return@withLock
        dirty = false
        _status.update { it.copy(running = true) }
        try {
            val tree = treeUri() ?: return@withLock
            // Read the projects first: if one cannot be read, nothing is written rather than mirroring it as deleted.
            val projects = store.allStrict()
            val file = backupFile(tree, create = true) ?: throw SecurityException("Backup folder unavailable")
            val pfd = resolver.openFileDescriptor(file, "rw") ?: throw SecurityException("Cannot open backup file")
            val size = FdRandomAccess(pfd).use { access ->
                // Only an empty file or one carrying this install's id may be written. Anything else — another
                // install's backup, a file still being copied in, one that cannot be read — waits for the user.
                if (access.size > 0 && BackupArchive.peekId(access) != backupId) {
                    state.edit().putBoolean(KEY_FOREIGN, true).apply()
                    return@withLock
                }
                val content = BackupContent(store, projects, settings, backupId, auto = true, Lang.fromCode(settings.getString("language", null)) ?: Lang.system())
                ZipUpdater.update(access, content.photos, content.head(), content.tail()).size
            }
            state.edit().putLong(KEY_LAST, System.currentTimeMillis()).putLong(KEY_SIZE, size).apply()
            _status.update { it.copy(problem = null, problemDetail = "", lowSpace = freeBytes() < LOW_SPACE_BYTES) }
        } catch (error: SecurityException) {
            dirty = true
            _status.update { it.copy(problem = Problem.NoAccess, problemDetail = error.message.orEmpty()) }
        } catch (error: Exception) {
            dirty = true
            _status.update { it.copy(problem = Problem.Failed, problemDetail = error.message ?: error.javaClass.simpleName) }
        } finally {
            _status.update { it.copy(running = false) }
            publish()
        }
    }

    /** Takes the folder the user picked, creates the backup folder inside it if needed, and inspects what is there. */
    suspend fun connect(tree: Uri): Connection = withContext(Dispatchers.IO) {
        mutex.withLock {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            treeUri()?.takeIf { it != tree }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val label = rootId.substringAfter(':').trim('/').let { path -> if (path.substringAfterLast('/') == FOLDER) path else listOf(path, FOLDER).filter(String::isNotEmpty).joinToString("/") }
            // Blocked until the folder's content is known, so a failure below never leaves an unchecked folder writable.
            state.edit().putString(KEY_TREE, tree.toString()).putString(KEY_FOLDER, label).putBoolean(KEY_FOREIGN, true).commit()
            val file = backupFile(tree, create = false)
            val existing = file?.let { uri -> resolver.openFileDescriptor(uri, "r")?.let(::FdRandomAccess)?.use { if (it.size == 0L) null else BackupArchive.peekId(it) ?: "" } }
            val result = when {
                file == null || existing == null -> Connection.Fresh
                existing == backupId -> Connection.Ours
                else -> Connection.Foreign(file)
            }
            if (result !is Connection.Foreign) state.edit().putBoolean(KEY_FOREIGN, false).apply()
            _status.update { it.copy(problem = null, problemDetail = "") }
            publish()
            result
        }.also { if (it !is Connection.Foreign) requestSync(0) }
    }

    /** The backup in the folder, e.g. to restore from it; null when there is none or the folder is not granted. */
    suspend fun backupFileUri(): Uri? = withContext(Dispatchers.IO) { runCatching { treeUri()?.let { backupFile(it, create = false) } }.getOrNull() }

    /** Continues writing into the backup the user just restored from: it becomes this install's own backup. */
    fun adopt(info: BackupInfo) {
        if (info.backupId.isBlank()) return
        state.edit().putString(KEY_ID, info.backupId).putBoolean(KEY_FOREIGN, false).apply()
        publish()
        requestSync(0)
    }

    /** Renames the foreign backup to backup_<date>.zip so it stays in the folder, then starts a fresh one. */
    suspend fun keepForeignAsArchive(createdAt: Long?): Boolean = withContext(Dispatchers.IO) {
        val renamed = mutex.withLock {
            runCatching {
                val file = treeUri()?.let { backupFile(it, create = false) }
                if (file != null) {
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date(createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis()))
                    DocumentsContract.renameDocument(resolver, file, "backup_$stamp.zip") ?: error("rename failed")
                }
                state.edit().putString(KEY_ID, UUID.randomUUID().toString()).putBoolean(KEY_FOREIGN, false).putLong(KEY_SIZE, 0L).putInt(KEY_DISMISSED, 0).apply()
            }.isSuccess
        }
        publish()
        if (renamed) requestSync(0)
        renamed
    }

    /** Stops the automatic backup after any running update; the file stays in the folder. */
    fun disable() {
        synchronized(this) { pending?.cancel() }
        scope.launch {
            mutex.withLock {
                treeUri()?.let { runCatching { resolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
                state.edit().remove(KEY_TREE).remove(KEY_FOLDER).remove(KEY_FOREIGN).remove(KEY_LAST).remove(KEY_SIZE).remove(KEY_DISMISSED).apply()
                _status.value = Status()
            }
        }
    }

    fun dismissSizeWarning() {
        state.edit().putInt(KEY_DISMISSED, _status.value.warnLevel).apply()
        publish()
    }

    /** Writes a one-off archive of everything to [uri] (a file the user picked). */
    suspend fun exportArchive(uri: Uri, lang: Lang) = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(uri) ?: error(lang.tr("Nie można otworzyć pliku docelowego.", "Cannot open the target file."))
        output.buffered().use { stream ->
            val content = BackupContent(store, store.allStrict(), settings, UUID.randomUUID().toString(), auto = false, lang)
            ZipUpdater.write(StreamSink(stream), content.photos, content.head(), content.tail())
        }
    }

    private fun freeBytes() = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun backupFile(tree: Uri, create: Boolean): Uri? {
        val folder = folderId(tree, create) ?: return null
        child(tree, folder, FILE)?.let { return DocumentsContract.buildDocumentUriUsingTree(tree, it) }
        if (!create) return null
        return DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folder), "application/zip", FILE)
    }

    // The picked folder itself when it is the backup folder; otherwise the backup folder inside it.
    private fun folderId(tree: Uri, create: Boolean): String? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        if (displayName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) == FOLDER) return rootId
        child(tree, rootId, FOLDER)?.let { return it }
        if (!create) return null
        return DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), Document.MIME_TYPE_DIR, FOLDER)
            ?.let(DocumentsContract::getDocumentId)
    }

    private fun displayName(uri: Uri): String? = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun child(tree: Uri, parentId: String, name: String): String? =
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) return cursor.getString(0)
            null
        }

    companion object {
        const val FOLDER = "wizualizator_ram_backup_auto"
        const val FILE = "backup.zip"
        const val WARN_BYTES = 1L shl 30
        const val WARN_STEP = 500L shl 20
        private const val LOW_SPACE_BYTES = 500L shl 20
        private const val DEBOUNCE_MILLIS = 4000L
        private const val KEY_TREE = "tree_uri"
        private const val KEY_FOLDER = "folder_label"
        private const val KEY_FOREIGN = "foreign_pending"
        private const val KEY_ID = "backup_id"
        private const val KEY_LAST = "last_success"
        private const val KEY_SIZE = "last_size"
        private const val KEY_DISMISSED = "dismissed_level"

        /** Where the folder picker opens: the phone's Documents folder. */
        val documentsUri: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

        @Volatile private var instance: AutoBackup? = null
        fun get(context: Context): AutoBackup = instance ?: synchronized(this) {
            instance ?: AutoBackup(context.applicationContext).also { instance = it }
        }
    }
}
