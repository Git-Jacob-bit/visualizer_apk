package pl.visualizer.montaz

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random

class BackupZipTest {
    @get:Rule val temp = TemporaryFolder()

    private fun photo(name: String, size: Int, seed: Int = name.hashCode()): Pair<ZipPhoto, ByteArray> {
        val data = Random(seed).nextBytes(size)
        return ZipPhoto(name, size.toLong(), 0L) { data } to data
    }

    private fun head(version: String) = listOf("backup.json" to """{"v":"$version"}""".toByteArray())
    private fun tail(version: String) = listOf("index.html" to "<p>ąę $version</p>".toByteArray())

    /** What a computer sees: the JDK's own ZIP reader must list and read every member. */
    private fun standardContents(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes() }
    }

    private fun update(file: File, photos: List<ZipPhoto>, version: String, compactMin: Long = ZipUpdater.COMPACT_MIN_BYTES) =
        FileRandomAccess(file, writable = true).use { ZipUpdater.update(it, photos, head(version), tail(version), compactMin) }

    @Test fun freshArchiveIsReadableByStandardZip() {
        val file = temp.newFile("backup.zip")
        val (a, aData) = photo("projekty/p1/a.jpg", 5000)
        val (b, bData) = photo("projekty/p1/b.jpg", 7000)
        val result = update(file, listOf(a, b), "1")
        assertEquals(2, result.appended)
        assertEquals(file.length(), result.size)
        val contents = standardContents(file)
        assertArrayEquals(aData, contents.getValue(a.name))
        assertArrayEquals(bData, contents.getValue(b.name))
        assertEquals("<p>ąę 1</p>", contents.getValue("index.html").toString(Charsets.UTF_8))
    }

    @Test fun updateKeepsPhotosInPlaceAndOnlyAppendsNewOnes() {
        val file = temp.newFile("backup.zip")
        val (a, _) = photo("projekty/p1/a.jpg", 5000)
        update(file, listOf(a), "1")
        val offsetBefore = FileRandomAccess(file, false).use { ZipReader.directory(it).first { e -> e.name == a.name }.offset }
        var reads = 0
        val counted = ZipPhoto(a.name, a.length, 0L) { reads++; a.read() }
        val (b, bData) = photo("projekty/p2/b.jpg", 3000)
        val result = update(file, listOf(counted, b), "2")
        assertEquals("an existing photo is never re-read", 0, reads)
        assertEquals(1, result.appended)
        val entries = FileRandomAccess(file, false).use { ZipReader.directory(it) }
        assertEquals(offsetBefore, entries.first { it.name == a.name }.offset)
        val contents = standardContents(file)
        assertArrayEquals(bData, contents.getValue(b.name))
        assertEquals("""{"v":"2"}""", contents.getValue("backup.json").toString(Charsets.UTF_8))
        assertEquals(4, contents.size)
    }

    @Test fun deletedPhotoDisappearsAndSpaceIsReclaimedByCompaction() {
        val file = temp.newFile("backup.zip")
        val (a, _) = photo("projekty/p1/a.jpg", 40_000)
        val (b, _) = photo("projekty/p1/b.jpg", 40_000)
        val (c, cData) = photo("projekty/p1/c.jpg", 10_000)
        update(file, listOf(a, b, c), "1")
        val full = file.length()
        // Without compaction the dead bytes stay but are no longer listed.
        update(file, listOf(c), "2", compactMin = Long.MAX_VALUE)
        assertTrue(file.length() >= full - 1000)
        assertEquals(setOf(c.name, "backup.json", "index.html"), standardContents(file).keys)
        // With compaction the remaining photo slides to the start.
        val result = update(file, listOf(c), "3", compactMin = 1)
        assertTrue(result.compacted)
        assertTrue(file.length() < 20_000)
        assertArrayEquals(cData, standardContents(file).getValue(c.name))
    }

    @Test fun trailingDeletedPhotoIsCutOffWithoutCompaction() {
        val file = temp.newFile("backup.zip")
        val (a, _) = photo("projekty/p1/a.jpg", 10_000)
        val (b, _) = photo("projekty/p1/b.jpg", 50_000)
        update(file, listOf(a, b), "1")
        update(file, listOf(a), "2", compactMin = Long.MAX_VALUE)
        assertTrue(file.length() < 15_000)
        assertEquals(setOf(a.name, "backup.json", "index.html"), standardContents(file).keys)
    }

    @Test fun interruptedUpdateIsRecoveredByScanning() {
        val file = temp.newFile("backup.zip")
        val (a, aData) = photo("projekty/p1/a.jpg", 5000)
        val (b, _) = photo("projekty/p1/b.jpg", 6000)
        update(file, listOf(a, b), "1")
        // Simulate a crash right after the tail was cut: no metadata, no directory.
        val bEnd = FileRandomAccess(file, false).use { ZipReader.directory(it).first { it.name == b.name }.end }
        FileRandomAccess(file, true).use { it.truncate(bEnd) }
        val recovered = FileRandomAccess(file, false).use { ZipReader.entries(it) }.associateBy { it.name }
        // The manifest is written before the photos, so recovery still finds it.
        assertEquals(setOf("backup.json", a.name, b.name), recovered.keys)
        assertArrayEquals(aData, FileRandomAccess(file, false).use { ZipReader.read(it, recovered.getValue(a.name)) })
        var reads = 0
        val result = update(file, listOf(ZipPhoto(a.name, a.length, 0L) { reads++; aData }, b), "2")
        assertEquals(0, reads)
        assertEquals(0, result.appended)
        assertEquals(4, standardContents(file).size)
    }

    @Test fun updateKilledWhileAddingPhotosStillIdentifiesItself() {
        val file = temp.newFile("backup.zip")
        val (a, aData) = photo("projekty/p1/a.jpg", 5000)
        update(file, listOf(a), "1")
        // The process dies while reading the new photo: no directory is written, but the manifest already is.
        val dying = ZipPhoto("projekty/p1/b.jpg", 6000, 0L) { throw IllegalStateException("killed") }
        runCatching { update(file, listOf(a, dying), "2") }
        FileRandomAccess(file, false).use { access ->
            val found = ZipReader.entries(access).associateBy { it.name }
            assertEquals("""{"v":"2"}""", ZipReader.read(access, found.getValue("backup.json")).toString(Charsets.UTF_8))
            assertArrayEquals(aData, ZipReader.read(access, found.getValue(a.name)))
        }
        // The next update heals it into a complete archive.
        val (b, bData) = photo("projekty/p1/b.jpg", 6000)
        update(file, listOf(a, b), "3")
        assertArrayEquals(bData, standardContents(file).getValue(b.name))
    }

    @Test fun damagedPhotoIsDetectedAndRewritten() {
        val file = temp.newFile("backup.zip")
        val (a, aData) = photo("projekty/p1/a.jpg", 5000)
        update(file, listOf(a), "1")
        val entry = FileRandomAccess(file, false).use { ZipReader.directory(it).first { it.name == a.name } }
        // Flip one data byte and cut the directory off, as an interrupted compaction could.
        FileRandomAccess(file, true).use { access ->
            val one = ByteArray(1).also { access.read(entry.dataOffset + 100, it) }
            one[0] = (one[0] + 1).toByte()
            access.write(entry.dataOffset + 100, one)
            access.truncate(entry.end)
        }
        FileRandomAccess(file, false).use { access ->
            assertFalse(ZipReader.intact(access, ZipReader.entries(access).first { it.name == a.name }))
        }
        var reads = 0
        update(file, listOf(ZipPhoto(a.name, a.length, 0L) { reads++; aData }), "2")
        assertEquals("a damaged recovered photo is written again", 1, reads)
        assertArrayEquals(aData, standardContents(file).getValue(a.name))
    }

    @Test fun manifestIsFoundWhenAnotherMemberNoLongerMatchesTheDirectory() {
        val file = temp.newFile("backup.zip")
        val (a, _) = photo("projekty/p1/a.jpg", 5000)
        update(file, listOf(a), "1")
        // A stale offset (interrupted compaction): the photo's local header no longer carries its name.
        val entry = FileRandomAccess(file, false).use { ZipReader.directory(it).first { it.name == a.name } }
        FileRandomAccess(file, true).use { it.write(entry.offset + 30, "X".toByteArray()) }
        FileRandomAccess(file, false).use { access ->
            assertTrue(runCatching { ZipReader.directory(access) }.isFailure)
            assertEquals("""{"v":"1"}""", ZipReader.find(access, "backup.json")!!.second.toString(Charsets.UTF_8))
        }
    }

    @Test fun duplicatePhotoNamesAreWrittenOnce() {
        val file = temp.newFile("backup.zip")
        val (a, _) = photo("projekty/p1/a.jpg", 5000)
        update(file, listOf(a, a), "1")
        val names = FileRandomAccess(file, false).use { ZipReader.directory(it).map { e -> e.name } }
        assertEquals(1, names.count { it == a.name })
    }

    @Test fun garbageFileIsRewritten() {
        val file = temp.newFile("backup.zip")
        file.writeBytes(Random(1).nextBytes(10_000))
        val (a, aData) = photo("projekty/p1/a.jpg", 5000)
        update(file, listOf(a), "1")
        assertArrayEquals(aData, standardContents(file).getValue(a.name))
    }

    @Test fun missingPhotoIsReportedAndSkipped() {
        val file = temp.newFile("backup.zip")
        val gone = ZipPhoto("projekty/p1/gone.jpg", 10, 0L) { null }
        val result = update(file, listOf(gone), "1")
        assertEquals(listOf(gone.name), result.missing)
        assertFalse(gone.name in standardContents(file))
    }

    @Test fun deflatedArchiveFromComputerCanBeRead() {
        val file = temp.newFile("repacked.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            zip.write("""{"v":"pc"}""".repeat(200).toByteArray())
            zip.closeEntry()
        }
        FileRandomAccess(file, false).use { access ->
            val entry = ZipReader.entries(access).single()
            assertEquals(8, entry.method)
            assertEquals("""{"v":"pc"}""".repeat(200), ZipReader.read(access, entry).toString(Charsets.UTF_8))
        }
    }

    @Test fun streamedArchiveMatchesStandardZip() {
        val file = temp.newFile("archive.zip")
        val (a, aData) = photo("projekty/p1/a.jpg", 5000)
        file.outputStream().use { ZipUpdater.write(StreamSink(it), listOf(a), head("1"), tail("1")) }
        assertArrayEquals(aData, standardContents(file).getValue(a.name))
    }

    @Test fun zip64DirectoryIsWrittenAndRead() {
        // Forces the ZIP64 end record by pretending an entry sits beyond 4 GB; the reader must parse it back.
        val sinkFile = temp.newFile("dir.bin")
        val far = ZipEntryInfo("far.jpg", 5_000_000_000L, 5_000_000_100L, 10, 10, 0, 0, 0, 33)
        FileRandomAccess(sinkFile, true).use { access ->
            val sink = RandomSink(access, 0)
            ZipWriter.writeDirectory(sink, listOf(far))
        }
        val bytes = sinkFile.readBytes()
        // ZIP64 end record and locator signatures are present.
        assertTrue(bytes.toList().windowed(4).any { it == listOf<Byte>(0x50, 0x4b, 0x06, 0x06) })
        assertTrue(bytes.toList().windowed(4).any { it == listOf<Byte>(0x50, 0x4b, 0x06, 0x07) })
    }
}
