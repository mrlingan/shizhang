import Foundation
import ZIPFoundation

struct ExcelRowIssue: Identifiable, Sendable {
    var id: Int { row }
    let row: Int
    let message: String
}

struct ExcelImportBatch: Identifiable, Sendable {
    let id = UUID()
    let filename: String
    let records: [LedgerRecord]
    let issues: [ExcelRowIssue]
    let rowsWithImages: Int
}

enum ExcelError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        if case .message(let text) = self { return text }
        return nil
    }
}

/// A small SpreadsheetML codec. ZIPFoundation owns ZIP compression and validation.
/// It supports ordinary Excel/WPS shared strings, inline strings and both date systems.
enum ExcelWorkbook {
    static let headers = ["日期", "类型", "分类", "金额（元）", "付款方式", "备注", "图片数量", "账单ID"]
    static let maximumRows = 10_000
    static let maximumFileBytes = 20 * 1024 * 1024
    private static let maximumXMLBytes = 32 * 1024 * 1024
    private static let ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    static func export(_ records: [LedgerRecord]) throws -> Data {
        guard records.count <= maximumRows else { throw ExcelError.message("每次最多导出 10,000 笔账单，请按月份分批导出。") }
        let sorted = records.sorted { $0.date < $1.date }
        var rows = [rowXML(1, cells: headers.enumerated().map { textCell(column($0.offset) + "1", $0.element, style: 1) })]
        for (index, record) in sorted.enumerated() {
            let row = index + 2
            rows.append(rowXML(row, cells: [
                numberCell("A\(row)", String(excelSerial(record.date)), style: 3),
                textCell("B\(row)", record.kind.title), textCell("C\(row)", record.category.title),
                numberCell("D\(row)", Money.input(record.amountMinor), style: 2),
                textCell("E\(row)", record.paymentMethod), textCell("F\(row)", record.note),
                numberCell("G\(row)", String(record.imageNames.count)), textCell("H\(row)", record.id.uuidString)
            ]))
        }
        let last = max(sorted.count + 1, 1)
        let sheet = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="\(ns)">
        <dimension ref="A1:H\(last)"/>
        <sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
        <sheetFormatPr defaultRowHeight="24"/>
        <cols><col min="1" max="1" width="16" customWidth="1"/><col min="2" max="3" width="12" customWidth="1"/><col min="4" max="4" width="20" customWidth="1"/><col min="5" max="5" width="24" customWidth="1"/><col min="6" max="6" width="48" customWidth="1"/><col min="7" max="7" width="12" customWidth="1"/><col min="8" max="8" width="40" customWidth="1"/></cols>
        <sheetData>\(rows.joined())</sheetData><autoFilter ref="A1:H\(last)"/>
        </worksheet>
        """
        let archive = try Archive(accessMode: .create)
        let files = [
            "[Content_Types].xml": """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>
            """,
            "_rels/.rels": """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
            """,
            "xl/workbook.xml": """
            <workbook xmlns="\(ns)" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><workbookPr date1904="0"/><sheets><sheet name="账单" sheetId="1" r:id="rId1"/></sheets></workbook>
            """,
            "xl/_rels/workbook.xml.rels": """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>
            """,
            "xl/styles.xml": styles,
            "xl/worksheets/sheet1.xml": sheet
        ]
        for name in files.keys.sorted() {
            let data = Data(files[name]!.utf8)
            try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count), compressionMethod: .deflate) { offset, size in
                data.subdata(in: Int(offset)..<Int(offset) + size)
            }
        }
        guard let data = archive.data else { throw ExcelError.message("Excel 文件生成失败。") }
        return data
    }

    static func read(_ data: Data, filename: String) throws -> ExcelImportBatch {
        guard data.count <= maximumFileBytes else { throw ExcelError.message("文件超过 20 MB，请拆分后导入。") }
        let archive: Archive
        do { archive = try Archive(data: data, accessMode: .read) }
        catch { throw ExcelError.message("无法打开文件。请使用未加密的 .xlsx 文件，旧版 .xls 不受支持。") }
        var inflatedBytes = 0
        func readPart(_ path: String, optional: Bool = false) throws -> Data? {
            guard let entry = archive[path] else {
                if optional { return nil }
                throw ExcelError.message("Excel 文件缺少必要内容：\(path)。")
            }
            guard entry.type == .file, entry.uncompressedSize <= UInt64(maximumXMLBytes) else {
                throw ExcelError.message("Excel 内容过大或文件格式不受支持。")
            }
            var result = Data()
            let crc = try archive.extract(entry) { chunk in
                inflatedBytes += chunk.count
                guard result.count + chunk.count <= maximumXMLBytes, inflatedBytes <= 64 * 1024 * 1024 else {
                    throw ExcelError.message("Excel 解压后的内容过大，请拆分文件。")
                }
                result.append(chunk)
            }
            guard crc == entry.checksum else { throw ExcelError.message("Excel 文件已损坏，请重新保存后导入。") }
            return result
        }
        let workbook = try XMLTree.parse(readPart("xl/workbook.xml")!)
        let relationships = try XMLTree.parse(readPart("xl/_rels/workbook.xml.rels")!)
        let sheets = workbook.child("sheets")?.children.filter { $0.name == "sheet" && $0.attributes["state"] != "hidden" && $0.attributes["state"] != "veryHidden" } ?? []
        guard let sheet = sheets.first(where: { $0.attributes["name"] == "账单" }) ?? sheets.first,
              let relationshipID = sheet.attributes["r:id"],
              let relationship = relationships.children.first(where: { $0.attributes["Id"] == relationshipID }),
              relationship.attributes["TargetMode"] != "External",
              let target = relationship.attributes["Target"] else { throw ExcelError.message("未找到可导入的账单工作表。") }
        let sheetPath = try packagePath(target)
        var sharedStrings: [String] = []
        if let sharedRelationship = relationships.children.first(where: { $0.attributes["Type"]?.hasSuffix("/sharedStrings") == true }),
           sharedRelationship.attributes["TargetMode"] != "External", let sharedTarget = sharedRelationship.attributes["Target"],
           let sharedData = try readPart(packagePath(sharedTarget), optional: true) {
            sharedStrings = try XMLTree.parse(sharedData).children.filter { $0.name == "si" }.map(\.stringText)
        }
        let date1904 = ["1", "true"].contains(workbook.child("workbookPr")?.attributes["date1904"] ?? "0")
        let root = try XMLTree.parse(readPart(sheetPath)!)
        let rows = root.child("sheetData")?.children.filter { $0.name == "row" } ?? []
        guard rows.count <= maximumRows + 20 else { throw ExcelError.message("每次最多导入 10,000 笔账单，请拆分文件。") }
        let decoded = try rows.enumerated().map { offset, node -> SheetRow in
            var cells: [Int: SheetCell] = [:]
            var nextColumn = 0
            for cell in node.children where cell.name == "c" {
                let index = cell.attributes["r"].flatMap(columnIndex) ?? nextColumn
                guard index < 16_384, cells[index] == nil else { throw ExcelError.message("表格中存在无效或重复的单元格坐标。") }
                nextColumn = index + 1
                let type = cell.attributes["t"] ?? "n"
                var value = cell.child("v")?.text ?? ""
                if type == "inlineStr" { value = cell.child("is")?.stringText ?? "" }
                if type == "s" {
                    guard let i = Int(value), sharedStrings.indices.contains(i) else { throw ExcelError.message("表格中的文本引用已损坏。") }
                    value = sharedStrings[i]
                }
                cells[index] = SheetCell(value: value, type: type, formula: cell.child("f") != nil)
            }
            return SheetRow(number: Int(node.attributes["r"] ?? "") ?? offset + 1, cells: cells)
        }
        let aliases = ["金额": "金额（元）", "金额(元)": "金额（元）", "收支类型": "类型", "收支": "类型", "支付方式": "付款方式", "账单 ID": "账单ID"]
        var mapping: [String: Int] = [:]
        var headerOffset: Int?
        for (offset, row) in decoded.prefix(20).enumerated() {
            var candidate: [String: Int] = [:]
            for (index, cell) in row.cells {
                let trimmed = cell.value.trimmingCharacters(in: .whitespacesAndNewlines)
                let label = aliases[trimmed] ?? trimmed
                if headers.contains(label) {
                    guard candidate[label] == nil else { throw ExcelError.message("表头“\(label)”重复，请只保留一列。") }
                    candidate[label] = index
                }
            }
            if ["日期", "类型", "分类", "金额（元）", "付款方式"].allSatisfy({ candidate[$0] != nil }) {
                mapping = candidate; headerOffset = offset; break
            }
        }
        guard let headerOffset else { throw ExcelError.message("缺少表头。请使用导入模板，保留日期、类型、分类、金额（元）、付款方式这五列。") }
        var records: [LedgerRecord] = []
        var issues: [ExcelRowIssue] = []
        var imageRows = 0
        var ids = Set<UUID>()
        var dataRowCount = 0
        for row in decoded.dropFirst(headerOffset + 1) {
            func cell(_ label: String) -> SheetCell { mapping[label].flatMap { row.cells[$0] } ?? SheetCell(value: "", type: "str", formula: false) }
            func value(_ label: String) -> String { cell(label).value.trimmingCharacters(in: .whitespacesAndNewlines) }
            guard ["日期", "类型", "分类", "金额（元）", "付款方式", "备注", "账单ID"].contains(where: { !value($0).isEmpty }) else { continue }
            dataRowCount += 1
            guard dataRowCount <= maximumRows else { throw ExcelError.message("每次最多导入 10,000 笔账单。") }
            do {
                guard !mapping.keys.contains(where: { cell($0).formula }) else { throw ExcelError.message("请将公式转换为值后导入。") }
                guard let kind = RecordKind.allCases.first(where: { $0.title == value("类型") }) else { throw ExcelError.message("类型必须是“支出”或“收入”。") }
                guard let category = RecordCategory.options(for: kind).first(where: { $0.title == value("分类") }) else { throw ExcelError.message("分类不适用于\(kind.title)，请参考模板中的分类说明。") }
                guard let amount = parseAmount(value("金额（元）")) else { throw ExcelError.message("金额必须大于 0、最多两位小数且不超过 999999999.99。") }
                guard let date = parseDate(cell("日期"), date1904: date1904) else { throw ExcelError.message("日期无效，请输入真实的 Excel 日期或 yyyy-MM-dd。") }
                let method = value("付款方式")
                guard !method.isEmpty, method.count <= 20 else { throw ExcelError.message("付款方式需为 1–20 个字。") }
                let idText = value("账单ID")
                let id: UUID
                if idText.isEmpty { id = UUID() }
                else if let parsed = UUID(uuidString: idText) { id = parsed }
                else { throw ExcelError.message("账单ID格式无效；新账单请将此列留空。") }
                guard ids.insert(id).inserted else { throw ExcelError.message("账单ID与本表其他行重复。") }
                if let count = Int(value("图片数量")), count > 0 { imageRows += 1 }
                records.append(LedgerRecord(id: id, kind: kind, amountMinor: amount, category: category, paymentMethod: method, date: date, note: cell("备注").value, imageNames: []))
            } catch { issues.append(ExcelRowIssue(row: row.number, message: error.localizedDescription)) }
        }
        return ExcelImportBatch(filename: filename, records: records, issues: issues, rowsWithImages: imageRows)
    }

    static func parseAmount(_ text: String) -> Int64? {
        guard !text.isEmpty, text.count <= 40,
              text.range(of: "^[+]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$", options: .regularExpression) != nil,
              let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")),
              value > 0, value <= Decimal(string: "999999999.99")! else { return nil }
        var cents = value * 100
        var rounded = Decimal()
        NSDecimalRound(&rounded, &cents, 0, .plain)
        guard rounded == cents else { return nil }
        return NSDecimalNumber(decimal: cents).int64Value
    }

    private static func parseDate(_ cell: SheetCell, date1904: Bool) -> Date? {
        let value = cell.value.trimmingCharacters(in: .whitespacesAndNewlines)
        if cell.type == "n", let serial = Double(value), serial.isFinite,
           serial >= (date1904 ? 0 : 1), serial < (date1904 ? 2_957_004 : 2_958_466),
           date1904 || Int(serial) != 60 {
            var utc = Calendar(identifier: .gregorian)
            utc.timeZone = TimeZone(secondsFromGMT: 0)!
            let base = utc.date(from: date1904 ? DateComponents(year: 1904, month: 1, day: 1) : DateComponents(year: 1899, month: 12, day: serial < 60 ? 31 : 30))!
            let wallDate = base.addingTimeInterval(serial * 86_400)
            var components = utc.dateComponents([.year, .month, .day, .hour, .minute, .second], from: wallDate)
            components.timeZone = .current
            var local = Calendar(identifier: .gregorian)
            local.timeZone = .current
            return local.date(from: components)
        }
        if cell.type == "d" {
            let formatter = ISO8601DateFormatter()
            if let date = formatter.date(from: value) { return date }
        }
        for format in ["yyyy-MM-dd", "yyyy/MM/dd", "yyyy年MM月dd日"] {
            let formatter = DateFormatter()
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = .current
            formatter.dateFormat = format
            formatter.isLenient = false
            if let date = formatter.date(from: value), formatter.string(from: date) == value { return date }
        }
        return nil
    }

    private static func excelSerial(_ date: Date) -> Double {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = .current
        let components = local.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let wallDate = utc.date(from: components)!
        let base = utc.date(from: DateComponents(year: 1899, month: 12, day: 30))!
        let serial = wallDate.timeIntervalSince(base) / 86_400
        return serial < 61 ? serial - 1 : serial
    }

    private static func packagePath(_ target: String) throws -> String {
        var parts: [String] = target.hasPrefix("/") ? [] : ["xl"]
        for part in target.split(separator: "/") {
            if part == "." { continue }
            if part == ".." {
                guard !parts.isEmpty else { throw ExcelError.message("Excel 文件路径无效。") }
                parts.removeLast()
            } else { parts.append(String(part)) }
        }
        let path = parts.joined(separator: "/")
        guard path.hasPrefix("xl/"), path.hasSuffix(".xml"), !path.contains(":") else { throw ExcelError.message("Excel 工作表路径不受支持。") }
        return path
    }

    private static func column(_ offset: Int) -> String { String(UnicodeScalar(65 + offset)!) }
    private static func columnIndex(_ reference: String) -> Int? {
        let letters = reference.prefix { $0.isASCII && $0.isLetter }
        guard !letters.isEmpty, letters.count <= 3 else { return nil }
        return letters.uppercased().utf8.reduce(0) { $0 * 26 + Int($1) - 64 } - 1
    }
    private static func rowXML(_ number: Int, cells: [String]) -> String { "<row r=\"\(number)\">\(cells.joined())</row>" }
    private static func textCell(_ address: String, _ value: String, style: Int = 0) -> String {
        "<c r=\"\(address)\" s=\"\(style)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(escape(value))</t></is></c>"
    }
    private static func numberCell(_ address: String, _ value: String, style: Int = 0) -> String {
        "<c r=\"\(address)\" s=\"\(style)\"><v>\(value)</v></c>"
    }
    private static func escape(_ text: String) -> String {
        String(text.unicodeScalars.filter { $0.value >= 32 || [9, 10, 13].contains($0.value) })
            .replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }
    private static let styles = """
    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd"/></numFmts><fonts count="2"><font><sz val="11"/><color rgb="FF1F3338"/><name val="Arial"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF217D66"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>
    """
}

private struct SheetCell {
    let value: String
    let type: String
    let formula: Bool
}
private struct SheetRow {
    let number: Int
    let cells: [Int: SheetCell]
}

/// Bounded XML tree, with external entities and DTDs disabled.
private final class XMLTree: NSObject, XMLParserDelegate {
    final class Node {
        let name: String
        let attributes: [String: String]
        var children: [Node] = []
        var text = ""
        init(name: String, attributes: [String: String]) { self.name = name; self.attributes = attributes }
        func child(_ name: String) -> Node? { children.first { $0.name == name } }
        var stringText: String { name == "t" ? text : children.filter { $0.name != "rPh" }.map(\.stringText).joined() }
    }
    private var stack: [Node] = []
    private var root: Node?
    private var nodes = 0
    private var rejected = false
    static func parse(_ data: Data) throws -> Node {
        // Reject declarations before parsing, including UTF-16 declarations.
        let content = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16) ?? ""
        guard !content.uppercased().contains("<!DOCTYPE"), !content.uppercased().contains("<!ENTITY") else {
            throw ExcelError.message("Excel XML 包含不受支持的实体声明。")
        }
        let delegate = XMLTree()
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        guard parser.parse(), !delegate.rejected, let root = delegate.root else { throw ExcelError.message("Excel XML 格式无效或内容过大。") }
        return root
    }
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String]) {
        nodes += 1
        guard stack.count < 64, nodes <= 500_000 else { rejected = true; parser.abortParsing(); return }
        let node = Node(name: elementName.components(separatedBy: ":").last!, attributes: attributeDict)
        if let parent = stack.last { parent.children.append(node) } else { root = node }
        stack.append(node)
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { stack.last?.text += string }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) { if !stack.isEmpty { stack.removeLast() } }
}
