import SwiftUI

enum AppTheme {
    static let background = Color(red: 0.96, green: 0.96, blue: 0.94)
    static let ink = Color(red: 0.12, green: 0.20, blue: 0.22)
    static let accent = Color(red: 0.13, green: 0.49, blue: 0.40)
    static let mint = Color(red: 0.80, green: 0.92, blue: 0.83)
    static let muted = Color(red: 0.44, green: 0.49, blue: 0.48)
    static let expense = Color(red: 0.74, green: 0.38, blue: 0.25)
}

extension View {
    func surface() -> some View {
        padding(20).background(.white, in: RoundedRectangle(cornerRadius: 24))
    }
}

struct CategoryIcon: View {
    let category: RecordCategory
    var body: some View {
        Image(systemName: category.symbol)
            .font(.system(size: 19, weight: .medium))
            .foregroundStyle(AppTheme.accent)
            .frame(width: 46, height: 46)
            .background(AppTheme.mint.opacity(0.45), in: RoundedRectangle(cornerRadius: 15))
    }
}

struct MonthPicker: View {
    @Binding var month: Date
    var body: some View {
        HStack {
            Button { shift(-1) } label: { Image(systemName: "chevron.left").padding(10) }
                .accessibilityLabel("上个月")
            Spacer()
            Text(month.formatted(.dateTime.year().month(.twoDigits))).font(.subheadline.weight(.semibold))
            Spacer()
            Button { shift(1) } label: { Image(systemName: "chevron.right").padding(10) }
                .accessibilityLabel("下个月")
        }
        .foregroundStyle(AppTheme.ink)
    }
    private func shift(_ value: Int) {
        if let next = Calendar.current.date(byAdding: .month, value: value, to: month) { month = next }
    }
}
