import Foundation

enum RecordKind: String, Codable, CaseIterable, Identifiable {
    case expense, income
    var id: String { rawValue }
    var title: String { self == .expense ? "支出" : "收入" }
}

enum RecordCategory: String, Codable, CaseIterable, Identifiable {
    case food, shopping, transport, home, entertainment, health, other
    case salary, bonus, gift, refund
    var id: String { rawValue }
    var title: String {
        switch self {
        case .food: "餐饮"
        case .shopping: "购物"
        case .transport: "交通"
        case .home: "居家"
        case .entertainment: "娱乐"
        case .health: "医疗"
        case .other: "其他"
        case .salary: "工资"
        case .bonus: "奖金"
        case .gift: "礼金"
        case .refund: "退款"
        }
    }
    var symbol: String {
        switch self {
        case .food: "fork.knife"
        case .shopping: "bag"
        case .transport: "tram"
        case .home: "house"
        case .entertainment: "gamecontroller"
        case .health: "cross.case"
        case .other: "ellipsis"
        case .salary: "briefcase"
        case .bonus: "star"
        case .gift: "gift"
        case .refund: "arrow.uturn.backward"
        }
    }
    static func options(for kind: RecordKind) -> [Self] {
        kind == .expense ? [.food, .shopping, .transport, .home, .entertainment, .health, .other] : [.salary, .bonus, .gift, .refund, .other]
    }
}

struct LedgerRecord: Identifiable, Codable, Equatable {
    var id = UUID()
    var kind: RecordKind
    var amountMinor: Int64
    var category: RecordCategory
    var paymentMethod: String
    var date: Date
    var note: String
    var imageNames: [String]
}

struct LedgerSnapshot: Codable {
    var records: [LedgerRecord] = []
    var paymentMethods: [String] = ["微信支付", "支付宝", "银行卡", "现金"]
}

enum Money {
    /// Parse decimal text directly into cents, avoiding floating-point rounding.
    static func parse(_ text: String) -> Int64? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2, !parts[0].isEmpty,
              parts[0].count <= 9,
              parts.allSatisfy({ $0.allSatisfy({ $0.isASCII && $0.isNumber }) }),
              let whole = Int64(parts[0]) else { return nil }
        let fraction = parts.count == 2 ? String(parts[1]) : ""
        guard fraction.count <= 2 else { return nil }
        let cents = Int64(fraction.padding(toLength: 2, withPad: "0", startingAt: 0)) ?? 0
        let result = whole * 100 + cents
        return result > 0 ? result : nil
    }

    static func input(_ minor: Int64) -> String {
        "\(minor / 100).\(String(format: "%02lld", minor % 100))"
    }

    static func formatted(_ minor: Int64) -> String {
        let number = NSDecimalNumber(value: minor).dividing(by: 100)
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        return formatter.string(from: number) ?? "0.00"
    }
}
