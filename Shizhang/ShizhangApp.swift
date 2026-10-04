import SwiftUI

@main
struct ShizhangApp: App {
    @StateObject private var store = LedgerStore()
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .tint(AppTheme.accent)
                .preferredColorScheme(.light)
                .environment(\.locale, Locale(identifier: "zh_CN"))
        }
    }
}
