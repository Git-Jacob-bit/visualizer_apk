package pl.visualizer.montaz

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun all(): List<Project> = root.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir -> runCatching { read(dir) }.getOrNull() }
        ?.sortedByDescending { it.createdAt }
        ?: emptyList()

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
        val json = JSONObject().apply {
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
                        put("cropZoom", photo.cropZoom.toDouble())
                        put("cropX", photo.cropX.toDouble())
                        put("cropY", photo.cropY.toDouble())
                    })
                }
            })
        }
        val atomic = AtomicFile(File(projectDir(project.id), "project.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun read(dir: File): Project {
        val json = JSONObject(AtomicFile(File(dir, "project.json")).openRead().bufferedReader().use { it.readText() })
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
                    cropZoom = item.optDouble("cropZoom", 1.0).toFloat(),
                    cropX = item.optDouble("cropX", 0.0).toFloat(),
                    cropY = item.optDouble("cropY", 0.0).toFloat(),
                )
            },
        )
    }
}
