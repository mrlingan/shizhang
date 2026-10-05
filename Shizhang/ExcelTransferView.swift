import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let ledgerExcel = UTType(importedAs: "org.openxmlformats.spreadsheetml.sheet", conformingTo: .data)
}

struct ExcelDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.ledgerExcel] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else { throw ExcelError.message("无法读取 Excel 文件。") }
        data = contents
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct ExcelTransferView: View {
    @EnvironmentObject private var store: LedgerStore
    @State private var month = Date()
    @State private var monthly = false
    @State private var busy = false
    @State private var importing = false
    @State private var exporting = false
    @State private var document: ExcelDocument?
    @State private var exportName = "拾账账单"
    @State private var preview: ExcelImportBatch?
    @State private var error: String?
    @State private var result: String?

    private var exportRecords: [LedgerRecord] { monthly ? store.records(in: month) : store.records }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Image(systemName: "tablecells.fill").font(.system(size: 36)).foregroundStyle(AppTheme.accent)
                    Text("账单，也能在 Excel 里整理").font(.title2.bold())
                    Text("导出为 .xlsx，或把整理好的表格导回账本。")
                        .font(.subheadline).foregroundStyle(AppTheme.muted)
                    VStack(alignment: .leading, spacing: 16) {
                        Label("导出账单", systemImage: "square.and.arrow.up").font(.headline)
                        Picker("导出范围", selection: $monthly) {
                            Text("全部账单").tag(false)
                            Text("按月份").tag(true)
                        }.pickerStyle(.segmented).accessibilityIdentifier("excelExportScope")
                        if monthly { MonthPicker(month: $month) }
                        HStack {
                            Text("当前范围").font(.subheadline).foregroundStyle(AppTheme.muted)
                            Spacer()
                            Text("\(exportRecords.count) 笔账单").font(.subheadline.weight(.semibold))
                        }
                        Button { exportLedger() } label: {
                            Label("导出 Excel", systemImage: "arrow.up.doc").font(.headline).frame(maxWidth: .infinity).padding(12)
                        }.buttonStyle(.borderedProminent).disabled(busy || exportRecords.isEmpty)
                            .accessibilityIdentifier("exportExcel")
                        Text("保存到“文件”，也可通过系统分享发送。表格含金额、日期、分类、付款方式、备注、图片数量及账单 ID，不包含图片文件。")
                            .font(.caption).foregroundStyle(AppTheme.muted).lineSpacing(3)
                    }.surface()
                    VStack(alignment: .leading, spacing: 16) {
                        Label("导入账单", systemImage: "square.and.arrow.down").font(.headline)
                        Text("先下载模板填写，或使用从拾账导出的表格。新付款方式会自动加入账本。")
                            .font(.subheadline).foregroundStyle(AppTheme.muted).lineSpacing(3)
                        Button { exportTemplate() } label: {
                            Label("下载 Excel 导入模板", systemImage: "doc.badge.arrow.down").frame(maxWidth: .infinity).padding(12)
                        }.buttonStyle(.bordered).disabled(busy).accessibilityIdentifier("exportExcelTemplate")
                        Button { importing = true } label: {
                            Label("选择 Excel 文件", systemImage: "folder").frame(maxWidth: .infinity).padding(12)
                        }.buttonStyle(.borderedProminent).disabled(busy).accessibilityIdentifier("importExcel")
                        Text("导入前会预览，错误行修正后才可保存。已有账单 ID 会跳过，不覆盖原账单；没有 ID 的每一行会作为新账单添加。")
                            .font(.caption).foregroundStyle(AppTheme.muted).lineSpacing(3)
                    }.surface()
                    if busy { ProgressView("正在处理 Excel…").frame(maxWidth: .infinity) }
                    Label("支持 .xlsx，每次最多 10,000 笔、文件最多 20 MB。", systemImage: "info.circle")
                        .font(.caption).foregroundStyle(AppTheme.muted)
                }.padding(22)
            }.background(AppTheme.background).foregroundStyle(AppTheme.ink)
                .navigationTitle("导入与导出").navigationBarTitleDisplayMode(.inline)
                .fileExporter(isPresented: $exporting, document: document, contentType: .ledgerExcel, defaultFilename: exportName) { response in
                    if case .failure(let failure) = response, !isCancellation(failure) { error = failure.localizedDescription }
                }
                .fileImporter(isPresented: $importing, allowedContentTypes: [.ledgerExcel], allowsMultipleSelection: false) { response in
                    switch response {
                    case .success(let urls): if let url = urls.first { importLedger(url) }
                    case .failure(let failure): if !isCancellation(failure) { error = failure.localizedDescription }
                    }
                }
                .sheet(item: $preview) { batch in
                    ExcelImportPreviewView(batch: batch) { summary in
                        result = "已导入 \(summary.imported) 笔账单，跳过 \(summary.skipped) 笔已有账单。"
                    }
                }
                .alert("Excel 操作失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                    Button("知道了", role: .cancel) { error = nil }
                } message: { Text(error ?? "") }
                .alert("导入完成", isPresented: Binding(get: { result != nil }, set: { if !$0 { result = nil } })) {
                    Button("完成", role: .cancel) { result = nil }
                } message: { Text(result ?? "") }
        }
    }

    private func exportLedger() {
        let records = exportRecords
        exportName = monthly ? "拾账账单-\(month.formatted(.dateTime.year().month(.twoDigits)))" : "拾账全部账单"
        busy = true
        Task {
            defer { busy = false }
            do {
                let data = try await Task.detached(priority: .userInitiated) { try ExcelWorkbook.export(records) }.value
                document = ExcelDocument(data: data)
                exporting = true
            } catch { self.error = error.localizedDescription }
        }
    }

    private func exportTemplate() {
        do {
            guard let url = Bundle.main.url(forResource: "账单导入模板", withExtension: "xlsx") else { throw ExcelError.message("未找到导入模板，请重新安装 App。") }
            document = ExcelDocument(data: try Data(contentsOf: url))
            exportName = "拾账导入模板"
            exporting = true
        } catch { self.error = error.localizedDescription }
    }

    private func importLedger(_ url: URL) {
        busy = true
        Task {
            defer { busy = false }
            do {
                preview = try await Task.detached(priority: .userInitiated) {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    var coordinatorError: NSError?
                    var readResult: Result<Data, Error>?
                    NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinatorError) { readableURL in
                        readResult = Result {
                            let size = try readableURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                            guard size <= ExcelWorkbook.maximumFileBytes else { throw ExcelError.message("文件超过 20 MB，请拆分后导入。") }
                            return try Data(contentsOf: readableURL)
                        }
                    }
                    if let coordinatorError { throw coordinatorError }
                    guard let readResult else { throw ExcelError.message("无法读取所选文件。") }
                    return try ExcelWorkbook.read(readResult.get(), filename: url.lastPathComponent)
                }.value
            } catch { if !isCancellation(error) { self.error = error.localizedDescription } }
        }
    }

    private func isCancellation(_ error: Error) -> Bool { (error as NSError).domain == NSCocoaErrorDomain && (error as NSError).code == NSUserCancelledError }
}

private struct ExcelImportPreviewView: View {
    @EnvironmentObject private var store: LedgerStore
    @Environment(\.dismiss) private var dismiss
    let batch: ExcelImportBatch
    let completed: (LedgerImportResult) -> Void
    @State private var error: String?

    private var incoming: [LedgerRecord] {
        let existing = Set(store.records.map(\.id))
        return batch.records.filter { !existing.contains($0.id) }
    }
    private var methods: [String] {
        Set(incoming.map(\.paymentMethod).filter { name in !store.paymentMethods.contains { $0.localizedCaseInsensitiveCompare(name) == .orderedSame } }).sorted()
    }
    var body: some View {
        NavigationStack {
            List {
                Section("导入预览") {
                    Text(batch.filename).font(.subheadline).foregroundStyle(AppTheme.muted)
                    LabeledContent("新增账单", value: "\(incoming.count) 笔")
                    LabeledContent("已有账单，将跳过", value: "\(batch.records.count - incoming.count) 笔")
                    LabeledContent("错误行", value: "\(batch.issues.count) 行")
                    if !methods.isEmpty { LabeledContent("新增付款方式", value: methods.joined(separator: "、")) }
                }
                if !batch.issues.isEmpty {
                    Section("请在 Excel 中修正以下行后重新选择文件") {
                        ForEach(batch.issues.prefix(100)) { issue in
                            VStack(alignment: .leading, spacing: 5) {
                                Text("第 \(issue.row) 行").font(.subheadline.bold()).foregroundStyle(AppTheme.expense)
                                Text(issue.message).font(.caption)
                            }
                        }
                        if batch.issues.count > 100 { Text("还有 \(batch.issues.count - 100) 行错误，请分批处理。").font(.caption) }
                    }
                }
                if batch.rowsWithImages > 0 {
                    Section {
                        Text("\(batch.rowsWithImages) 笔记录标记有图片。Excel 不包含图片文件，新导入的账单没有图片附件；已有账单的图片保持原样。")
                            .font(.caption).foregroundStyle(AppTheme.muted)
                    }
                }
                Section("账单示例（最多展示 10 笔）") {
                    ForEach(incoming.prefix(10)) { record in
                        VStack(alignment: .leading, spacing: 6) {
                            RecordRow(record: record)
                            Text("\(record.date.formatted(.dateTime.year().month().day())) · \(record.category.title)")
                                .font(.caption).foregroundStyle(AppTheme.muted)
                        }
                    }
                    if incoming.isEmpty { Text("没有可新增的账单。").foregroundStyle(AppTheme.muted) }
                }
            }.scrollContentBackground(.hidden).background(AppTheme.background)
                .navigationTitle("确认导入").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
                .safeAreaInset(edge: .bottom) {
                    Button {
                        do {
                            let summary = try store.importRecords(batch.records)
                            dismiss()
                            completed(summary)
                        } catch { self.error = error.localizedDescription }
                    } label: {
                        Text("导入 \(incoming.count) 笔账单").font(.headline).frame(maxWidth: .infinity).padding(15)
                    }.buttonStyle(.borderedProminent).disabled(incoming.isEmpty || !batch.issues.isEmpty)
                        .accessibilityIdentifier("confirmExcelImport").padding(18).background(AppTheme.background)
                }
                .alert("导入失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                    Button("知道了", role: .cancel) { error = nil }
                } message: { Text(error ?? "") }
        }
    }
}
