import XCTest
import ZIPFoundation
@testable import Shizhang

final class ExcelTests: XCTestCase {
    private func sample(amount: Int64 = 2850) -> LedgerRecord {
        let date = Calendar(identifier: .gregorian).date(from: DateComponents(year: 2026, month: 10, day: 5))!
        return LedgerRecord(kind: .expense, amountMinor: amount, category: .food, paymentMethod: "家庭钱包", date: date, note: "午餐 & <咖啡>\n=1+1", imageNames: ["receipt.jpg"])
    }

    func testExportRoundTripPreservesExactAmountsIDsAndLiteralText() throws {
        let records = [sample(amount: 1), sample(amount: 99_999_999_999)]
        let data = try ExcelWorkbook.export(records)
        let archive = try Archive(data: data, accessMode: .read)
        XCTAssertNotNil(archive["[Content_Types].xml"])
        XCTAssertNotNil(archive["xl/styles.xml"])
        let batch = try ExcelWorkbook.read(data, filename: "export.xlsx")
        XCTAssertTrue(batch.issues.isEmpty)
        XCTAssertEqual(Set(batch.records.map(\.id)), Set(records.map(\.id)))
        XCTAssertEqual(Set(batch.records.map(\.amountMinor)), Set([1, 99_999_999_999]))
        XCTAssertEqual(batch.records.first?.note, records.first?.note)
        XCTAssertTrue(batch.records.allSatisfy { $0.imageNames.isEmpty })
        XCTAssertEqual(batch.rowsWithImages, 2)
        XCTAssertTrue(Calendar.current.isDate(batch.records[0].date, inSameDayAs: records[0].date))
    }

    func testIndependentSharedStringWorkbookAndNumericExcelDates() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ExcelSharedStrings", withExtension: "xlsx"))
        let data = try Data(contentsOf: url)
        let batch = try ExcelWorkbook.read(data, filename: "fixture.xlsx")
        XCTAssertTrue(batch.issues.isEmpty, "\(batch.issues)")
        XCTAssertEqual(batch.records.count, 2)
        XCTAssertEqual(batch.records[0].amountMinor, 2850)
        XCTAssertEqual(batch.records[1].amountMinor, 1_234_567)
        XCTAssertEqual(batch.records[0].note, "测试午餐 & 咖啡")
        XCTAssertEqual(batch.records[1].kind, .income)
        XCTAssertEqual(Calendar.current.component(.day, from: batch.records[0].date), 5)
        XCTAssertEqual(Calendar.current.component(.month, from: batch.records[0].date), 10)
    }

    func testBundledTemplateHasRecognizedHeadersAndNoSampleRecords() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "账单导入模板", withExtension: "xlsx"))
        let batch = try ExcelWorkbook.read(Data(contentsOf: url), filename: "template.xlsx")
        XCTAssertTrue(batch.records.isEmpty)
        XCTAssertTrue(batch.issues.isEmpty)
    }

    func testInvalidAmountsAndFormulaCellsProduceRowErrors() throws {
        let exported = try ExcelWorkbook.export([sample()])
        let invalid = try rewriting(exported) { name, xml in
            name == "xl/worksheets/sheet1.xml" ? xml.replacingOccurrences(of: "<v>28.50</v>", with: "<v>28.501</v>") : xml
        }
        let invalidBatch = try ExcelWorkbook.read(invalid, filename: "invalid.xlsx")
        XCTAssertEqual(invalidBatch.issues.first?.row, 2)
        XCTAssertTrue(invalidBatch.records.isEmpty)
        let formula = try rewriting(exported) { name, xml in
            name == "xl/worksheets/sheet1.xml" ? xml.replacingOccurrences(of: "<v>28.50</v>", with: "<f>1+1</f><v>2</v>") : xml
        }
        let formulaBatch = try ExcelWorkbook.read(formula, filename: "formula.xlsx")
        XCTAssertEqual(formulaBatch.issues.count, 1)
        XCTAssertTrue(formulaBatch.issues[0].message.contains("公式"))
    }

    func testNumericAmountParsingDoesNotAcceptPartialOrRoundedValues() {
        XCTAssertEqual(ExcelWorkbook.parseAmount("1.234567E4"), 1_234_567)
        XCTAssertEqual(ExcelWorkbook.parseAmount("1e-2"), 1)
        for text in ["1-2", "1e", "1e+", "1.2.3", "0", "-2", "1.001", "1,000", "NaN", "999999999.991"] {
            XCTAssertNil(ExcelWorkbook.parseAmount(text), text)
        }
    }

    func test1904DatesAndNonstandardWorksheetPath() throws {
        let data = try rewriting(ExcelWorkbook.export([sample()])) { name, xml in
            if name == "xl/workbook.xml" { return xml.replacingOccurrences(of: "date1904=\"0\"", with: "date1904=\"1\"") }
            if name == "xl/_rels/workbook.xml.rels" { return xml.replacingOccurrences(of: "worksheets/sheet1.xml", with: "/xl/worksheets/sheet1.xml") }
            if name == "xl/worksheets/sheet1.xml" {
                return xml.replacingOccurrences(of: "<c r=\"A2\" s=\"3\"><v>46300.0</v>", with: "<c r=\"A2\" s=\"3\"><v>44838.0</v>")
            }
            return xml
        }
        let batch = try ExcelWorkbook.read(data, filename: "1904.xlsx")
        XCTAssertTrue(batch.issues.isEmpty)
        XCTAssertEqual(Calendar.current.component(.year, from: batch.records[0].date), 2026)
        XCTAssertEqual(Calendar.current.component(.month, from: batch.records[0].date), 10)
        XCTAssertEqual(Calendar.current.component(.day, from: batch.records[0].date), 5)
    }

    func testDamagedFilesMissingHeadersAndDTDRejected() throws {
        XCTAssertThrowsError(try ExcelWorkbook.read(Data("not a zip".utf8), filename: "bad.xlsx"))
        let exported = try ExcelWorkbook.export([sample()])
        let missing = try rewriting(exported) { name, xml in
            name == "xl/worksheets/sheet1.xml" ? xml.replacingOccurrences(of: "金额（元）", with: "错误表头") : xml
        }
        XCTAssertThrowsError(try ExcelWorkbook.read(missing, filename: "missing.xlsx"))
        let dtd = try rewriting(exported) { name, xml in
            name == "xl/worksheets/sheet1.xml" ? xml.replacingOccurrences(of: "<worksheet", with: "<!DOCTYPE worksheet [<!ENTITY x 'bad'>]><worksheet") : xml
        }
        XCTAssertThrowsError(try ExcelWorkbook.read(dtd, filename: "dtd.xlsx"))
    }

    @MainActor
    func testBatchImportAddsPaymentMethodsAndSkipsExistingIDsWithoutLosingPhotos() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LedgerStore(directory: directory)
        var existing = sample()
        existing.paymentMethod = "现金"
        existing.imageNames = []
        try store.save(existing, newImages: [Data([1, 2, 3])])
        let savedPhoto = try XCTUnwrap(store.records.first?.imageNames.first)
        var incoming = existing
        incoming.amountMinor = 9900
        incoming.imageNames = []
        var added = sample(amount: 10001)
        added.imageNames = []
        let result = try store.importRecords([incoming, added])
        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.skipped, 1)
        let reload = LedgerStore(directory: directory)
        XCTAssertEqual(reload.records.count, 2)
        XCTAssertEqual(reload.records.first { $0.id == existing.id }?.amountMinor, 2850)
        XCTAssertEqual(reload.records.first { $0.id == existing.id }?.imageNames, [savedPhoto])
        XCTAssertTrue(reload.paymentMethods.contains("家庭钱包"))
        let repeatResult = try reload.importRecords([incoming, added])
        XCTAssertEqual(repeatResult.imported, 0)
        XCTAssertEqual(repeatResult.skipped, 2)
    }

    @MainActor
    func testBatchValidationFailureDoesNotSaveAnyRecordsOrMethods() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LedgerStore(directory: directory)
        var valid = sample()
        valid.imageNames = []
        var invalid = sample(amount: 0)
        invalid.imageNames = []
        XCTAssertThrowsError(try store.importRecords([valid, invalid]))
        XCTAssertTrue(store.records.isEmpty)
        XCTAssertFalse(store.paymentMethods.contains("家庭钱包"))
        XCTAssertTrue(LedgerStore(directory: directory).records.isEmpty)
    }

    private func rewriting(_ data: Data, transform: (String, String) -> String) throws -> Data {
        let original = try Archive(data: data, accessMode: .read)
        let updated = try Archive(accessMode: .create)
        for entry in original {
            var contents = Data()
            _ = try original.extract(entry) { contents.append($0) }
            if entry.path.hasSuffix(".xml") || entry.path.hasSuffix(".rels"), let text = String(data: contents, encoding: .utf8) {
                contents = Data(transform(entry.path, text).utf8)
            }
            let copy = contents
            try updated.addEntry(with: entry.path, type: .file, uncompressedSize: Int64(copy.count), compressionMethod: .deflate) { offset, size in
                copy.subdata(in: Int(offset)..<Int(offset) + size)
            }
        }
        return try XCTUnwrap(updated.data)
    }
}
