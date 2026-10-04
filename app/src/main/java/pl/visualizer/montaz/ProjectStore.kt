package pl.visualizer.montaz

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val MIN_LABEL_SCALE = 0.6f
internal const val MAX_LABEL_SCALE = 2f

// Background colours offered for the PN label, as ARGB: white (the default, and what older projects load) or the
// steps label's print yellow.
internal val PN_LABEL_COLORS = listOf(0xFFFFFFFF.toInt(), 0xFFFFD330.toInt())

// Black text unless the label is dark enough that white reads better.
internal fun labelTextColor(background: Int): Int =
    if (android.graphics.Color.luminance(background) < 0.18f) android.graphics.Color.WHITE else android.graphics.Color.BLACK

data class PhotoItem(
    val id: String,
    val fileName: String,
    val widthMm: Float,
    val heightMm: Float,
    val pn: String = "",
    val steps: String = "",
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val pnCorner: String = "TL",
    val stepsCorner: String = "BR",
    // Label size as a multiple of the standard 10 pt print label; each label scales on its own.
    val pnScale: Float = 1f,
    val stepsScale: Float = 1f,
    val pnColor: Int = PN_LABEL_COLORS[0],
    // Framing inside the fixed print size: zoom ≥ 1, centre offset −1…1 across the photo's free travel.
    val cropZoom: Float = 1f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
)

data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val photos: List<PhotoItem> = emptyList(),
)

/** Projects on the phone. [onChange] fires after every write, which is what keeps the automatic backup current. */
class ProjectStore(context: Context, private val onChange: () -> Unit = {}) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun all(): List<Project> = root.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir -> runCatching { read(dir) }.getOrNull() }
        ?.sortedByDescending { it.createdAt }
        ?: emptyList()

    /**
     * Readable projects and the ids of those whose project.json cannot be read. The backup mirrors the phone, so a
     * project that is merely unreadable must never look deleted; it fails outright when the folder cannot be listed.
     * A folder without project.json is not a project.
     */
    fun scan(): Pair<List<Project>, List<String>> {
        val dirs = root.listFiles() ?: throw java.io.IOException("Cannot list projects")
        val projects = mutableListOf<Project>()
        val broken = mutableListOf<String>()
        dirs.filter { it.isDirectory && (File(it, "project.json").exists() || File(it, "project.json.bak").exists()) }.forEach { dir ->
            runCatching { read(dir) }.onSuccess { projects += it }.onFailure { broken += dir.name }
        }
        return projects.sortedByDescending { it.createdAt } to broken
    }

    fun get(id: String): Project? = all().firstOrNull { it.id == id }

    fun create(name: String): Project {
        val project = Project(UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis())
        save(project)
        return project
    }

    fun rename(id: String, name: String) {
        get(id)?.let { save(it.copy(name = name.trim())) }
    }

    fun delete(id: String) {
        val dir = File(root, id)
        if (dir.parentFile == root) dir.deleteRecursively()
        onChange()
    }

    /**
     * Writes a project taken from a backup. Photo files that are not on the phone yet come from [readPhoto]; a photo
     * it cannot supply is left out rather than saved without its file. Returns how many photos made it.
     */
    fun importProject(project: Project, readPhoto: (PhotoItem) -> ByteArray?): Int {
        require(validId(project.id)) { "Invalid project id" }
        val dir = projectDir(project.id)
        val photos = project.photos.filter { photo ->
            val file = File(dir, photo.fileName)
            if (file.parentFile != dir) return@filter false
            file.exists() || readPhoto(photo)?.let { bytes ->
                val partial = File(dir, photo.fileName + ".part")
                partial.writeBytes(bytes)
                partial.renameTo(file)
            } == true
        }
        save(project.copy(photos = photos))
        return photos.size
    }

    fun newCaptureFile(projectId: String): File {
        val dir = File(root, projectId).apply { mkdirs() }
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    fun photoFile(projectId: String, photo: PhotoItem): File = File(File(root, projectId), photo.fileName)

    fun addPhoto(projectId: String, file: File, widthMm: Float, heightMm: Float): PhotoItem {
        val project = requireNotNull(get(projectId))
        val photo = PhotoItem(UUID.randomUUID().toString(), file.name, widthMm, heightMm)
        save(project.copy(photos = project.photos + photo))
        return photo
    }

    fun updatePhoto(projectId: String, photo: PhotoItem) {
        val project = requireNotNull(get(projectId))
        save(project.copy(photos = project.photos.map { if (it.id == photo.id) photo else it }))
    }

    fun deletePhoto(projectId: String, photoId: String) {
        val project = requireNotNull(get(projectId))
        project.photos.firstOrNull { it.id == photoId }?.let { photoFile(projectId, it).delete() }
        save(project.copy(photos = project.photos.filterNot { it.id == photoId }))
    }

    private fun projectDir(id: String) = File(root, id).apply { mkdirs() }

    private fun save(project: Project) {
        synchronized(fileLock) {
            val atomic = AtomicFile(File(projectDir(project.id), "project.json"))
            val stream = atomic.startWrite()
            try {
                stream.write(toJson(project).toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
        onChange()
    }

    private fun read(dir: File): Project = synchronized(fileLock) {
        fromJson(JSONObject(AtomicFile(File(dir, "project.json")).openRead().bufferedReader().use { it.readText() }))
    }

    companion object {
        // The backup reads projects on a background thread. Before Android 11 AtomicFile writes by moving the old
        // file aside, and a read at that moment would put it back over the half-written one — so reads and writes
        // of project.json never overlap, across every ProjectStore instance.
        private val fileLock = Any()

        /** Project ids name folders; one coming from a backup file must not be able to point anywhere else. */
        fun validId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }

        /** The project.json format, shared with the backup so a project restores exactly as it was saved. */
        fun toJson(project: Project): JSONObject = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("createdAt", project.createdAt)
            put("photos", JSONArray().apply {
                project.photos.forEach { photo ->
                    put(JSONObject().apply {
                        put("id", photo.id)
                        put("fileName", photo.fileName)
                        put("widthMm", photo.widthMm.toDouble())
                        put("heightMm", photo.heightMm.toDouble())
                        put("pn", photo.pn)
                        put("steps", photo.steps)
                        put("brightness", photo.brightness.toDouble())
                        put("contrast", photo.contrast.toDouble())
                        put("pnCorner", photo.pnCorner)
                        put("stepsCorner", photo.stepsCorner)
                        put("pnScale", photo.pnScale.toDouble())
                        put("stepsScale", photo.stepsScale.toDouble())
                        put("pnColor", photo.pnColor)
                        put("cropZoom", photo.cropZoom.toDouble())
                        put("cropX", photo.cropX.toDouble())
                        put("cropY", photo.cropY.toDouble())
                    })
                }
            })
        }

        fun fromJson(json: JSONObject): Project {
            val photos = json.getJSONArray("photos")
            return Project(
                id = json.getString("id"),
                name = json.getString("name"),
                createdAt = json.getLong("createdAt"),
                photos = (0 until photos.length()).map { index ->
                    val item = photos.getJSONObject(index)
                    PhotoItem(
                        id = item.getString("id"),
                        fileName = item.getString("fileName"),
                        widthMm = item.getDouble("widthMm").toFloat(),
                        heightMm = item.getDouble("heightMm").toFloat(),
                        pn = item.optString("pn"),
                        steps = item.optString("steps"),
                        brightness = item.optDouble("brightness", 0.0).toFloat(),
                        contrast = item.optDouble("contrast", 1.0).toFloat(),
                        pnCorner = item.optString("pnCorner", "TL"),
                        stepsCorner = item.optString("stepsCorner", "BR"),
                        pnScale = item.optDouble("pnScale", 1.0).toFloat(),
                        stepsScale = item.optDouble("stepsScale", 1.0).toFloat(),
                        pnColor = item.optInt("pnColor", PN_LABEL_COLORS[0]).takeIf { it in PN_LABEL_COLORS } ?: PN_LABEL_COLORS[0],
                        cropZoom = item.optDouble("cropZoom", 1.0).toFloat(),
                        cropX = item.optDouble("cropX", 0.0).toFloat(),
                        cropY = item.optDouble("cropY", 0.0).toFloat(),
                    )
                },
            )
        }
    }
}
