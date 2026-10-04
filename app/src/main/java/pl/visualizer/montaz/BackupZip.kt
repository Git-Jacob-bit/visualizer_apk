package pl.visualizer.montaz

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * The few ZIP operations the backup needs, written by hand so an archive can be updated in place: photos are stored
 * (uncompressed) and only ever appended, while the small metadata files, central directory and end record sit at
 * the tail and are rewritten on every update. Plain Kotlin on purpose — unit tests run it on the JVM.
 */

/** A file that can be read and written at any offset; on Android a SAF file descriptor, in tests a local file. */
interface RandomAccess : Closeable {
    val size: Long
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    fun write(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    fun truncate(size: Long)
}

class FileRandomAccess(file: File, writable: Boolean) : RandomAccess {
    private val raf = RandomAccessFile(file, if (writable) "rw" else "r")
    override val size get() = raf.length()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        raf.seek(position)
        raf.readFully(buffer, offset, length)
    }
    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        raf.seek(position)
        raf.write(buffer, offset, length)
    }
    override fun truncate(size: Long) = raf.setLength(size)
    override fun close() = raf.close()
}

/** Where the writer puts bytes: a stream for a one-off archive, or a position in a [RandomAccess] for updates. */
interface ZipSink {
    val position: Long
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
}

class StreamSink(private val output: OutputStream) : ZipSink {
    override var position = 0L
        private set
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        output.write(bytes, offset, length)
        position += length
    }
}

class RandomSink(private val file: RandomAccess, start: Long) : ZipSink {
    override var position = start
        private set
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        file.write(position, bytes, offset, length)
        position += length
    }
}

/** One archive member. [offset] points at its local header, [dataOffset] at the (possibly compressed) bytes. */
data class ZipEntryInfo(
    val name: String,
    val offset: Long,
    val dataOffset: Long,
    val compressedSize: Long,
    val size: Long,
    val crc: Long,
    val method: Int,
    val dosTime: Int,
    val dosDate: Int,
) {
    val end get() = dataOffset + compressedSize
}

class ZipFormatException(message: String) : IOException(message)

object ZipWriter {
    private const val UTF8_FLAG = 0x0800
    private const val MAX_32 = 0xFFFFFFFFL

    /** Writes [data] as a stored member at the sink's position. */
    fun writeEntry(sink: ZipSink, name: String, data: ByteArray, modified: Long = System.currentTimeMillis()): ZipEntryInfo {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val (time, date) = dosDateTime(modified)
        val offset = sink.position
        val header = Bytes(30 + nameBytes.size).apply {
            int(0x04034b50); short(10); short(UTF8_FLAG); short(0); short(time); short(date)
            int(crc); int(data.size.toLong()); int(data.size.toLong()); short(nameBytes.size); short(0); bytes(nameBytes)
        }
        sink.write(header.array)
        sink.write(data)
        return ZipEntryInfo(name, offset, offset + header.array.size, data.size.toLong(), data.size.toLong(), crc, 0, time, date)
    }

    /** Central directory and end record for [entries], ZIP64 when the archive outgrows 4 GB or 65 535 members. */
    fun writeDirectory(sink: ZipSink, entries: List<ZipEntryInfo>) {
        val start = sink.position
        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val wideOffset = entry.offset >= MAX_32
            val record = Bytes(46 + nameBytes.size + if (wideOffset) 12 else 0).apply {
                int(0x02014b50); short(if (wideOffset) 45 else 20); short(if (wideOffset) 45 else 10); short(UTF8_FLAG); short(entry.method)
                short(entry.dosTime); short(entry.dosDate); int(entry.crc); int(entry.compressedSize); int(entry.size)
                short(nameBytes.size); short(if (wideOffset) 12 else 0); short(0); short(0); short(0); int(0)
                int(if (wideOffset) MAX_32 else entry.offset); bytes(nameBytes)
                if (wideOffset) { short(0x0001); short(8); long(entry.offset) }
            }
            sink.write(record.array)
        }
        val directorySize = sink.position - start
        val wide = start >= MAX_32 || directorySize >= MAX_32 || entries.size >= 0xFFFF || entries.any { it.offset >= MAX_32 }
        if (wide) {
            val zip64End = sink.position
            sink.write(Bytes(56).apply {
                int(0x06064b50); long(44); short(45); short(45); int(0); int(0)
                long(entries.size.toLong()); long(entries.size.toLong()); long(directorySize); long(start)
            }.array)
            sink.write(Bytes(20).apply { int(0x07064b50); int(0); long(zip64End); int(1) }.array)
        }
        val count = if (wide) 0xFFFF else entries.size
        sink.write(Bytes(22).apply {
            int(0x06054b50); short(0); short(0); short(count); short(count)
            int(if (wide) MAX_32 else directorySize); int(if (wide) MAX_32 else start); short(0)
        }.array)
    }

    private fun dosDateTime(millis: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val year = c.get(Calendar.YEAR).coerceIn(1980, 2107)
        val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        val date = ((year - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        return time to date
    }
}

object ZipReader {
    private const val MAX_32 = 0xFFFFFFFFL

    /** Members listed by the central directory, or — when the tail is damaged — found by walking the local headers. */
    fun entries(file: RandomAccess): List<ZipEntryInfo> =
        try { directory(file) } catch (error: IOException) { scan(file).ifEmpty { throw error } }

    fun directory(file: RandomAccess): List<ZipEntryInfo> {
        val size = file.size
        if (size < 22) throw ZipFormatException("Archive too short")
        val tailLength = minOf(size, 22L + 0xFFFF).toInt()
        val tail = ByteArray(tailLength).also { file.read(size - tailLength, it) }
        val endAt = (tailLength - 22 downTo 0).firstOrNull { le32(tail, it) == 0x06054b50L } ?: throw ZipFormatException("No end record")
        var count = le16(tail, endAt + 10).toLong()
        var directorySize = le32(tail, endAt + 12)
        var directoryStart = le32(tail, endAt + 16)
        if (count == 0xFFFFL || directorySize == MAX_32 || directoryStart == MAX_32) {
            val locatorAt = endAt - 20
            if (locatorAt < 0 || le32(tail, locatorAt) != 0x07064b50L) throw ZipFormatException("No ZIP64 locator")
            val record = ByteArray(56).also { file.read(le64(tail, locatorAt + 8), it) }
            if (le32(record, 0) != 0x06064b50L) throw ZipFormatException("No ZIP64 end record")
            count = le64(record, 32); directorySize = le64(record, 40); directoryStart = le64(record, 48)
        }
        if (directoryStart + directorySize > size || directorySize > Int.MAX_VALUE) throw ZipFormatException("Directory out of range")
        val directory = ByteArray(directorySize.toInt()).also { file.read(directoryStart, it) }
        val result = ArrayList<ZipEntryInfo>()
        var at = 0
        repeat(count.toInt()) {
            if (at + 46 > directory.size || le32(directory, at) != 0x02014b50L) throw ZipFormatException("Broken directory")
            val method = le16(directory, at + 10)
            val time = le16(directory, at + 12)
            val date = le16(directory, at + 14)
            val crc = le32(directory, at + 16)
            var compressed = le32(directory, at + 20)
            var plain = le32(directory, at + 24)
            val nameLength = le16(directory, at + 28)
            val extraLength = le16(directory, at + 30)
            val commentLength = le16(directory, at + 32)
            var offset = le32(directory, at + 42)
            val name = String(directory, at + 46, nameLength, Charsets.UTF_8)
            var extra = at + 46 + nameLength
            val extraEnd = extra + extraLength
            while (extra + 4 <= extraEnd) {
                val id = le16(directory, extra)
                val length = le16(directory, extra + 2)
                if (id == 0x0001) {
                    var field = extra + 4
                    if (plain == MAX_32) { plain = le64(directory, field); field += 8 }
                    if (compressed == MAX_32) { compressed = le64(directory, field); field += 8 }
                    if (offset == MAX_32) offset = le64(directory, field)
                }
                extra += 4 + length
            }
            val local = ByteArray(30).also { file.read(offset, it) }
            if (le32(local, 0) != 0x04034b50L) throw ZipFormatException("Broken local header: $name")
            val dataOffset = offset + 30 + le16(local, 26) + le16(local, 28)
            result += ZipEntryInfo(name, offset, dataOffset, compressed, plain, crc, method, time, date)
            at = extraEnd + commentLength
        }
        return result
    }

    /** Recovery for an archive whose update was interrupted: stored members are self-describing, so walk them. */
    fun scan(file: RandomAccess): List<ZipEntryInfo> {
        val found = LinkedHashMap<String, ZipEntryInfo>()
        val header = ByteArray(30)
        var at = 0L
        while (at + 30 <= file.size) {
            file.read(at, header)
            if (le32(header, 0) != 0x04034b50L) break
            val flags = le16(header, 6)
            val method = le16(header, 8)
            val compressed = le32(header, 18)
            val nameLength = le16(header, 26)
            val dataOffset = at + 30 + nameLength + le16(header, 28)
            if (flags and 0x0008 != 0 || method != 0 || dataOffset + compressed > file.size) break
            val name = ByteArray(nameLength).also { file.read(at + 30, it) }.toString(Charsets.UTF_8)
            found[name] = ZipEntryInfo(name, at, dataOffset, compressed, le32(header, 22), le32(header, 14), method, le16(header, 10), le16(header, 12))
            at = dataOffset + compressed
        }
        return found.values.toList()
    }

    /** The member's content; stored and deflated members (an archive re-packed on a computer) are both read. */
    fun read(file: RandomAccess, entry: ZipEntryInfo): ByteArray {
        if (entry.size > Int.MAX_VALUE || entry.compressedSize > Int.MAX_VALUE) throw ZipFormatException("Entry too large: ${entry.name}")
        val raw = ByteArray(entry.compressedSize.toInt()).also { file.read(entry.dataOffset, it) }
        return when (entry.method) {
            0 -> raw
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(raw)
                    val out = ByteArray(entry.size.toInt())
                    var done = 0
                    while (done < out.size) {
                        val n = inflater.inflate(out, done, out.size - done)
                        if (n == 0 && (inflater.finished() || inflater.needsInput())) throw EOFException("Truncated entry: ${entry.name}")
                        done += n
                    }
                    out
                } finally { inflater.end() }
            }
            else -> throw ZipFormatException("Unsupported compression in ${entry.name}")
        }
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, at: Int) = le16(b, at).toLong() or (le16(b, at + 2).toLong() shl 16)
    private fun le64(b: ByteArray, at: Int) = le32(b, at) or (le32(b, at + 4) shl 32)
}

/** Little-endian record builder. */
private class Bytes(size: Int) {
    val array = ByteArray(size)
    private var at = 0
    fun short(value: Int) { array[at++] = value.toByte(); array[at++] = (value shr 8).toByte() }
    fun int(value: Int) = int(value.toLong())
    fun int(value: Long) { for (i in 0 until 4) array[at++] = (value shr (8 * i)).toByte() }
    fun long(value: Long) { for (i in 0 until 8) array[at++] = (value shr (8 * i)).toByte() }
    fun bytes(value: ByteArray) { value.copyInto(array, at); at += value.size }
}

/** A photo the archive should hold; [read] is called only when it is not in the archive yet. */
class ZipPhoto(val name: String, val length: Long, val modified: Long, val read: () -> ByteArray?)

data class ZipUpdateResult(val size: Long, val appended: Int, val compacted: Boolean, val missing: List<String>)

object ZipUpdater {
    /** Dead bytes (photos deleted since they were written) worth reclaiming: over a quarter of the file and 32 MB. */
    internal const val COMPACT_MIN_BYTES = 32L * 1024 * 1024

    /**
     * Brings [file] in line with [photos] and [meta]: photos already present stay where they are, new ones are
     * appended, and the metadata, directory and end record are rewritten after the last photo. Deleted photos drop
     * out of the directory; their bytes are reclaimed by sliding later photos down once they waste enough space.
     * An unreadable or foreign file is simply rewritten from the start.
     */
    fun update(file: RandomAccess, photos: List<ZipPhoto>, meta: List<Pair<String, ByteArray>>, compactMinBytes: Long = COMPACT_MIN_BYTES): ZipUpdateResult {
        val wanted = photos.associateBy { it.name }
        val existing = if (file.size == 0L) emptyList() else runCatching { ZipReader.entries(file) }.getOrDefault(emptyList())
        var kept = existing
            .filter { entry -> entry.method == 0 && wanted[entry.name]?.length == entry.size }
            .distinctBy { it.name }
            .sortedBy { it.offset }
        // Kept photos must not overlap; anything odd (a hand-edited archive) is rewritten instead of trusted.
        if (kept.zipWithNext().any { (a, b) -> b.offset < a.end } || kept.firstOrNull()?.offset?.let { it < 0 } == true) kept = emptyList()
        val keptEnd = kept.lastOrNull()?.end ?: 0L
        val live = kept.sumOf { it.end - it.offset }
        val compacted = keptEnd - live >= compactMinBytes && (keptEnd - live) * 4 >= keptEnd
        if (compacted) kept = slideDown(file, kept)
        val sink = RandomSink(file, kept.lastOrNull()?.end ?: 0L)
        // Truncate first: the new tail is written over a shorter file, never interleaved with the old one.
        file.truncate(sink.position)
        val have = kept.map { it.name }.toSet()
        val entries = kept.toMutableList()
        val missing = mutableListOf<String>()
        var appended = 0
        for (photo in photos) {
            if (photo.name in have) continue
            val data = photo.read()
            if (data == null) { missing += photo.name; continue }
            entries += ZipWriter.writeEntry(sink, photo.name, data, photo.modified)
            appended++
        }
        // Photos keep the archive order they were written in; the directory lists them before the metadata.
        for ((name, data) in meta) entries += ZipWriter.writeEntry(sink, name, data)
        ZipWriter.writeDirectory(sink, entries)
        return ZipUpdateResult(sink.position, appended, compacted, missing)
    }

    /** One-off archive for a stream (manual export): photos, then metadata, then the directory. */
    fun write(sink: ZipSink, photos: List<ZipPhoto>, meta: List<Pair<String, ByteArray>>): List<String> {
        val entries = mutableListOf<ZipEntryInfo>()
        val missing = mutableListOf<String>()
        for (photo in photos) {
            val data = photo.read()
            if (data == null) missing += photo.name else entries += ZipWriter.writeEntry(sink, photo.name, data, photo.modified)
        }
        for ((name, data) in meta) entries += ZipWriter.writeEntry(sink, name, data)
        ZipWriter.writeDirectory(sink, entries)
        return missing
    }

    // Copies each member (header and data) to the lowest free offset; always forwards, so a member never overwrites
    // bytes it still has to read.
    private fun slideDown(file: RandomAccess, entries: List<ZipEntryInfo>): List<ZipEntryInfo> {
        val buffer = ByteArray(1024 * 1024)
        var target = 0L
        return entries.map { entry ->
            val shift = entry.offset - target
            if (shift > 0) {
                var done = 0L
                val length = entry.end - entry.offset
                while (done < length) {
                    val n = minOf(buffer.size.toLong(), length - done).toInt()
                    file.read(entry.offset + done, buffer, 0, n)
                    file.write(target + done, buffer, 0, n)
                    done += n
                }
            }
            target += entry.end - entry.offset
            entry.copy(offset = entry.offset - shift, dataOffset = entry.dataOffset - shift)
        }
    }
}
