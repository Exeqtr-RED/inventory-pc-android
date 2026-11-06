package ru.pcinventory.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Минимальный XLSX-читатель/писатель БЕЗ внешних библиотек.
 *
 * ВАЖНО: файл является ZIP-контейнером с XML-частями (Office Open XML).
 * Текст пишется через inlineStr (строки не выносятся в sharedStrings) —
 * этого достаточно для Excel/LibreOffice/Google Sheets.
 */

data class XlsxCell(val text: String? = null, val number: Double? = null, val bold: Boolean = false)
data class XlsxRow(val cells: List<XlsxCell>)
data class XlsxSheet(val name: String, val rows: List<XlsxRow>, val colWidths: List<Double> = emptyList())

private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val NS_PKG_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
private const val REL_WORKSHEET = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
private const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

/** Индекс колонки в буквенное имя: 1→A, 26→Z, 27→AA. */
fun colName(index: Int): String {
    require(index >= 1) { "Индекс колонки должен быть >= 1" }
    var n = index
    val sb = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        sb.insert(0, ('A' + rem))
        n = (n - 1) / 26
    }
    return sb.toString()
}

/** Обратное преобразование "A1" → 1, "AB12" → 28 (только буквенная часть). */
private fun colIndex(ref: String): Int {
    var n = 0
    for (ch in ref) {
        if (!ch.isLetter()) break
        n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
    }
    return n
}

/** XML-экранирование текста: &amp; &lt; &gt; &quot;; управляющие символы (&lt;32) вырезаются. */
private fun esc(s: String): String = buildString(s.length) {
    for (ch in s) {
        when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch.code < 32 -> {} // вырезаем управляющие символы
            else -> append(ch)
        }
    }
}

/** Число без лишних хвостов: целое — без точки. */
private fun num(v: Double): String =
    if (!v.isInfinite() && !v.isNaN() && v == kotlin.math.floor(v) && kotlin.math.abs(v) < 9.0E15) {
        v.toLong().toString()
    } else v.toString()

// ---------------------------------------------------------------- WRITER ----

/** Собирает XLSX (zip с XML-частями) из списка листов. */
fun buildXlsx(sheets: List<XlsxSheet>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        // [Content_Types].xml
        val ct = StringBuilder().apply {
            append(XML_DECL)
            append("<Types xmlns=\"").append(NS_PKG_CT).append("\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            for (i in sheets.indices) {
                append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            append("</Types>")
        }.toString()
        entry("[Content_Types].xml", ct)

        // _rels/.rels
        entry(
            "_rels/.rels",
            XML_DECL + "<Relationships xmlns=\"" + NS_PKG_REL + "\">" +
                "<Relationship Id=\"rId1\" Type=\"" + REL_DOC + "\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>"
        )

        // xl/workbook.xml
        val wb = StringBuilder().apply {
            append(XML_DECL)
            append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_R).append("\"><sheets>")
            sheets.forEachIndexed { i, s ->
                append("<sheet name=\"").append(esc(s.name)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>")
            }
            append("</sheets></workbook>")
        }.toString()
        entry("xl/workbook.xml", wb)

        // xl/_rels/workbook.xml.rels — листы + последний rId на styles
        val rels = StringBuilder().apply {
            append(XML_DECL)
            append("<Relationships xmlns=\"").append(NS_PKG_REL).append("\">")
            for (i in sheets.indices) {
                append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(REL_WORKSHEET)
                    .append("\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>")
            }
            append("<Relationship Id=\"rId").append(sheets.size + 1).append("\" Type=\"").append(REL_STYLES)
                .append("\" Target=\"styles.xml\"/>")
            append("</Relationships>")
        }.toString()
        entry("xl/_rels/workbook.xml.rels", rels)

        // xl/styles.xml — минимальный набор: обычный + жирный шрифт
        entry("xl/styles.xml", STYLES_XML)

        // листы
        sheets.forEachIndexed { i, sheet ->
            entry("xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet))
        }
    }
    return out.toByteArray()
}

private const val STYLES_XML: String =
    XML_DECL +
        "<styleSheet xmlns=\"" + NS_MAIN + "\">" +
        "<fonts count=\"2\">" +
        "<font><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "</fonts>" +
        "<fills count=\"2\">" +
        "<fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "</fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "</cellXfs>" +
        "</styleSheet>"

private fun sheetXml(sheet: XlsxSheet): String = buildString {
    append(XML_DECL)
    append("<worksheet xmlns=\"").append(NS_MAIN).append("\">")
    if (sheet.colWidths.isNotEmpty()) {
        append("<cols>")
        sheet.colWidths.forEachIndexed { i, w ->
            append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                .append("\" width=\"").append(num(w)).append("\" customWidth=\"1\"/>")
        }
        append("</cols>")
    }
    append("<sheetData>")
    sheet.rows.forEachIndexed { ri, row ->
        val r = ri + 1
        if (row.cells.isEmpty()) {
            append("<row r=\"").append(r).append("\"/>")
            return@forEachIndexed
        }
        append("<row r=\"").append(r).append("\">")
        row.cells.forEachIndexed { ci, cell ->
            val ref = colName(ci + 1) + r
            val style = if (cell.bold) " s=\"1\"" else ""
            when {
                cell.text != null ->
                    append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style)
                        .append("><is><t xml:space=\"preserve\">").append(esc(cell.text))
                        .append("</t></is></c>")
                cell.number != null ->
                    append("<c r=\"").append(ref).append("\"").append(style)
                        .append("><v>").append(num(cell.number)).append("</v></c>")
                // text == null && number == null → пустая ячейка, пропускаем
            }
        }
        append("</row>")
    }
    append("</sheetData></worksheet>")
}

// ---------------------------------------------------------------- READER ----

/**
 * Читает ПЕРВЫЙ лист XLSX в матрицу строк (пустые ячейки — "").
 * Поддерживает sharedStrings, inlineStr и числовые ячейки.
 */
fun readFirstSheet(input: InputStream): List<List<String>> {
    // 1. Вычитываем все части zip в память (файлы инвентаризации небольшие)
    val entries = HashMap<String, ByteArray>()
    ZipInputStream(BufferedInputStream(input)).use { zip ->
        var e: ZipEntry? = zip.nextEntry
        while (e != null) {
            if (!e.isDirectory) entries[e.name] = zip.readBytes()
            zip.closeEntry()
            e = zip.nextEntry
        }
    }
    if (entries.isEmpty()) return emptyList()

    // 2. sharedStrings (текст всех <t> внутри <si>, включая rich text <r><t>)
    val shared = ArrayList<String>()
    entries["xl/sharedStrings.xml"]?.let { shared.addAll(parseSharedStrings(it)) }

    // 3. workbook.xml → список (name, r:id)
    val sheets = entries["xl/workbook.xml"]?.let { parseWorkbook(it) } ?: return emptyList()
    if (sheets.isEmpty()) return emptyList()

    // 4. rels: rId → Target (нормализуем к имени части "xl/...")
    val rels = HashMap<String, String>()
    entries["xl/_rels/workbook.xml.rels"]?.let { parseRels(it, rels) }

    val firstRid = sheets[0].second
    val target = rels[firstRid] ?: return emptyList()
    val normalized = when {
        target.startsWith("/") -> target.removePrefix("/")
        target.startsWith("xl/") -> target
        else -> "xl/$target"
    }
    val bytes = entries[normalized] ?: return emptyList()
    return parseSheet(bytes, shared)
}

private fun newParser(bytes: ByteArray): XmlPullParser {
    val p = Xml.newPullParser()
    try {
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    } catch (_: Exception) {
        // не все реализации поддерживают переключение — работаем и без него
    }
    p.setInput(ByteArrayInputStream(bytes), null)
    return p
}

/** Атрибут по локальному имени (учитывает префиксы вида r:id). */
private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).substringAfter(':') == name) return getAttributeValue(i)
    }
    return null
}

private fun parseSharedStrings(bytes: ByteArray): List<String> {
    val p = newParser(bytes)
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inSi = false
    var inT = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name) {
                "si" -> { inSi = true; sb.setLength(0) }
                "t" -> if (inSi) inT = true
            }
            XmlPullParser.TEXT -> if (inT) sb.append(p.text)
            XmlPullParser.END_TAG -> when (p.name) {
                "t" -> inT = false
                "si" -> { out.add(sb.toString()); inSi = false; inT = false }
            }
        }
        ev = p.next()
    }
    return out
}

private fun parseWorkbook(bytes: ByteArray): List<Pair<String, String>> {
    val p = newParser(bytes)
    val out = ArrayList<Pair<String, String>>()
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
            val name = p.attr("name") ?: ""
            val rid = p.attr("id") ?: "rId${out.size + 1}"
            out.add(name to rid)
        }
        ev = p.next()
    }
    return out
}

private fun parseRels(bytes: ByteArray, out: MutableMap<String, String>) {
    val p = newParser(bytes)
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
            val id = p.attr("Id")
            val target = p.attr("Target")
            if (id != null && target != null) out[id] = target
        }
        ev = p.next()
    }
}

private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
    val p = newParser(bytes)
    val rows = ArrayList<MutableList<String>>()
    var curRow: MutableList<String>? = null
    var col = 0
    var type = ""
    var mode = 0 // 1 — внутри <v>, 2 — внутри <is>, 3 — внутри <is><t>
    val sb = StringBuilder()

    fun assign() {
        val row = curRow ?: return
        if (col < 1 || col > row.size) return
        val raw = sb.toString()
        val value = if (type == "s") {
            val idx = raw.trim().toIntOrNull()
            if (idx != null && idx in shared.indices) shared[idx] else raw
        } else raw
        row[col - 1] = value
    }

    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name) {
                "row" -> {
                    val r = p.attr("r")?.trim()?.toIntOrNull() ?: (rows.size + 1)
                    while (rows.size < r) rows.add(ArrayList())
                    curRow = rows[r - 1]
                    col = 0
                }
                "c" -> {
                    val row = curRow
                    if (row != null) {
                        val ref = p.attr("r")
                        col = if (!ref.isNullOrBlank()) colIndex(ref) else col + 1
                        while (row.size < col) row.add("")
                        type = p.attr("t") ?: ""
                        mode = 0
                        sb.setLength(0)
                    }
                }
                "v" -> { mode = 1; sb.setLength(0) }
                "is" -> { mode = 2; sb.setLength(0) }
                "t" -> if (mode == 2) { mode = 3; sb.setLength(0) }
            }
            XmlPullParser.TEXT -> if (mode >= 1) sb.append(p.text)
            XmlPullParser.END_TAG -> when (p.name) {
                "v" -> if (mode == 1) { assign(); mode = 0 }
                "t" -> if (mode == 3) { assign(); mode = 2 }
                "row" -> curRow = null
            }
        }
        ev = p.next()
    }
    return rows
}
