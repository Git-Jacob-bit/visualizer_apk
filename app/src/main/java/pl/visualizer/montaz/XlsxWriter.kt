package pl.visualizer.montaz

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One prepared photo for the photo sheet: a square JPEG plus the texts printed under it. */
class XlsxPhoto(val jpeg: ByteArray, val pn: String, val steps: String)

class XlsxTexts(
    val visualSheet: String,
    val photoSheet: String,
    val title: String,
    val photoTitle: String,
    val previous: String,
    val current: String,
    val description: String,
    val assembly: String,
    val componentNo: String,
    val sensorNo: String,
    val stepNo: String,
    val stepPrefix: String,
)

/**
 * Hand-written SpreadsheetML: a blank step template that mirrors the plant's visualisation workbook, and a sheet
 * with every project photo on an identical square, ready to copy into the template. No library, so the APK stays small.
 */
object XlsxWriter {
    private const val EMU_PER_PX = 9525L
    // Photo sheet: a square picture centred in a square-ish cell, plus a margin so neighbours never touch.
    private const val PHOTO_PX = 180
    private const val PHOTO_MARGIN_PX = 8
    private const val PHOTO_COLUMNS = 3

    // Colours as Excel shows the theme tints in the plant's workbook.
    private const val BLUE = "FF83CBEB"
    private const val ORANGE = "FFF2AA84"
    private const val YELLOW = "FFFFFF00"
    private const val GREEN = "FF47D45A"

    // cellXfs indices in styles.xml.
    private const val S_TITLE = 1
    private const val S_COLUMN_HEAD = 2
    private const val S_PREVIOUS = 3
    private const val S_CURRENT = 4
    private const val S_FRAME = 5
    private const val S_TEXT = 6
    private const val S_CAPTION = 7
    private const val S_STEP = 8
    private const val S_STEP_TEXT = 9
    private const val S_ASSEMBLY = 10

    /**
     * Template blocks: each step number written into its block, then blank spares for steps added later, then
     * [assemblyBlocks] blank cable-assembly blocks (one wide photo, a longer instruction, one caption bar).
     */
    fun write(output: OutputStream, steps: List<Int?>, assemblyBlocks: Int, photos: List<XlsxPhoto>, texts: XlsxTexts) {
        ZipOutputStream(output).use { zip ->
            fun put(name: String, body: String) = put(zip, name, body.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", contentTypes(photos.isNotEmpty()))
            put("_rels/.rels", rels(listOf(Triple("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument", "xl/workbook.xml"))))
            put("xl/workbook.xml", workbook(texts))
            put("xl/_rels/workbook.xml.rels", rels(listOf(
                Triple("rId1", "$REL/worksheet", "worksheets/sheet1.xml"),
                Triple("rId2", "$REL/worksheet", "worksheets/sheet2.xml"),
                Triple("rId3", "$REL/styles", "styles.xml"),
            )))
            put("xl/styles.xml", styles())
            put("xl/worksheets/sheet1.xml", templateSheet(steps, assemblyBlocks, texts))
            put("xl/worksheets/sheet2.xml", photoSheet(photos, texts))
            if (photos.isNotEmpty()) {
                put("xl/worksheets/_rels/sheet2.xml.rels", rels(listOf(Triple("rId1", "$REL/drawing", "../drawings/drawing1.xml"))))
                put("xl/drawings/drawing1.xml", drawing(photos))
                put("xl/drawings/_rels/drawing1.xml.rels", rels(photos.indices.map { Triple("rId${it + 1}", "$REL/image", "../media/image${it + 1}.jpeg") }))
                photos.forEachIndexed { index, photo -> put(zip, "xl/media/image${index + 1}.jpeg", photo.jpeg) }
            }
        }
    }

    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun contentTypes(hasPhotos: Boolean) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        if (hasPhotos) append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (sheet in 1..2) append("""<Override PartName="/xl/worksheets/sheet$sheet.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        if (hasPhotos) append("""<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""")
        append("</Types>")
    }

    private fun rels(items: List<Triple<String, String, String>>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        items.forEach { (id, type, target) -> append("""<Relationship Id="$id" Type="$type" Target="$target"/>""") }
        append("</Relationships>")
    }

    private fun workbook(texts: XlsxTexts) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$MAIN" xmlns:r="$REL">""" +
        """<sheets><sheet name="${attr(sheetName(texts.visualSheet))}" sheetId="1" r:id="rId1"/><sheet name="${attr(sheetName(texts.photoSheet))}" sheetId="2" r:id="rId2"/></sheets></workbook>"""

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="$MAIN">""" +
        """<fonts count="4"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font><font><b/><sz val="11"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><b/><sz val="16"/><name val="Calibri"/><family val="2"/></font><font><b/><sz val="14"/><name val="Calibri"/><family val="2"/></font></fonts>""" +
        """<fills count="6"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""" +
        listOf(BLUE, ORANGE, YELLOW, GREEN).joinToString("") { """<fill><patternFill patternType="solid"><fgColor rgb="$it"/><bgColor indexed="64"/></patternFill></fill>""" } +
        """</fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>""" +
        """<border><left style="thin"><color indexed="64"/></left><right style="thin"><color indexed="64"/></right><top style="thin"><color indexed="64"/></top><bottom style="thin"><color indexed="64"/></bottom><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="11">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        xf(font = 2, vertical = "center") +
        xf(font = 1, horizontal = "center", vertical = "center", wrap = true) +
        xf(font = 1, fill = 2, border = 1, horizontal = "center", vertical = "center") +
        xf(font = 1, fill = 3, border = 1, horizontal = "center", vertical = "center") +
        xf(border = 1, horizontal = "center", vertical = "center", wrap = true) +
        xf(border = 1, vertical = "top", wrap = true) +
        xf(font = 1, fill = 4, border = 1, horizontal = "center", vertical = "center", wrap = true) +
        xf(font = 3, border = 1, horizontal = "center", vertical = "center") +
        xf(horizontal = "center", vertical = "top", wrap = true) +
        xf(font = 1, fill = 5, border = 1, horizontal = "center", vertical = "center") +
        """</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

    private fun xf(font: Int = 0, fill: Int = 0, border: Int = 0, horizontal: String? = null, vertical: String? = null, wrap: Boolean = false) =
        """<xf numFmtId="0" fontId="$font" fillId="$fill" borderId="$border" xfId="0"""" +
            (if (font != 0) """ applyFont="1"""" else "") + (if (fill != 0) """ applyFill="1"""" else "") +
            (if (border != 0) """ applyBorder="1"""" else "") + """ applyAlignment="1"><alignment""" +
            (horizontal?.let { """ horizontal="$it"""" } ?: "") + (vertical?.let { """ vertical="$it"""" } ?: "") +
            (if (wrap) """ wrapText="1"""" else "") + "/></xf>"

    // Columns B–H as in the plant's sheet: previous photo, current photo, description, component no, sensor no, step no.
    private fun templateSheet(steps: List<Int?>, assemblyBlocks: Int, texts: XlsxTexts): String {
        val rows = StringBuilder()
        val merges = mutableListOf("B1:H1")
        rows.append(row(1, 30.0, cell("B1", texts.title, S_TITLE)))
        rows.append(row(2, 20.0, cell("F2", texts.componentNo, S_COLUMN_HEAD) + cell("G2", texts.sensorNo, S_COLUMN_HEAD) + cell("H2", texts.stepNo, S_COLUMN_HEAD)))
        var r = 3
        for (step in steps) {
            r++ // spacer row keeps blocks apart, the way the plant's sheet separates them with thick rules
            rows.append(row(r, 19.5, cell("C$r", texts.previous, S_PREVIOUS) + cell("D$r", texts.current, S_CURRENT) + cell("E$r", texts.description, S_CURRENT)))
            r++
            rows.append(row(r, 145.0, cell("C$r", "", S_FRAME) + cell("D$r", "", S_FRAME) + cell("E$r", "", S_TEXT) +
                cell("F$r", "", S_FRAME) + cell("G$r", "", S_FRAME) + (step?.let { number("H$r", it, S_STEP) } ?: cell("H$r", "", S_STEP))))
            r++
            rows.append(row(r, 19.5, cell("C$r", "", S_CAPTION) + cell("D$r", "", S_CAPTION) + cell("E$r", "", S_CAPTION)))
            r++
        }
        repeat(assemblyBlocks) {
            r++
            merges += "C$r:E$r"
            rows.append(row(r, 19.5, cell("C$r", texts.assembly, S_ASSEMBLY) + cell("D$r", "", S_ASSEMBLY) + cell("E$r", "", S_ASSEMBLY)))
            r++
            merges += "C$r:D$r"
            rows.append(row(r, 145.0, cell("C$r", "", S_FRAME) + cell("D$r", "", S_FRAME) + cell("E$r", "", S_TEXT) +
                cell("F$r", "", S_FRAME) + cell("G$r", "", S_FRAME) + cell("H$r", "", S_STEP)))
            r++
            merges += "C$r:E$r"
            rows.append(row(r, 19.5, cell("C$r", "", S_CAPTION) + cell("D$r", "", S_CAPTION) + cell("E$r", "", S_CAPTION)))
            r++
        }
        val cols = """<cols><col min="1" max="2" width="3.7" customWidth="1"/><col min="3" max="4" width="27.7" customWidth="1"/>""" +
            """<col min="5" max="5" width="34.7" customWidth="1"/><col min="6" max="6" width="17.7" customWidth="1"/>""" +
            """<col min="7" max="7" width="14.7" customWidth="1"/><col min="8" max="8" width="11.7" customWidth="1"/></cols>"""
        return sheet(cols, rows.toString(), merges, freezeRows = 2, drawing = false)
    }

    private fun photoSheet(photos: List<XlsxPhoto>, texts: XlsxTexts): String {
        val rows = StringBuilder()
        rows.append(row(1, 30.0, cell("B1", texts.photoTitle, S_TITLE)))
        val merges = mutableListOf("B1:F1")
        photos.chunked(PHOTO_COLUMNS).forEachIndexed { line, group ->
            val pictureRow = photoRow(line)
            rows.append(row(pictureRow, pxToPt(PHOTO_PX + 2 * PHOTO_MARGIN_PX), ""))
            rows.append(row(pictureRow + 1, 19.5, group.withIndex().joinToString("") { (i, photo) -> cell("${photoColumn(i)}${pictureRow + 1}", photo.pn, S_CAPTION) }))
            rows.append(row(pictureRow + 2, 33.0, group.withIndex().joinToString("") { (i, photo) ->
                cell("${photoColumn(i)}${pictureRow + 2}", if (photo.steps.isBlank()) "" else "${texts.stepPrefix} ${photo.steps}", S_STEP_TEXT)
            }))
        }
        // Width in characters for a cell the picture fills with its margin (Calibri 11: 7 px per character + 5 px padding).
        val photoWidth = (PHOTO_PX + 2 * PHOTO_MARGIN_PX - 5) / 7.0
        val cols = """<cols><col min="1" max="1" width="2.7" customWidth="1"/><col min="2" max="2" width="$photoWidth" customWidth="1"/>""" +
            """<col min="3" max="3" width="2.7" customWidth="1"/><col min="4" max="4" width="$photoWidth" customWidth="1"/>""" +
            """<col min="5" max="5" width="2.7" customWidth="1"/><col min="6" max="6" width="$photoWidth" customWidth="1"/></cols>"""
        return sheet(cols, rows.toString(), merges, freezeRows = 1, drawing = photos.isNotEmpty())
    }

    private fun photoRow(line: Int) = 3 + line * 4
    private fun photoColumn(index: Int) = "BDF"[index].toString()

    private fun drawing(photos: List<XlsxPhoto>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$REL">""")
        val size = PHOTO_PX * EMU_PER_PX
        val offset = PHOTO_MARGIN_PX * EMU_PER_PX
        photos.forEachIndexed { index, photo ->
            val column = 1 + (index % PHOTO_COLUMNS) * 2
            val row = photoRow(index / PHOTO_COLUMNS) - 1
            val name = listOf(photo.pn, photo.steps).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "image${index + 1}" }
            append("""<xdr:oneCellAnchor><xdr:from><xdr:col>$column</xdr:col><xdr:colOff>$offset</xdr:colOff><xdr:row>$row</xdr:row><xdr:rowOff>$offset</xdr:rowOff></xdr:from>""")
            append("""<xdr:ext cx="$size" cy="$size"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${index + 2}" name="${attr(name)}"/>""")
            append("""<xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${index + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""")
            append("""<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$size" cy="$size"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>""")
        }
        append("</xdr:wsDr>")
    }

    private fun sheet(cols: String, rows: String, merges: List<String>, freezeRows: Int, drawing: Boolean) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="$MAIN" xmlns:r="$REL"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""" +
            """<sheetViews><sheetView workbookViewId="0"><pane ySplit="$freezeRows" topLeftCell="A${freezeRows + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""" +
            """<sheetFormatPr defaultRowHeight="15"/>$cols<sheetData>$rows</sheetData>""" +
            (if (merges.isEmpty()) "" else """<mergeCells count="${merges.size}">${merges.joinToString("") { """<mergeCell ref="$it"/>""" }}</mergeCells>""") +
            """<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/><pageSetup paperSize="9" orientation="portrait" fitToWidth="1" fitToHeight="0"/>""" +
            (if (drawing) """<drawing r:id="rId1"/>""" else "") + "</worksheet>"

    private fun row(index: Int, heightPt: Double, cells: String) = """<row r="$index" ht="$heightPt" customHeight="1">$cells</row>"""

    private fun cell(ref: String, text: String, style: Int) =
        if (text.isEmpty()) """<c r="$ref" s="$style"/>"""
        else """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${text(text)}</t></is></c>"""

    private fun number(ref: String, value: Int, style: Int) = """<c r="$ref" s="$style"><v>$value</v></c>"""

    private fun pxToPt(px: Int) = px * 72.0 / 96.0

    // Excel rejects sheet names over 31 characters or with []:*?/\ in them.
    private fun sheetName(name: String) = name.replace(Regex("""[\[\]:*?/\\]"""), " ").take(31)

    private fun text(value: String) = value.filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(value: String) = text(value).replace("\"", "&quot;").replace("\n", " ")
}
