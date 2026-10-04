import SwiftUI
import PhotosUI
import UIKit

struct RecordEditor: View {
    @EnvironmentObject private var store: LedgerStore
    @Environment(\.dismiss) private var dismiss
    private let original: LedgerRecord?
    @State private var kind: RecordKind
    @State private var amount: String
    @State private var category: RecordCategory
    @State private var paymentMethod: String
    @State private var date: Date
    @State private var note: String
    @State private var retainedNames: [String]
    @State private var newImages: [Data] = []
    @State private var selection: [PhotosPickerItem] = []
    @State private var loadingImages = false
    @State private var imageTask: Task<Void, Never>?
    @State private var error: String?
    @State private var addingMethod = false
    @State private var newMethod = ""
    @State private var imagePreview: ImagePreview?
    @State private var confirmDelete = false
    @FocusState private var amountFocused: Bool

    init(record: LedgerRecord? = nil) {
        original = record
        _kind = State(initialValue: record?.kind ?? .expense)
        _amount = State(initialValue: record.map { Money.input($0.amountMinor) } ?? "")
        _category = State(initialValue: record?.category ?? .food)
        _paymentMethod = State(initialValue: record?.paymentMethod ?? "")
        _date = State(initialValue: record?.date ?? Date())
        _note = State(initialValue: record?.note ?? "")
        _retainedNames = State(initialValue: record?.imageNames ?? [])
    }

    private var imageCount: Int { retainedNames.count + newImages.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Picker("收支类型", selection: $kind) {
                        ForEach(RecordKind.allCases) { Text($0.title).tag($0) }
                    }.pickerStyle(.segmented)
                    VStack(alignment: .leading, spacing: 12) {
                        Text("\(kind.title)金额").font(.subheadline).foregroundStyle(AppTheme.muted)
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Text("¥").font(.system(size: 28, weight: .medium)).foregroundStyle(AppTheme.accent)
                            TextField("0.00", text: $amount).font(.system(size: 42, weight: .semibold, design: .rounded))
                                .keyboardType(.decimalPad).focused($amountFocused).accessibilityIdentifier("recordAmount")
                        }
                        if !amount.isEmpty && Money.parse(amount) == nil {
                            Text("请输入大于 0 的金额，最多 9 位整数、2 位小数。")
                                .font(.caption).foregroundStyle(AppTheme.expense)
                        } else {
                            Text("人民币 · 精确到分").font(.caption).foregroundStyle(AppTheme.muted)
                        }
                    }.surface()
                    VStack(alignment: .leading, spacing: 16) {
                        Text("分类").font(.headline)
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 68))], spacing: 14) {
                            ForEach(RecordCategory.options(for: kind)) { item in
                                Button { category = item; amountFocused = false } label: {
                                    VStack(spacing: 8) {
                                        Image(systemName: item.symbol).font(.system(size: 20))
                                            .frame(width: 48, height: 48)
                                            .background(category == item ? AppTheme.accent : AppTheme.background, in: RoundedRectangle(cornerRadius: 16))
                                            .foregroundStyle(category == item ? .white : AppTheme.accent)
                                        Text(item.title).font(.caption).foregroundStyle(AppTheme.ink)
                                    }.frame(maxWidth: .infinity)
                                }.buttonStyle(.plain).accessibilityAddTraits(category == item ? .isSelected : [])
                            }
                        }
                    }.surface()
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Text("付款方式").font(.headline)
                            Spacer()
                            Button { addingMethod = true } label: { Label("自定义", systemImage: "plus").font(.caption.weight(.semibold)) }
                                .accessibilityIdentifier("customPaymentMethod")
                        }
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 108))], spacing: 10) {
                            ForEach(store.paymentMethods, id: \.self) { method in
                                Button { paymentMethod = method; amountFocused = false } label: {
                                    Text(method).font(.subheadline).lineLimit(2).frame(maxWidth: .infinity, minHeight: 22).padding(12)
                                        .foregroundStyle(paymentMethod == method ? AppTheme.accent : AppTheme.muted)
                                        .background(paymentMethod == method ? AppTheme.mint.opacity(0.5) : AppTheme.background, in: RoundedRectangle(cornerRadius: 12))
                                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(paymentMethod == method ? AppTheme.accent : .clear, lineWidth: 1))
                                }.buttonStyle(.plain).accessibilityAddTraits(paymentMethod == method ? .isSelected : [])
                            }
                        }
                    }.surface()
                    VStack(alignment: .leading, spacing: 18) {
                        DatePicker("日期", selection: $date, displayedComponents: .date).font(.subheadline)
                        Divider()
                        TextField("写点备注，例如：和朋友吃晚饭", text: $note, axis: .vertical)
                            .font(.subheadline).lineLimit(2...4).accessibilityIdentifier("recordNote")
                    }.surface()
                    VStack(alignment: .leading, spacing: 15) {
                        HStack {
                            Text("账单图片").font(.headline)
                            Spacer()
                            Text("\(imageCount) / 3").font(.caption).foregroundStyle(AppTheme.muted)
                        }
                        Text("添加小票、支付截图或照片，轻点可查看大图。")
                            .font(.caption).foregroundStyle(AppTheme.muted)
                        if imageCount > 0 {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 14) {
                                    ForEach(retainedNames, id: \.self) { name in
                                        if let image = UIImage(contentsOfFile: store.imageURL(name).path) {
                                            thumbnail(image) { retainedNames.removeAll { $0 == name } }
                                        } else {
                                            VStack {
                                                Image(systemName: "photo.badge.exclamationmark")
                                                Button("移除丢失图片") { retainedNames.removeAll { $0 == name } }
                                            }.font(.caption).frame(width: 110, height: 110)
                                        }
                                    }
                                    ForEach(newImages.indices, id: \.self) { index in
                                        if let image = UIImage(data: newImages[index]) {
                                            thumbnail(image) { newImages.remove(at: index) }
                                        }
                                    }
                                }.padding(.top, 8).padding(.trailing, 8)
                            }
                        }
                        if loadingImages {
                            ProgressView("正在导入图片…").font(.subheadline)
                        } else if imageCount < 3 {
                            PhotosPicker(selection: $selection, maxSelectionCount: 3 - imageCount, matching: .images) {
                                Label("从相册添加", systemImage: "photo.badge.plus").font(.subheadline.weight(.medium))
                                    .frame(maxWidth: .infinity).padding(20)
                                    .background(AppTheme.background, in: RoundedRectangle(cornerRadius: 14))
                                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(AppTheme.accent.opacity(0.3), style: StrokeStyle(lineWidth: 1, dash: [5])))
                            }.accessibilityIdentifier("addImages")
                        }
                        Text("图片仅保存在本机，不会上传至服务器。")
                            .font(.caption2).foregroundStyle(AppTheme.muted)
                    }.surface()
                    if original != nil {
                        Button("删除这笔账单", role: .destructive) { confirmDelete = true }
                            .font(.subheadline).frame(maxWidth: .infinity).padding(10)
                    }
                }.padding(20)
            }.background(AppTheme.background).foregroundStyle(AppTheme.ink)
                .scrollDismissesKeyboard(.interactively)
                .navigationTitle(original == nil ? "记一笔" : "编辑账单").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("完成") { amountFocused = false } }
                }
                .safeAreaInset(edge: .bottom) {
                    Button(action: save) {
                        Text(original == nil ? "保存账单" : "保存修改").font(.headline).frame(maxWidth: .infinity).padding(17)
                    }.buttonStyle(.borderedProminent)
                        .disabled(Money.parse(amount) == nil || paymentMethod.isEmpty || loadingImages)
                        .accessibilityIdentifier("saveRecord").padding(.horizontal, 20).padding(.vertical, 10)
                        .background(AppTheme.background)
                }
                .onAppear { if paymentMethod.isEmpty { paymentMethod = store.paymentMethods.first ?? "" } }
                .onChange(of: kind) { _, newValue in
                    if !RecordCategory.options(for: newValue).contains(category) { category = RecordCategory.options(for: newValue)[0] }
                }
                .onChange(of: selection) { _, items in
                    guard !items.isEmpty else { return }
                    imageTask?.cancel()
                    loadingImages = true
                    imageTask = Task { await importImages(items) }
                }
                .onDisappear { imageTask?.cancel() }
                .alert("添加付款方式", isPresented: $addingMethod) {
                    TextField("名称（最多 20 个字）", text: $newMethod)
                    Button("取消", role: .cancel) { newMethod = "" }
                    Button("添加") {
                        do {
                            try store.addPaymentMethod(newMethod)
                            paymentMethod = newMethod.trimmingCharacters(in: .whitespacesAndNewlines)
                            newMethod = ""
                        } catch { self.error = error.localizedDescription }
                    }
                } message: { Text("例如：交通卡、信用卡、家庭钱包") }
                .alert("操作失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                    Button("知道了", role: .cancel) { error = nil }
                } message: { Text(error ?? "") }
                .confirmationDialog("删除这笔账单及其图片？", isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button("删除账单", role: .destructive) {
                        if let original { do { try store.delete(original); dismiss() } catch { self.error = error.localizedDescription } }
                    }
                }
                .sheet(item: $imagePreview) { selected in
                    NavigationStack {
                        Image(uiImage: selected.image).resizable().scaledToFit().padding()
                            .frame(maxWidth: .infinity, maxHeight: .infinity).background(AppTheme.background)
                            .navigationTitle("账单图片").navigationBarTitleDisplayMode(.inline)
                            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { imagePreview = nil } } }
                    }
                }
        }
    }

    private func thumbnail(_ image: UIImage, remove: @escaping () -> Void) -> some View {
        Button { imagePreview = ImagePreview(image: image) } label: {
            Image(uiImage: image).resizable().scaledToFill().frame(width: 110, height: 110)
                .clipShape(RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain).accessibilityLabel("查看账单图片")
            .overlay(alignment: .topTrailing) {
                Button(action: remove) {
                    Image(systemName: "xmark.circle.fill").font(.title3).symbolRenderingMode(.palette).foregroundStyle(.white, AppTheme.ink)
                }.offset(x: 6, y: -6).accessibilityLabel("移除图片")
            }
    }

    @MainActor
    private func importImages(_ items: [PhotosPickerItem]) async {
        defer { loadingImages = false; selection = [] }
        var failures = 0
        for item in items.prefix(3 - imageCount) {
            if Task.isCancelled { return }
            do {
                guard let data = try await item.loadTransferable(type: Data.self),
                      let image = UIImage(data: data) else { failures += 1; continue }
                guard !Task.isCancelled else { return }
                let longest = max(image.size.width, image.size.height)
                let ratio = min(1, 1800 / max(longest, 1))
                let size = CGSize(width: max(image.size.width * ratio, 1), height: max(image.size.height * ratio, 1))
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
                guard let jpeg = resized.jpegData(compressionQuality: 0.82) else { failures += 1; continue }
                newImages.append(jpeg)
            } catch { failures += 1 }
        }
        if failures > 0 { error = "有 \(failures) 张图片无法导入。请检查 iCloud 下载状态，或重新选择图片。" }
    }

    private func save() {
        guard let value = Money.parse(amount) else { return }
        let record = LedgerRecord(id: original?.id ?? UUID(), kind: kind, amountMinor: value, category: category,
                                  paymentMethod: paymentMethod, date: date, note: note.trimmingCharacters(in: .whitespacesAndNewlines), imageNames: retainedNames)
        do { try store.save(record, newImages: newImages); dismiss() }
        catch { self.error = error.localizedDescription }
    }
}

private struct ImagePreview: Identifiable {
    let id = UUID()
    let image: UIImage
}
