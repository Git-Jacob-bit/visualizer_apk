package pl.visualizer.montaz

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * Backup file layout — a plain ZIP a computer opens without extra software:
 *
 *     index.html                       readable overview: every project and photo, framed as in the app
 *     backup.json                      format version, app version, settings and the list of projects
 *     projekty/<project id>/project.json
 *     projekty/<project id>/<photo>.jpg  untouched originals; edits live in project.json
 */
object BackupFormat {
    const val VERSION = 1
    const val MANIFEST = "backup.json"
    const val INDEX = "index.html"
    private const val PROJECTS = "projekty/"

    fun projectPath(projectId: String) = "$PROJECTS$projectId/project.json"
    fun photoPath(projectId: String, fileName: String) = "$PROJECTS$projectId/$fileName"

    /** The SharedPreferences file whose every key travels with the backup. */
    const val SETTINGS = "visualizer_settings"
}

data class BackupInfo(val backupId: String, val createdAt: Long, val appVersion: String, val auto: Boolean, val projectCount: Int, val photoCount: Int)

/** What a backup of the current state is made of; built fresh for every write. */
internal class BackupContent(private val store: ProjectStore, private val projects: List<Project>, private val settings: SharedPreferences, private val backupId: String, private val auto: Boolean, private val lang: Lang) {
    val createdAt = System.currentTimeMillis()

    val photos: List<ZipPhoto> = projects.flatMap { project ->
        project.photos.map { photo ->
            val file = store.photoFile(project.id, photo)
            ZipPhoto(BackupFormat.photoPath(project.id, photo.fileName), file.length(), file.lastModified()) {
                runCatching { file.readBytes() }.getOrNull()
            }
        }
    }

    /** Small files rewritten on every update; the overview goes last so it reflects everything above it. */
    fun meta(): List<Pair<String, ByteArray>> = buildList {
        add(BackupFormat.MANIFEST to manifest().toString(1).toByteArray(Charsets.UTF_8))
        projects.forEach { add(BackupFormat.projectPath(it.id) to ProjectStore.toJson(it).toString(1).toByteArray(Charsets.UTF_8)) }
        add(BackupFormat.INDEX to BackupIndex.html(projects, createdAt, lang).toByteArray(Charsets.UTF_8))
    }

    private fun manifest() = JSONObject().apply {
        put("format", BackupFormat.VERSION)
        put("app", "Wizualizator ramy")
        put("appVersion", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("backupId", backupId)
        put("kind", if (auto) "auto" else "archive")
        put("createdAt", createdAt)
        put("settings", BackupSettings.toJson(settings))
        put("projects", JSONArray().apply {
            projects.forEach { project ->
                put(JSONObject().put("id", project.id).put("name", project.name).put("createdAt", project.createdAt).put("photos", project.photos.size))
            }
        })
    }
}

/** Every preference with its type, so a restore writes back exactly what was there. */
internal object BackupSettings {
    fun toJson(settings: SharedPreferences) = JSONObject().apply {
        settings.all.toSortedMap().forEach { (key, value) ->
            val typed = when (value) {
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is String -> JSONObject().put("type", "string").put("value", value)
                is Set<*> -> JSONObject().put("type", "stringSet").put("value", JSONArray(value.map { it.toString() }))
                else -> null
            }
            if (typed != null) put(key, typed)
        }
    }

    /** Replaces all preferences with the backup's; unknown types are skipped rather than guessed. */
    fun restore(settings: SharedPreferences, json: JSONObject) {
        val editor = settings.edit().clear()
        json.keys().forEach { key ->
            val item = json.optJSONObject(key) ?: return@forEach
            when (item.optString("type")) {
                "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
                "int" -> editor.putInt(key, item.getInt("value"))
                "long" -> editor.putLong(key, item.getLong("value"))
                "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
                "string" -> editor.putString(key, item.getString("value"))
                "stringSet" -> item.getJSONArray("value").let { array -> editor.putStringSet(key, (0 until array.length()).map(array::getString).toSet()) }
            }
        }
        // The restored app goes straight to its projects; the introduction is for new users.
        editor.putBoolean("welcome_done", true)
        editor.commit()
    }
}

/** The overview page: opens in any browser once the archive is unpacked, with framing and light as in the app. */
internal object BackupIndex {
    fun html(projects: List<Project>, createdAt: Long, lang: Lang): String {
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", lang.locale).format(Date(createdAt))
        val photoTotal = projects.sumOf { it.photos.size }
        val filters = StringBuilder()
        val body = StringBuilder()
        var filterId = 0
        projects.forEachIndexed { projectIndex, project ->
            body.append("<section id=\"p$projectIndex\"><h2>").append(esc(project.name)).append("</h2><p class=\"meta\">")
                .append(esc(lang.tr("Utworzono", "Created"))).append(' ').append(SimpleDateFormat("dd.MM.yyyy", lang.locale).format(Date(project.createdAt)))
                .append(" · ").append(esc(photoCount(project.photos.size, lang))).append("</p><div class=\"grid\">")
            project.photos.forEachIndexed { index, photo ->
                val src = esc(BackupFormat.photoPath(project.id, photo.fileName))
                val q = { v: Float -> "${((v.coerceIn(-1f, 1f) + 1f) * 50f)}%" }
                val position = "${q(photo.cropX)} ${q(photo.cropY)}"
                var style = "object-position:$position;transform-origin:$position;transform:scale(${photo.cropZoom.coerceAtLeast(1f)})"
                if (photo.brightness != 0f || photo.contrast != 1f) {
                    // The app's curve is contrast around mid-grey plus a brightness offset: a linear transfer per channel.
                    val slope = photo.contrast
                    val intercept = (1f - photo.contrast) / 2f + photo.brightness
                    val f = "f${filterId++}"
                    filters.append("<filter id=\"$f\" color-interpolation-filters=\"sRGB\"><feComponentTransfer>")
                    listOf("R", "G", "B").forEach { filters.append("<feFunc$it type=\"linear\" slope=\"$slope\" intercept=\"$intercept\"/>") }
                    filters.append("</feComponentTransfer></filter>")
                    style += ";filter:url(#$f)"
                }
                body.append("<figure><a class=\"frame\" href=\"").append(src).append("\" style=\"aspect-ratio:${photo.widthMm}/${photo.heightMm}\"><img loading=\"lazy\" src=\"")
                    .append(src).append("\" style=\"").append(style).append("\" alt=\"\">")
                if (photo.pn.isNotBlank()) {
                    val color = String.format("#%06X", photo.pnColor and 0xFFFFFF)
                    body.append("<span class=\"label ${corner(photo.pnCorner)}\" style=\"background:$color\">").append(esc(photo.pn)).append("</span>")
                }
                if (photo.steps.isNotBlank()) body.append("<span class=\"label steps ${corner(photo.stepsCorner)}\">").append(esc(photo.steps)).append("</span>")
                body.append("</a><figcaption><b>").append(index + 1).append("</b> ").append(esc(mmText(photo.widthMm, photo.heightMm)))
                if (photo.pn.isNotBlank()) body.append(" · PN ").append(esc(photo.pn))
                if (photo.steps.isNotBlank()) body.append(" · ").append(esc(lang.tr("krok", "step"))).append(' ').append(esc(photo.steps))
                body.append("</figcaption></figure>")
            }
            if (project.photos.isEmpty()) body.append("<p class=\"meta\">").append(esc(lang.tr("Brak zdjęć.", "No photos."))).append("</p>")
            body.append("</div></section>")
        }
        val nav = projects.mapIndexed { i, p -> "<a href=\"#p$i\">${esc(p.name)}</a>" }.joinToString("")
        return """<!doctype html>
<html lang="${lang.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(lang.tr("Kopia zapasowa — Wizualizator ramy", "Backup — Frame visualizer"))}</title>
<style>
:root{--ink:#22261f;--muted:#666b61;--paper:#f7f7f2;--card:#fff;--line:#e1e4da;--accent:#e2582f}
@media (prefers-color-scheme:dark){:root{--ink:#f1f2e9;--muted:#a5ad9d;--paper:#151815;--card:#20241f;--line:#353d31;--accent:#ffa083}}
body{margin:0;background:var(--paper);color:var(--ink);font:15px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1200px;margin:0 auto;padding:24px 16px 48px}h1{margin:0 0 4px;font-size:26px}h2{margin:36px 0 2px;font-size:20px}
.meta{color:var(--muted);margin:0 0 12px}.hint{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:10px 14px;color:var(--muted)}
nav{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}nav a{background:var(--card);border:1px solid var(--line);border-radius:999px;padding:4px 12px;color:var(--ink);text-decoration:none}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:16px;align-items:start}
figure{margin:0;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:10px}
.frame{position:relative;display:block;overflow:hidden;border-radius:6px;background:#000}
.frame img{width:100%;height:100%;object-fit:cover;display:block}
figcaption{margin-top:8px;font-size:13px;color:var(--muted)}figcaption b{color:var(--accent)}
.label{position:absolute;padding:1px 5px;font:600 11px system-ui,sans-serif;color:#000;border:1px solid #000}
.steps{background:#ffd330}.TL{left:4px;top:4px}.TR{right:4px;top:4px}.BL{left:4px;bottom:4px}.BR{right:4px;bottom:4px}
</style></head><body><main>
<h1>${esc(lang.tr("Wizualizator ramy — kopia zapasowa", "Frame visualizer — backup"))}</h1>
<p class="meta">${esc(lang.tr("Zapisano", "Saved"))} $date · ${esc(lang.tr("wersja", "version"))} ${esc(AppVersion)} · ${esc(lang.count(projects.size, "projekt", "projekty", "projektów", "project", "projects"))} · ${esc(photoCount(photoTotal, lang))}</p>
<p class="hint">${esc(lang.tr("Nie widać zdjęć? Najpierw rozpakuj cały plik ZIP (Wyodrębnij wszystkie), a potem otwórz index.html z rozpakowanego folderu. Kliknięcie zdjęcia otwiera oryginał. Kopię wgrywa się z powrotem w aplikacji: O aplikacji → Kopia zapasowa.",
            "No photos showing? Unpack the whole ZIP file first (Extract all), then open index.html from the unpacked folder. Clicking a photo opens the original. The backup is loaded back in the app: About → Backup."))}</p>
<nav>$nav</nav>
<svg width="0" height="0" style="position:absolute">$filters</svg>
$body
</main></body></html>
"""
    }

    private fun corner(value: String) = value.takeIf { it in setOf("TL", "TR", "BL", "BR") } ?: "TL"

    private fun esc(text: String) = buildString {
        text.forEach { c ->
            when (c) {
                '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}

/** A SAF document opened as a real file descriptor: positional reads and writes, safe from several threads. */
internal class FdRandomAccess(private val pfd: ParcelFileDescriptor) : RandomAccess {
    private val fd = pfd.fileDescriptor
    override val size: Long get() = Os.fstat(fd).st_size
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = Os.pread(fd, buffer, offset + done, length - done, position + done)
            if (n <= 0) throw EOFException()
            done += n
        }
    }
    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) done += Os.pwrite(fd, buffer, offset + done, length - done, position + done)
    }
    override fun truncate(size: Long) = Os.ftruncate(fd, size)
    override fun close() = pfd.close()

    companion object {
        /** Whether the descriptor is a seekable file rather than a pipe (some cloud providers stream). */
        fun seekable(pfd: ParcelFileDescriptor) = try { Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR); true } catch (_: ErrnoException) { false }
    }
}

/** RandomAccessFile is not positional; one reader at a time. */
private class LockedFileAccess(file: File) : RandomAccess {
    private val inner = FileRandomAccess(file, writable = false)
    override val size get() = synchronized(this) { inner.size }
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = synchronized(this) { inner.read(position, buffer, offset, length) }
    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) = throw UnsupportedOperationException()
    override fun truncate(size: Long) = throw UnsupportedOperationException()
    override fun close() = inner.close()
}

class NotABackupException : IllegalStateException("Not a frame visualizer backup")

/** A backup opened for reading: its projects, settings and photos, read straight from the archive. */
class BackupArchive private constructor(
    private val access: RandomAccess,
    private val entries: Map<String, ZipEntryInfo>,
    val info: BackupInfo,
    val projects: List<Project>,
    private val settingsJson: JSONObject,
    private val cacheDir: File,
    private val tempCopy: File?,
) : Closeable {
    /** Photos the archive really holds (a damaged backup may list more). */
    fun hasPhoto(project: Project, photo: PhotoItem) = BackupFormat.photoPath(project.id, photo.fileName) in entries

    fun photoBytes(projectId: String, photo: PhotoItem): ByteArray? =
        entries[BackupFormat.photoPath(projectId, photo.fileName)]?.let { runCatching { ZipReader.read(access, it) }.getOrNull() }

    /** The photo as a cached file, so the app's own photo view can show it with framing and labels. */
    // One extraction at a time, and a closed archive (the user left the screen) just yields nothing.
    @Synchronized
    fun photoFile(project: Project, photo: PhotoItem): File? = runCatching {
        val file = File(cacheDir, "${project.id}_${photo.fileName}")
        if (file.exists()) return@runCatching file
        val bytes = photoBytes(project.id, photo) ?: return@runCatching null
        val partial = File(cacheDir, file.name + ".part")
        partial.writeBytes(bytes)
        file.takeIf { partial.renameTo(it) }
    }.getOrNull()

    fun restoreSettings(settings: SharedPreferences) = BackupSettings.restore(settings, settingsJson)

    @Synchronized
    override fun close() {
        runCatching { access.close() }
        tempCopy?.delete()
        cacheDir.deleteRecursively()
    }

    companion object {
        fun open(context: Context, uri: Uri): BackupArchive {
            // Only one backup is open at a time; leftovers of an earlier one (e.g. the app was killed) go first.
            File(context.cacheDir, "backup_browse").deleteRecursively()
            context.cacheDir.listFiles { file -> file.name.startsWith("backup_import_") }?.forEach { it.delete() }
            val resolver = context.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw NotABackupException()
            var temp: File? = null
            val access: RandomAccess = if (FdRandomAccess.seekable(pfd)) FdRandomAccess(pfd) else {
                pfd.close()
                // A streamed document (e.g. straight from a cloud drive) is copied once so it can be read at random.
                temp = File(context.cacheDir, "backup_import_${UUID.randomUUID()}.zip")
                resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: throw NotABackupException()
                LockedFileAccess(temp)
            }
            return try { read(context, access, temp) } catch (error: Exception) {
                access.close(); temp?.delete()
                throw if (error is NotABackupException) error else NotABackupException().apply { initCause(error) }
            }
        }

        internal fun read(context: Context, access: RandomAccess, temp: File?): BackupArchive {
            val entries = ZipReader.entries(access).associateBy { it.name }
            val manifestEntry = entries[BackupFormat.MANIFEST] ?: throw NotABackupException()
            val manifest = JSONObject(ZipReader.read(access, manifestEntry).toString(Charsets.UTF_8))
            if (manifest.optInt("format", 0) !in 1..BackupFormat.VERSION) throw NotABackupException()
            val listed = manifest.optJSONArray("projects") ?: JSONArray()
            val projects = (0 until listed.length()).mapNotNull { index ->
                val id = listed.getJSONObject(index).getString("id")
                entries[BackupFormat.projectPath(id)]?.let { entry ->
                    runCatching { ProjectStore.fromJson(JSONObject(ZipReader.read(access, entry).toString(Charsets.UTF_8))) }.getOrNull()
                }
            }.sortedByDescending { it.createdAt }
            val info = BackupInfo(
                backupId = manifest.optString("backupId"),
                createdAt = manifest.optLong("createdAt"),
                appVersion = manifest.optString("appVersion"),
                auto = manifest.optString("kind") == "auto",
                projectCount = projects.size,
                photoCount = projects.sumOf { it.photos.size },
            )
            val cache = File(context.cacheDir, "backup_browse/${UUID.randomUUID()}").apply { mkdirs() }
            return BackupArchive(access, entries, info, projects, manifest.optJSONObject("settings") ?: JSONObject(), cache, temp)
        }

        /** Just the identity of a backup, for deciding whether the automatic backup may write over it. */
        internal fun peekId(access: RandomAccess): String? = runCatching {
            val entry = ZipReader.entries(access).firstOrNull { it.name == BackupFormat.MANIFEST } ?: return null
            JSONObject(ZipReader.read(access, entry).toString(Charsets.UTF_8)).optString("backupId").ifBlank { null }
        }.getOrNull()
    }
}

/** How a project from a backup relates to the phone's projects. */
enum class ImportState { New, Same, Different }

enum class ImportMode { Copy, Merge }

internal object BackupImport {
    fun state(project: Project, existing: Project?): ImportState = when {
        existing == null -> ImportState.New
        ProjectStore.toJson(existing).toString() == ProjectStore.toJson(project).toString() -> ImportState.Same
        else -> ImportState.Different
    }

    fun missingPhotos(project: Project, existing: Project?) = project.photos.count { photo -> existing?.photos?.none { it.id == photo.id } != false }

    /**
     * Brings one project in without touching anything already on the phone: a new project keeps its identity, a
     * conflicting one becomes a renamed copy with fresh identifiers, or (merge) only gains the photos it lacks.
     */
    fun import(store: ProjectStore, archive: BackupArchive, project: Project, existing: Project?, mode: ImportMode, lang: Lang): Int {
        if (existing == null) return store.importProject(project) { archive.photoBytes(project.id, it) }
        return when (mode) {
            ImportMode.Merge -> {
                val added = project.photos.filter { photo -> existing.photos.none { it.id == photo.id } }
                (store.importProject(existing.copy(photos = existing.photos + added)) { photo -> archive.photoBytes(project.id, photo) } - existing.photos.size).coerceAtLeast(0)
            }
            ImportMode.Copy -> {
                val originals = HashMap<String, PhotoItem>()
                val photos = project.photos.map { photo ->
                    photo.copy(id = UUID.randomUUID().toString(), fileName = "${UUID.randomUUID()}.jpg").also { originals[it.id] = photo }
                }
                val date = SimpleDateFormat("dd.MM.yyyy", lang.locale).format(Date(archive.info.createdAt))
                val copy = Project(UUID.randomUUID().toString(), "${project.name} " + lang.tr("(z kopii $date)", "(from backup $date)"), project.createdAt, photos)
                store.importProject(copy) { photo -> originals[photo.id]?.let { archive.photoBytes(project.id, it) } }
            }
        }
    }
}

internal fun backupDateText(millis: Long, lang: Lang): String = SimpleDateFormat("d.MM.yyyy, HH:mm", lang.locale).format(Date(millis))

internal fun sizeText(bytes: Long, lang: Lang): String = when {
    bytes >= 1L shl 30 -> lang.decimal(bytes / (1024f * 1024f * 1024f), 2) + " GB"
    bytes >= 1L shl 20 -> lang.decimal(bytes / (1024f * 1024f), 0) + " MB"
    else -> lang.decimal(bytes / 1024f, 0) + " KB"
}

