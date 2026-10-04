# 拾账 · iPhone 记账 App

一个使用 SwiftUI 编写的开源记账 App，支持图片附件、自定义金额与付款方式。支持 iOS 17 及以上，所有界面为中文，无第三方依赖。

Shizhang is an open-source iPhone expense tracker built with SwiftUI. It supports receipt photos, custom payment methods, exact monetary amounts, and monthly summaries. All records and images are stored locally.

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

## 功能

- 支出 / 收入记账，人民币金额手动输入，精确到分。
- 每笔最多添加 3 张相册图片，支持预览、移除与编辑；图片最长边压缩到 1800 像素。
- 微信、支付宝、银行卡和现金，以及自行添加的付款方式；正在被账单使用的方式不能删除。
- 分类、日期、备注、搜索、收支筛选、月份切换。
- 编辑与确认删除账单；月度收入、支出、结余及分类占比。
- JSON 账本与图片保存到 App 的 Application Support 目录；写入失败会提示，账本读取失败时保留原文件并禁止覆盖。

图片“上传”在当前版本指从相册导入账单附件并保存在本机。当前版本没有服务器、账号、云同步或 OCR。卸载 App 会移除本地数据。系统相册选择器不需要读取整个照片库的权限。

## 验证

`⌘U` 运行 `ShizhangTests`，覆盖金额解析、月份统计、付款方式验证、图片保存与清理、账单重载和损坏账本保护。

命令行也可运行（设备名称以本机安装的模拟器为准）：

```sh
xcodebuild test -project Shizhang.xcodeproj -scheme Shizhang -destination 'platform=iOS Simulator,name=iPhone 18 Pro' CODE_SIGNING_ALLOWED=NO
```

已在 Xcode 27 / iOS 27 模拟器中通过编译与 4 项自动测试，并手动验证图片导入、大图预览、金额修改、自定义付款方式和重启后的数据保留。

## 代码结构

| 文件 | 用途 |
| --- | --- |
| `Shizhang/ShizhangApp.swift` | App 入口与全局状态 |
| `Shizhang/Models.swift` | 账单模型、分类与金额解析 |
| `Shizhang/LedgerStore.swift` | 本地账本与图片文件读写 |
| `Shizhang/ContentView.swift` | 账本、月度统计与付款方式管理 |
| `Shizhang/RecordEditor.swift` | 账单编辑、相册导入与图片预览 |
| `Shizhang/Theme.swift` | 颜色与共用界面组件 |
| `ShizhangTests/LedgerTests.swift` | 核心逻辑测试 |

## 参与贡献

欢迎提交 Issue 或 Pull Request。开发与验证约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

本项目采用 [MIT License](LICENSE)。使用、修改和分发时请保留版权声明和许可证。
