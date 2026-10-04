import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: LedgerStore
    var body: some View {
        TabView {
            LedgerView().tabItem { Label("账本", systemImage: "square.stack.3d.up") }
            ReportsView().tabItem { Label("统计", systemImage: "chart.bar.xaxis") }
            PaymentMethodsView().tabItem { Label("付款方式", systemImage: "creditcard") }
        }
        .alert("无法读取账本", isPresented: Binding(get: { store.loadError != nil }, set: { if !$0 { store.loadError = nil } })) {
            Button("知道了", role: .cancel) { store.loadError = nil }
        } message: { Text(store.loadError ?? "") }
    }
}

struct LedgerView: View {
    @EnvironmentObject private var store: LedgerStore
    @State private var month = Date()
    @State private var filter: RecordKind?
    @State private var search = ""
    @State private var adding = false
    @State private var editing: LedgerRecord?
    @State private var pendingDelete: LedgerRecord?
    @State private var error: String?

    private var filtered: [LedgerRecord] {
        store.records(in: month).filter {
            (filter == nil || $0.kind == filter) && (search.isEmpty ||
                "\($0.note) \($0.category.title) \($0.paymentMethod)".localizedCaseInsensitiveContains(search))
        }
    }
    private var days: [Date] {
        Set(filtered.map { Calendar.current.startOfDay(for: $0.date) }).sorted(by: >)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("拾账").font(.system(size: 34, weight: .bold, design: .rounded))
                            Text("把每一笔生活，好好记下。").font(.subheadline).foregroundStyle(AppTheme.muted)
                        }
                        Spacer()
                        Image(systemName: "leaf.fill").font(.system(size: 24))
                            .foregroundStyle(AppTheme.accent).padding(14)
                            .background(AppTheme.mint, in: Circle())
                    }
                    MonthPicker(month: $month)
                    summary
                    HStack {
                        Text("账单明细").font(.title3.bold())
                        Spacer()
                        Text("\(filtered.count) 笔").font(.subheadline).foregroundStyle(AppTheme.muted)
                    }
                    HStack(spacing: 8) {
                        filterButton("全部", kind: nil)
                        filterButton("支出", kind: .expense)
                        filterButton("收入", kind: .income)
                        Spacer()
                    }
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(AppTheme.muted)
                        TextField("搜索备注、分类或付款方式", text: $search).font(.subheadline)
                            .accessibilityIdentifier("ledgerSearch")
                        if !search.isEmpty {
                            Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                                .accessibilityLabel("清除搜索")
                        }
                    }.padding(14).background(.white, in: RoundedRectangle(cornerRadius: 16))
                    if filtered.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: search.isEmpty ? "book.closed" : "magnifyingglass")
                                .font(.system(size: 34)).foregroundStyle(AppTheme.accent).padding(.bottom, 4)
                            Text(search.isEmpty ? "这一页，等你开始" : "没有找到相关账单").font(.headline)
                            Text(search.isEmpty ? "记录一笔花费，让生活的去向更清晰。" : "试试其他关键词或筛选条件。")
                                .font(.subheadline).foregroundStyle(AppTheme.muted).multilineTextAlignment(.center)
                        }.frame(maxWidth: .infinity).padding(.vertical, 35).surface()
                    } else {
                        ForEach(days, id: \.self) { day in
                            VStack(alignment: .leading, spacing: 12) {
                                Text(day.formatted(.dateTime.month().day().weekday(.wide)))
                                    .font(.caption.weight(.semibold)).foregroundStyle(AppTheme.muted)
                                VStack(spacing: 0) {
                                    let rows = filtered.filter { Calendar.current.isDate($0.date, inSameDayAs: day) }
                                    ForEach(rows) { record in
                                        Button { editing = record } label: { RecordRow(record: record) }
                                            .buttonStyle(.plain)
                                            .contextMenu {
                                                Button("编辑账单", systemImage: "pencil") { editing = record }
                                                Button("删除账单", systemImage: "trash", role: .destructive) { pendingDelete = record }
                                            }
                                        if record.id != rows.last?.id { Divider().padding(.leading, 62) }
                                    }
                                }.padding(.horizontal, 16).background(.white, in: RoundedRectangle(cornerRadius: 22))
                            }
                        }
                    }
                }.padding(22)
            }
            .background(AppTheme.background)
            .foregroundStyle(AppTheme.ink)
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .bottom) {
                Button { adding = true } label: {
                    Label("记一笔", systemImage: "plus").font(.headline)
                        .frame(maxWidth: .infinity).padding(17)
                        .foregroundStyle(.white).background(AppTheme.accent, in: Capsule())
                }.accessibilityIdentifier("addRecord")
                    .padding(.horizontal, 22).padding(.top, 10).padding(.bottom, 10)
                    .background(AppTheme.background.opacity(0.96))
            }
            .sheet(isPresented: $adding) { RecordEditor() }
            .sheet(item: $editing) { RecordEditor(record: $0) }
            .confirmationDialog("删除这笔账单及其图片？", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }), titleVisibility: .visible) {
                Button("删除账单", role: .destructive) {
                    if let record = pendingDelete { do { try store.delete(record) } catch { self.error = error.localizedDescription } }
                    pendingDelete = nil
                }
            }
            .alert("操作失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("知道了", role: .cancel) { error = nil }
            } message: { Text(error ?? "") }
        }
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack {
                Text("本月支出").font(.subheadline).foregroundStyle(.white.opacity(0.72))
                Spacer()
                Text("CNY / 人民币").font(.caption2.weight(.medium)).foregroundStyle(AppTheme.mint)
            }
            Text("¥ \(Money.formatted(store.total(.expense, in: month)))")
                .font(.system(size: 38, weight: .semibold, design: .rounded)).minimumScaleFactor(0.5).lineLimit(1)
            HStack {
                VStack(alignment: .leading, spacing: 8) {
                    Label("收入", systemImage: "arrow.down.left").font(.caption).foregroundStyle(AppTheme.mint)
                    Text("¥ \(Money.formatted(store.total(.income, in: month)))").font(.subheadline.weight(.semibold))
                }
                Spacer()
                Rectangle().fill(.white.opacity(0.15)).frame(width: 1, height: 34)
                Spacer()
                VStack(alignment: .leading, spacing: 8) {
                    Text("结余").font(.caption).foregroundStyle(.white.opacity(0.72))
                    Text("¥ \(Money.formatted(store.total(.income, in: month) - store.total(.expense, in: month)))")
                        .font(.subheadline.weight(.semibold))
                }
                Spacer(minLength: 0)
            }
        }.foregroundStyle(.white).padding(25)
            .background(LinearGradient(colors: [AppTheme.ink, Color(red: 0.16, green: 0.33, blue: 0.31)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 28))
    }

    private func filterButton(_ title: String, kind: RecordKind?) -> some View {
        Button { filter = kind } label: {
            Text(title).font(.subheadline.weight(.medium)).padding(.horizontal, 20).padding(.vertical, 10)
                .foregroundStyle(filter == kind ? .white : AppTheme.muted)
                .background(filter == kind ? AppTheme.ink : .white, in: Capsule())
        }
    }
}

struct RecordRow: View {
    let record: LedgerRecord
    var body: some View {
        HStack(spacing: 12) {
            CategoryIcon(category: record.category)
            VStack(alignment: .leading, spacing: 5) {
                Text(record.note.isEmpty ? record.category.title : record.note).font(.subheadline.weight(.semibold)).lineLimit(1)
                HStack(spacing: 5) {
                    Text(record.paymentMethod)
                    if !record.imageNames.isEmpty { Image(systemName: "photo"); Text("\(record.imageNames.count)") }
                }.font(.caption).foregroundStyle(AppTheme.muted)
            }
            Spacer(minLength: 4)
            Text("\(record.kind == .expense ? "−" : "+")\(Money.formatted(record.amountMinor))")
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(record.kind == .income ? AppTheme.accent : AppTheme.ink).lineLimit(1).minimumScaleFactor(0.7)
        }.padding(.vertical, 16).contentShape(Rectangle())
    }
}

struct ReportsView: View {
    @EnvironmentObject private var store: LedgerStore
    @State private var month = Date()
    @State private var kind: RecordKind = .expense
    private var items: [(category: RecordCategory, total: Int64)] {
        RecordCategory.options(for: kind).map { category in
            (category, store.records(in: month).filter { $0.kind == kind && $0.category == category }.reduce(Int64(0)) { $0 + $1.amountMinor })
        }.filter { $0.1 > 0 }.sorted { $0.1 > $1.1 }
    }
    private var total: Int64 { store.total(kind, in: month) }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("看见生活的去向").font(.title2.bold())
                    Text("每一笔记录，都是更了解自己的开始。").font(.subheadline).foregroundStyle(AppTheme.muted)
                    MonthPicker(month: $month)
                    Picker("收支类型", selection: $kind) {
                        ForEach(RecordKind.allCases) { Text($0.title).tag($0) }
                    }.pickerStyle(.segmented)
                    VStack(alignment: .leading, spacing: 12) {
                        Text("本月总\(kind.title)").font(.subheadline).foregroundStyle(AppTheme.muted)
                        Text("¥ \(Money.formatted(total))").font(.system(size: 34, weight: .semibold, design: .rounded)).lineLimit(1).minimumScaleFactor(0.5)
                        Text("\(store.records(in: month).filter { $0.kind == kind }.count) 笔记录").font(.caption).foregroundStyle(AppTheme.muted)
                    }.frame(maxWidth: .infinity, alignment: .leading).surface()
                    Text("分类分布").font(.title3.bold())
                    if items.isEmpty {
                        ContentUnavailableView("还没有\(kind.title)记录", systemImage: "chart.bar", description: Text("在账本中记一笔，这里就会显示分类统计。"))
                    } else {
                        VStack(spacing: 22) {
                            ForEach(items, id: \.category) { item in
                                VStack(spacing: 10) {
                                    HStack(spacing: 12) {
                                        CategoryIcon(category: item.category)
                                        Text(item.category.title).font(.subheadline.weight(.medium))
                                        Spacer()
                                        VStack(alignment: .trailing, spacing: 4) {
                                            Text("¥ \(Money.formatted(item.total))").font(.subheadline.weight(.semibold))
                                            Text(Double(item.total) / Double(max(total, 1)), format: .percent.precision(.fractionLength(1)))
                                                .font(.caption).foregroundStyle(AppTheme.muted)
                                        }
                                    }
                                    GeometryReader { geometry in
                                        Capsule().fill(AppTheme.mint.opacity(0.5))
                                        Capsule().fill(AppTheme.accent).frame(width: geometry.size.width * CGFloat(item.total) / CGFloat(max(total, 1)))
                                    }.frame(height: 7)
                                }
                            }
                        }.surface()
                    }
                }.padding(22)
            }.background(AppTheme.background).foregroundStyle(AppTheme.ink)
                .navigationTitle("月度统计").navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct PaymentMethodsView: View {
    @EnvironmentObject private var store: LedgerStore
    @State private var name = ""
    @State private var error: String?
    @State private var deleting: String?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Image(systemName: "creditcard.fill").font(.system(size: 36)).foregroundStyle(AppTheme.accent)
                    Text("用你习惯的方式").font(.title2.bold())
                    Text("微信、现金，或你自己的小钱包。\n付款方式可以自由添加，记账时直接选择。")
                        .font(.subheadline).foregroundStyle(AppTheme.muted).lineSpacing(5)
                    VStack(spacing: 0) {
                        ForEach(store.paymentMethods, id: \.self) { method in
                            HStack {
                                Image(systemName: "creditcard").foregroundStyle(AppTheme.accent)
                                Text(method).font(.subheadline.weight(.medium))
                                Spacer()
                                Button(role: .destructive) { deleting = method } label: {
                                    Image(systemName: "trash").foregroundStyle(AppTheme.muted).padding(8)
                                }.accessibilityLabel("删除\(method)")
                            }.padding(.vertical, 12)
                            if method != store.paymentMethods.last { Divider() }
                        }
                    }.surface()
                    VStack(alignment: .leading, spacing: 14) {
                        Text("添加付款方式").font(.headline)
                        TextField("例如：招商信用卡 / 零钱包", text: $name)
                            .textInputAutocapitalization(.never).padding(14)
                            .background(AppTheme.background, in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityIdentifier("newPaymentMethod")
                        Button {
                            do { try store.addPaymentMethod(name); name = "" } catch { self.error = error.localizedDescription }
                        } label: {
                            Label("添加", systemImage: "plus").font(.headline).frame(maxWidth: .infinity).padding(14)
                        }.buttonStyle(.borderedProminent).disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("savePaymentMethod")
                    }.surface()
                    Label("账单与图片保存在本机，卸载 App 会移除数据。", systemImage: "internaldrive")
                        .font(.caption).foregroundStyle(AppTheme.muted)
                }.padding(22)
            }.background(AppTheme.background).foregroundStyle(AppTheme.ink)
                .navigationTitle("付款方式").navigationBarTitleDisplayMode(.inline)
                .confirmationDialog("删除“\(deleting ?? "")”？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                    Button("删除付款方式", role: .destructive) {
                        if let deleting { do { try store.deletePaymentMethod(deleting) } catch { self.error = error.localizedDescription } }
                        deleting = nil
                    }
                }
                .alert("操作失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                    Button("知道了", role: .cancel) { error = nil }
                } message: { Text(error ?? "") }
        }
    }
}
