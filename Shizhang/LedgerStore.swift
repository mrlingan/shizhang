import Foundation
import SwiftUI

@MainActor
final class LedgerStore: ObservableObject {
    @Published private(set) var snapshot = LedgerSnapshot()
    @Published var loadError: String?
    private let directory: URL
    private let fileURL: URL
    private var canWrite = true

    var records: [LedgerRecord] { snapshot.records }
    var paymentMethods: [String] { snapshot.paymentMethods }

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Shizhang", isDirectory: true)
        fileURL = self.directory.appendingPathComponent("ledger.json")
        do {
            try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                snapshot = try JSONDecoder().decode(LedgerSnapshot.self, from: Data(contentsOf: fileURL))
            }
        } catch {
            canWrite = false
            loadError = "本地账本读取失败，原文件已保留。请检查存储空间或重新启动 App。\n\(error.localizedDescription)"
        }
    }

    func records(in month: Date) -> [LedgerRecord] {
        records.filter { Calendar.current.isDate($0.date, equalTo: month, toGranularity: .month) }
            .sorted { $0.date > $1.date }
    }

    func total(_ kind: RecordKind, in month: Date) -> Int64 {
        records(in: month).filter { $0.kind == kind }.reduce(0) { $0 + $1.amountMinor }
    }

    func imageURL(_ name: String) -> URL { directory.appendingPathComponent(name) }

    func save(_ record: LedgerRecord, newImages: [Data]) throws {
        guard record.amountMinor > 0, record.amountMinor <= 99_999_999_999,
              paymentMethods.contains(record.paymentMethod),
              RecordCategory.options(for: record.kind).contains(record.category),
              record.imageNames.count + newImages.count <= 3 else {
            throw StoreError.invalidRecord
        }
        var updated = record
        var writtenNames: [String] = []
        do {
            for data in newImages {
                let name = "\(UUID().uuidString).jpg"
                try data.write(to: imageURL(name), options: .atomic)
                writtenNames.append(name)
            }
            updated.imageNames += writtenNames
            var candidate = snapshot
            let previous = candidate.records.first { $0.id == updated.id }
            candidate.records.removeAll { $0.id == updated.id }
            candidate.records.append(updated)
            try commit(candidate)
            for oldName in previous?.imageNames ?? [] where !updated.imageNames.contains(oldName) {
                try? FileManager.default.removeItem(at: imageURL(oldName))
            }
        } catch {
            for name in writtenNames { try? FileManager.default.removeItem(at: imageURL(name)) }
            throw error
        }
    }

    func delete(_ record: LedgerRecord) throws {
        var candidate = snapshot
        candidate.records.removeAll { $0.id == record.id }
        try commit(candidate)
        for name in record.imageNames { try? FileManager.default.removeItem(at: imageURL(name)) }
    }

    func addPaymentMethod(_ name: String) throws {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 20 else { throw StoreError.invalidMethod }
        guard !paymentMethods.contains(where: { $0.localizedCaseInsensitiveCompare(trimmed) == .orderedSame }) else {
            throw StoreError.duplicateMethod
        }
        var candidate = snapshot
        candidate.paymentMethods.append(trimmed)
        try commit(candidate)
    }

    func deletePaymentMethod(_ name: String) throws {
        guard paymentMethods.count > 1 else { throw StoreError.lastMethod }
        guard !records.contains(where: { $0.paymentMethod == name }) else { throw StoreError.methodInUse }
        var candidate = snapshot
        candidate.paymentMethods.removeAll { $0 == name }
        try commit(candidate)
    }

    /// Append a validated batch in one atomic write; never overwrite existing records.
    @discardableResult
    func importRecords(_ incoming: [LedgerRecord]) throws -> LedgerImportResult {
        var candidate = snapshot
        var knownIDs = Set(candidate.records.map(\.id))
        var imported = 0
        var skipped = 0
        for var record in incoming {
            guard record.amountMinor > 0, record.amountMinor <= 99_999_999_999,
                  record.date.timeIntervalSinceReferenceDate.isFinite,
                  RecordCategory.options(for: record.kind).contains(record.category),
                  record.imageNames.isEmpty else { throw StoreError.invalidRecord }
            let name = record.paymentMethod.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, name.count <= 20 else { throw StoreError.invalidMethod }
            guard knownIDs.insert(record.id).inserted else { skipped += 1; continue }
            if let existing = candidate.paymentMethods.first(where: { $0.localizedCaseInsensitiveCompare(name) == .orderedSame }) {
                record.paymentMethod = existing
            } else {
                record.paymentMethod = name
                candidate.paymentMethods.append(name)
            }
            candidate.records.append(record)
            imported += 1
        }
        if imported > 0 { try commit(candidate) }
        return LedgerImportResult(imported: imported, skipped: skipped)
    }

    private func commit(_ candidate: LedgerSnapshot) throws {
        guard canWrite else { throw StoreError.unreadableLedger }
        try JSONEncoder().encode(candidate).write(to: fileURL, options: .atomic)
        snapshot = candidate
    }
}

struct LedgerImportResult {
    let imported: Int
    let skipped: Int
}

enum StoreError: LocalizedError {
    case invalidRecord, invalidMethod, duplicateMethod, lastMethod, methodInUse, unreadableLedger
    var errorDescription: String? {
        switch self {
        case .invalidRecord: "请检查金额、分类、付款方式及图片数量。"
        case .invalidMethod: "付款方式名称需为 1–20 个字。"
        case .duplicateMethod: "这个付款方式已经存在。"
        case .lastMethod: "请至少保留一种付款方式。"
        case .methodInUse: "已有账单使用此付款方式，请先修改相关账单。"
        case .unreadableLedger: "原账本无法读取，暂时不能保存，避免覆盖已有数据。"
        }
    }
}
