# 拾账 · iPhone 记账 App

一个使用 SwiftUI 编写的开源记账 App，支持图片附件、自定义金额与付款方式，以及 Excel 导入导出。支持 iOS 17 及以上，所有界面为中文。

Shizhang is an open-source iPhone expense tracker built with SwiftUI. It supports receipt photos, custom payment methods, exact monetary amounts, monthly summaries, and Excel (.xlsx) import/export. All records and images are stored locally.

## 获取源码

```sh
git clone https://github.com/mrlingan/shizhang.git
cd shizhang
open Shizhang.xcodeproj
```

## 运行

1. 打开 `Shizhang.xcodeproj`，选择 **Shizhang** Scheme。
2. 选择 iPhone 模拟器，按 `⌘R` 运行。
3. 若要安装到自己的 iPhone，在 Target → Signing & Capabilities 中选择你的 Apple 开发团队，并按需修改 Bundle Identifier。

首次打开时 Xcode 会通过 Swift Package Manager 下载 [ZIPFoundation 0.9.20](https://github.com/weichsel/ZIPFoundation)，用于读取和生成 `.xlsx` 中的 ZIP 容器。第三方许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 功能

- 支出 / 收入记账，人民币金额手动输入，精确到分。
- 每笔最多添加 3 张相册图片，支持预览、移除与编辑；图片最长边压缩到 1800 像素。
- 微信、支付宝、银行卡和现金，以及自行添加的付款方式；正在被账单使用的方式不能删除。
- 分类、日期、备注、搜索、收支筛选、月份切换。
- 编辑与确认删除账单；月度收入、支出、结余及分类占比。
- JSON 账本与图片保存到 App 的 Application Support 目录；写入失败会提示，账本读取失败时保留原文件并禁止覆盖。
- Excel `.xlsx` 导入与导出，可导出全部账单或指定月份；内置带分类下拉选项的导入模板。

图片“上传”在当前版本指从相册导入账单附件并保存在本机。当前版本没有服务器、账号、云同步或 OCR。卸载 App 会移除本地数据。系统相册选择器不需要读取整个照片库的权限。

## Excel 导入与导出

在底部 **数据** 页面操作：

1. 选择“全部账单”或“按月份”，点击“导出 Excel”，保存到系统“文件”中。
2. 点击“下载 Excel 导入模板”，在 Excel 或 WPS 中填写。模板的“填写说明”工作表解释格式和分类。
3. 点击“选择 Excel 文件”，检查导入预览。修正错误行后再导入，新付款方式会自动添加。

| 列名 | 格式 |
| --- | --- |
| 日期 | Excel 数值日期，或 `yyyy-MM-dd` 文本日期 |
| 类型 | `支出` 或 `收入` |
| 分类 | 对应收支类型的 App 分类，见模板 |
| 金额（元） | 正数，最多两位有效小数，最大 `999999999.99` |
| 付款方式 | 1–20 个字，可自定义 |
| 备注 | 可留空，普通文字 |
| 图片数量 | 仅用于说明，可留空 |
| 账单ID | 新账单留空，从 App 导出的账单请保留 |

前五列必填，列顺序可调整。导入选择名为“账单”的工作表，没有时选择第一个可见工作表。支持 Excel 的共享字符串、内联文本、1900 和 1904 日期系统。公式单元格需要转换为值，旧版 `.xls`、加密文件不支持。每次最多 10,000 笔，文件最多 20 MB。

导入先校验再一次性保存；存在错误行时不会写入任何账单。相同账单 ID 的记录会跳过，不覆盖已有金额或图片。没有 ID 的每行都视为新账单，重复导入这种表格会新增记录。

**Excel 不包含图片文件。** 导出仅记录图片数量，新导入的账单没有图片附件，已有账单的图片保留。Excel 可迁移账单数据，但不能作为图片完整备份。

## 验证

`⌘U` 运行 `ShizhangTests`，覆盖金额解析、月份统计、付款方式验证、图片保存与清理、账单重载、损坏账本保护，以及 Excel 往返、外部表格兼容、日期系统、错误行、批量导入与重复 ID。

命令行也可运行（设备名称以本机安装的模拟器为准）：

```sh
xcodebuild test -project Shizhang.xcodeproj -scheme Shizhang -destination 'platform=iOS Simulator,name=iPhone 18 Pro' CODE_SIGNING_ALLOWED=NO
```

已在 Xcode 27 / iOS 27 模拟器中通过编译与 13 项自动测试，并手动验证图片导入、大图预览、金额修改、自定义付款方式和重启后的数据保留；Excel 导出、模板保存、外部表格导入与导入完成提示、重复账单跳过和月份筛选也已在模拟器验证。

## 代码结构

| 文件 | 用途 |
| --- | --- |
| `Shizhang/ShizhangApp.swift` | App 入口与全局状态 |
| `Shizhang/Models.swift` | 账单模型、分类与金额解析 |
| `Shizhang/LedgerStore.swift` | 本地账本与图片文件读写 |
| `Shizhang/ContentView.swift` | 账本、月度统计与付款方式管理 |
| `Shizhang/RecordEditor.swift` | 账单编辑、相册导入与图片预览 |
| `Shizhang/Theme.swift` | 颜色与共用界面组件 |
| `Shizhang/ExcelWorkbook.swift` | Excel 文件生成与解析、逐行校验 |
| `Shizhang/ExcelTransferView.swift` | 导入导出、文件选择和导入预览 |
| `Shizhang/Resources/账单导入模板.xlsx` | 空白 Excel 导入模板与填写说明 |
| `ShizhangTests/LedgerTests.swift` | 核心逻辑测试 |
| `ShizhangTests/ExcelTests.swift` | Excel 互操作与批量导入测试 |

## 参与贡献

欢迎提交 Issue 或 Pull Request。开发与验证约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

本项目采用 [MIT License](LICENSE)。使用、修改和分发时请保留版权声明和许可证。
