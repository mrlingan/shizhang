package com.lingansir.ooo

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.SAXException

data class RowIssue(val row: Int, val message: String)
data class ImportBatch(val filename: String, val records: List<LedgerRecord>, val issues: List<RowIssue>, val rowsWithImages: Int)

/** SpreadsheetML .xlsx codec using platform ZIP/XML; no formula evaluation or network access. */
object ExcelWorkbook {
    const val MAX_FILE = 20 * 1024 * 1024
    const val MAX_ROWS = 10_000
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    val headers = listOf("日期", "类型", "分类", "金额（元）", "付款方式", "备注", "图片数量", "账单ID")
    fun readLimited(input: InputStream, limit: Int = MAX_FILE): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "文件内容过大，请拆分后导入。" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun export(records: List<LedgerRecord>): ByteArray {
        require(records.size <= MAX_ROWS) { "每次最多导出 10,000 笔，请按月份导出。" }
        val rows = StringBuilder()
        fun textCell(index: Int, row: Int, value: String, style: Int = 0): String {
            require(value.length <= 32767) { "备注超过 Excel 单元格长度限制。" }
            return "<c r=\"${('A'.code+index).toChar()}$row\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>"
        }
        rows.append("<row r=\"1\">")
        headers.forEachIndexed { i, h -> rows.append(textCell(i, 1, h, 1)) }
        rows.append("</row>")
        records.sortedBy { it.date }.forEachIndexed { i, r ->
            val row = i + 2
            rows.append("<row r=\"$row\">")
            val values = listOf(r.date.toString(), r.kind.title, r.category.title, Money.input(r.amountMinor),
                r.paymentMethod, r.note, r.imageNames.size.toString(), r.id)
            values.forEachIndexed { col, value ->
                if (col == 3 || col == 6) rows.append("<c r=\"${('A'.code+col).toChar()}$row\" s=\"${if (col == 3) 2 else 0}\"><v>$value</v></c>")
                else rows.append(textCell(col, row, value))
            }
            rows.append("</row>")
        }
        val last = records.size + 1
        val files = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="$NS" xmlns:r="$REL"><workbookPr date1904="0"/><sheets><sheet name="账单" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$REL/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to """<styleSheet xmlns="$NS"><fonts count="2"><font><sz val="11"/><name val="Microsoft YaHei"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Microsoft YaHei"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF217D66"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$NS"><dimension ref="A1:H$last"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="24"/><cols><col min="1" max="5" width="20" customWidth="1"/><col min="6" max="6" width="48" customWidth="1"/><col min="7" max="8" width="40" customWidth="1"/></cols><sheetData>$rows</sheetData><autoFilter ref="A1:H$last"/></worksheet>"""
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> files.forEach { (path, xml) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }
        }.toByteArray().also { require(it.size <= MAX_FILE) { "导出文件超过 20 MB，请按月份拆分。" } }
    }
    fun read(data: ByteArray, filename: String): ImportBatch {
        require(data.size <= MAX_FILE) { "文件超过 20 MB，请拆分后导入。" }
        val files = mutableMapOf<String, ByteArray>()
        var total = 0L
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 2048) { "Excel 内部文件过多。" }
                    if (!entry.isDirectory) {
                        val bytes = readLimited(zip, 32 * 1024 * 1024)
                        total += bytes.size
                        require(total <= 64 * 1024 * 1024) { "Excel 解压后的内容过大。" }
                        require(!files.containsKey(entry.name)) { "Excel 包含重复内容。" }
                        if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) files[entry.name] = bytes
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) { throw IllegalArgumentException("无法打开 Excel：${e.message}。请使用未加密的 .xlsx 文件。", e) }
        fun part(path: String) = parse(files[path] ?: error("Excel 缺少必要内容：$path"))
        val workbook = part("xl/workbook.xml")
        val relationships = part("xl/_rels/workbook.xml.rels").children("Relationship")
        fun target(type: String) = relationships.find { it.getAttribute("Type").endsWith("/$type") }
            ?.takeIf { it.getAttribute("TargetMode") != "External" }?.getAttribute("Target")?.let(::packagePath)
        val shared = target("sharedStrings")?.let { path -> files[path]?.let(::parse) }
            ?.children("si")?.map { it.stringText() } ?: emptyList()
        val sheets = workbook.child("sheets")?.children("sheet")?.filter { it.getAttribute("state") !in listOf("hidden", "veryHidden") } ?: emptyList()
        val sheet = sheets.find { it.getAttribute("name") == "账单" } ?: sheets.firstOrNull() ?: error("未找到可导入的工作表。")
        val id = sheet.getAttributeNS(REL, "id")
        val relation = relationships.find { it.getAttribute("Id") == id } ?: error("工作表引用无效。")
        require(relation.getAttribute("TargetMode") != "External") { "不支持外部工作表。" }
        val root = part(packagePath(relation.getAttribute("Target")))
        val date1904 = workbook.child("workbookPr")?.getAttribute("date1904") in listOf("1", "true")
        val rows = root.child("sheetData")?.children("row") ?: emptyList()
        require(rows.size <= MAX_ROWS + 20) { "每次最多导入 10,000 笔账单。" }
        val decoded = rows.mapIndexed { offset, row ->
            var next = 0
            val cells = mutableMapOf<Int, Cell>()
            row.children("c").forEach { cell ->
                val ref = cell.getAttribute("r")
                val index = if (ref.isEmpty()) next else columnIndex(ref)
                require(index in 0..16383 && index !in cells) { "单元格坐标无效或重复。" }
                next = index + 1
                val type = cell.getAttribute("t").ifEmpty { "n" }
                var value = cell.child("v")?.textContent ?: ""
                if (type == "inlineStr") value = cell.child("is")?.stringText() ?: ""
                if (type == "s") value = shared.getOrNull(value.toIntOrNull() ?: -1) ?: error("表格中的文本引用已损坏。")
                cells[index] = Cell(value, type, cell.child("f") != null)
            }
            Pair(row.getAttribute("r").toIntOrNull() ?: offset + 1, cells)
        }
        val aliases = mapOf("金额" to "金额（元）", "金额(元)" to "金额（元）", "收支类型" to "类型", "收支" to "类型", "支付方式" to "付款方式", "账单 ID" to "账单ID")
        var headerOffset = -1
        var mapping: Map<String, Int> = emptyMap()
        for ((offset, row) in decoded.take(20).withIndex()) {
            val candidate = mutableMapOf<String, Int>()
            row.second.forEach { (index, cell) ->
                val trimmed = cell.value.trim()
                val name = aliases[trimmed] ?: trimmed
                if (name in headers) { require(name !in candidate) { "表头“$name”重复。" }; candidate[name] = index }
            }
            if (headers.take(5).all { it in candidate }) { headerOffset = offset; mapping = candidate; break }
        }
        require(headerOffset >= 0) { "缺少表头，请使用导入模板，保留日期、类型、分类、金额（元）、付款方式。" }
        val records = mutableListOf<LedgerRecord>()
        val issues = mutableListOf<RowIssue>()
        val ids = mutableSetOf<String>()
        var imageRows = 0
        var dataRows = 0
        for ((rowNumber, cells) in decoded.drop(headerOffset + 1)) {
            fun cell(label: String) = mapping[label]?.let { cells[it] } ?: Cell("", "str", false)
            fun value(label: String) = cell(label).value.trim()
            if (headers.filterNot { it == "图片数量" }.all { value(it).isEmpty() }) continue
            require(++dataRows <= MAX_ROWS) { "每次最多导入 10,000 笔账单。" }
            try {
                require(mapping.keys.none { cell(it).formula }) { "请将公式转换为值后导入。" }
                val kind = Kind.entries.find { it.title == value("类型") } ?: error("类型必须是“支出”或“收入”。")
                val category = Category.options(kind).find { it.title == value("分类") } ?: error("分类不适用于${kind.title}，请参考模板。")
                val amount = Money.parseExcel(value("金额（元）")) ?: error("金额需大于 0、最多两位小数，最大 999999999.99。")
                val date = parseDate(cell("日期"), date1904) ?: error("日期无效，请输入 Excel 日期或 yyyy-MM-dd。")
                val method = value("付款方式")
                require(method.isNotEmpty() && method.codePointCount(0, method.length) <= 20) { "付款方式需为 1–20 个字。" }
                val idText = value("账单ID")
                require(idText.isEmpty() || Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(idText)) { "账单ID格式无效，新账单请留空。" }
                val recordId = if (idText.isEmpty()) UUID.randomUUID().toString() else UUID.fromString(idText).toString()
                require(ids.add(recordId)) { "账单ID与本表其他行重复。" }
                if ((value("图片数量").toIntOrNull() ?: 0) > 0) imageRows++
                records.add(LedgerRecord(recordId, kind, amount, category, method, date, cell("备注").value))
            } catch (e: Exception) { issues.add(RowIssue(rowNumber, e.message ?: "该行内容无效。")) }
        }
        return ImportBatch(filename, records, issues, imageRows)
    }
    private data class Cell(val value: String, val type: String, val formula: Boolean)
    private fun parseDate(cell: Cell, date1904: Boolean): LocalDate? = runCatching {
        val value = cell.value.trim()
        if (cell.type == "n") {
            val serial = BigDecimal(value)
            val max = if (date1904) 2957004 else 2958466
            require(serial >= BigDecimal(if (date1904) 0 else 1) && serial < BigDecimal(max))
            val day = serial.toLong()
            require(date1904 || day != 60L)
            if (date1904) LocalDate.of(1904, 1, 1).plusDays(day)
            else LocalDate.of(1899, 12, 31).plusDays(if (day > 60) day - 1 else day)
        } else {
            if (cell.type == "d" && value.contains('T')) OffsetDateTime.parse(value).toLocalDate()
            else {
                val formats = listOf("uuuu-MM-dd", "uuuu/MM/dd", "uuuu年MM月dd日")
                formats.firstNotNullOfOrNull { format -> runCatching {
                    LocalDate.parse(value, DateTimeFormatter.ofPattern(format).withResolverStyle(ResolverStyle.STRICT))
                }.getOrNull() } ?: error("日期格式无效")
            }
        }.also { require(it.year in 1..9999) }
    }.getOrNull()
    private fun packagePath(target: String): String {
        val parts = if (target.startsWith('/')) mutableListOf() else mutableListOf("xl")
        target.split('/').forEach { p -> when(p) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()) { "Excel 文件路径无效。" }; parts.removeAt(parts.lastIndex) }
            else -> { require(':' !in p && '\\' !in p) { "Excel 路径无效。" }; parts.add(p) }
        } }
        require(parts.firstOrNull() == "xl") { "Excel 文件路径无效。" }
        return parts.joinToString("/")
    }
    private fun parse(bytes: ByteArray): Element {
        val probe = String(bytes, Charsets.ISO_8859_1).replace("\u0000", "").lowercase()
        require("<!doctype" !in probe && "<!entity" !in probe) { "不支持含外部实体的 XML。" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("禁止外部实体") }
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }
    private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { it.localName == name || it.tagName == name }
    private fun Element.child(name: String) = children(name).firstOrNull()
    private fun Element.stringText(): String {
        val nodes = getElementsByTagNameNS("*", "t")
        return (0 until nodes.length).joinToString("") { nodes.item(it).textContent }
    }
    private fun columnIndex(ref: String): Int {
        require(Regex("[A-Za-z]+[1-9][0-9]*").matches(ref)) { "单元格坐标无效。" }
        var result = 0
        for (c in ref.takeWhile { it.isLetter() }) { require(result < 16384); result = result * 26 + c.uppercaseChar().code - 'A'.code + 1 }
        return result - 1
    }
    private fun escape(s: String): String {
        require(s.none { it.code < 32 && it !in "\t\n\r" }) { "文字中包含 Excel 不支持的控制字符。" }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }
}
