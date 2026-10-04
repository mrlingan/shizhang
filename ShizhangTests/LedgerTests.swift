import XCTest
@testable import Shizhang

final class LedgerTests: XCTestCase {
    func testAmountsAreExactAndRejectInvalidInput() {
        XCTAssertEqual(Money.parse("0.01"), 1)
        XCTAssertEqual(Money.parse("12.3"), 1230)
        XCTAssertEqual(Money.parse(" 999999999.99 "), 99_999_999_999)
        XCTAssertEqual(Money.parse("00012.34"), 1234)
        for invalid in ["", "0", "0.00", "-1", "1.001", "1,20", "1e2", ".", "1.2.3", "1000000000", "１２"] {
            XCTAssertNil(Money.parse(invalid), "Should reject \(invalid)")
        }
        XCTAssertEqual(Money.input(1234), "12.34")
        XCTAssertEqual(Money.formatted(-1234), "-12.34")
    }

    @MainActor
    func testRecordsAndAttachmentsSurviveReloadEditAndDelete() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LedgerStore(directory: directory)
        try store.addPaymentMethod(" 家庭钱包 ")
        XCTAssertEqual(store.paymentMethods.last, "家庭钱包")
        XCTAssertThrowsError(try store.addPaymentMethod("家庭钱包"))
        let now = Date()
        var record = LedgerRecord(kind: .expense, amountMinor: 1234, category: .food, paymentMethod: "家庭钱包", date: now, note: "午餐", imageNames: [])
        let attachment = Data([0xFF, 0xD8, 0xFF, 0xD9])
        try store.save(record, newImages: [attachment])
        let reload = LedgerStore(directory: directory)
        record = try XCTUnwrap(reload.records.first)
        let filename = try XCTUnwrap(record.imageNames.first)
        XCTAssertEqual(try Data(contentsOf: reload.imageURL(filename)), attachment)
        XCTAssertEqual(reload.total(.expense, in: now), 1234)
        XCTAssertThrowsError(try reload.deletePaymentMethod("家庭钱包"))
        record.amountMinor = 5678
        record.imageNames = []
        try reload.save(record, newImages: [])
        XCTAssertEqual(reload.records.count, 1)
        XCTAssertEqual(reload.total(.expense, in: now), 5678)
        XCTAssertFalse(FileManager.default.fileExists(atPath: reload.imageURL(filename).path))
        try reload.delete(record)
        try reload.deletePaymentMethod("家庭钱包")
        let final = LedgerStore(directory: directory)
        XCTAssertTrue(final.records.isEmpty)
        XCTAssertFalse(final.paymentMethods.contains("家庭钱包"))
    }

    @MainActor
    func testMonthlyTotalsAndIncomeAreSeparate() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LedgerStore(directory: directory)
        let date = try XCTUnwrap(Calendar.current.date(from: DateComponents(year: 2026, month: 10, day: 4)))
        let previous = try XCTUnwrap(Calendar.current.date(byAdding: .month, value: -1, to: date))
        for (kind, amount, day, category) in [(RecordKind.expense, Int64(10), date, RecordCategory.food), (.expense, 20, date, .shopping), (.income, 1000, date, .salary), (.expense, 900, previous, .food)] {
            try store.save(LedgerRecord(kind: kind, amountMinor: amount, category: category, paymentMethod: "现金", date: day, note: "", imageNames: []), newImages: [])
        }
        XCTAssertEqual(store.total(.expense, in: date), 30)
        XCTAssertEqual(store.total(.income, in: date), 1000)
        XCTAssertEqual(store.records(in: date).count, 3)
        XCTAssertEqual(store.total(.expense, in: previous), 900)
    }

    @MainActor
    func testCorruptLedgerIsNotOverwritten() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("ledger.json")
        let original = Data("corrupt-data".utf8)
        try original.write(to: file)
        let store = LedgerStore(directory: directory)
        XCTAssertNotNil(store.loadError)
        XCTAssertThrowsError(try store.addPaymentMethod("钱包"))
        XCTAssertEqual(try Data(contentsOf: file), original)
    }
}
