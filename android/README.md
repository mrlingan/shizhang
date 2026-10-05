# 拾账 · Android

现有 iPhone「拾账」的 Android Kotlin 版本。中文界面，米白与墨绿配色，使用 Android 原生 View / Material 组件；支持 Android 7.0（API 24）及以上。

## 功能

- 支出 / 收入记账，人民币手动输入，使用整数分保存，避免浮点误差。
- 分类、日期、备注；按月查看、收支筛选、搜索备注 / 分类 / 付款方式。
- 编辑账单，确认删除账单及其附件。
- 每笔最多 3 张图片；使用系统选择器，不申请整个相册或存储权限。处理 EXIF 方向，最长边压缩至 1800 像素；可预览、双指缩放、移除。
- 月度收支、结余、分类金额及占比。
- 默认微信支付、支付宝、银行卡、现金；支持自定义付款方式，禁止删除已使用的方式或最后一种方式。
- Excel `.xlsx` 全部 / 按月导出、保存导入模板、导入预览及错误行提示。
- 兼容 iPhone 版的列名、分类和账单 ID。支持 Excel / WPS 共享字符串、内联文本、1900 / 1904 日期系统。
- 本机 JSON 账本及图片；原子保存，保存失败回滚，损坏账本保留原文件并禁止覆盖。

没有账号、服务器、云同步或 OCR。卸载会删除本地账本及图片；应用关闭系统云备份与设备迁移备份。**Excel 只包含图片数量，不含图片文件，不能作为图片完整备份。**

## 运行与安装

用 Android Studio 打开这个目录，等待 Gradle 同步，选择 `app` 和设备，点击 Run。保持原空项目的包名 `com.lingansir.ooo`、AGP 9.4.1、Gradle 9.6.0、compile / target SDK 37。

项目使用 AGP 内置 Kotlin。Gradle wrapper 的 JVM 配置为 Java 25，可使用 Android Studio 自带的 JBR。

```sh
./gradlew :app:assembleDebug
```

调试安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。这是调试签名；正式发布时需要配置自己的 release 签名。

在本机未配置系统 Java 时：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug
```

## Excel 导入

在「数据」页保存导入模板，在 Excel / WPS 填写后点击「选择 Excel 文件」。先查看预览，再确认导入。

| 列 | 要求 |
| --- | --- |
| 日期 | Excel 日期，或 `yyyy-MM-dd`、`yyyy/MM/dd` 文本 |
| 类型 | 支出 / 收入 |
| 分类 | 与类型对应的拾账分类，见模板说明 |
| 金额（元） | 大于 0，最多两位有效小数，最大 999999999.99 |
| 付款方式 | 1–20 个字，新方式自动添加 |
| 备注 | 可留空，普通文字 |
| 图片数量 | 只作说明，导入不会生成图片 |
| 账单ID | 新账单留空；导出账单请保留，以免重复新增 |

前五列必填，列顺序可调整。优先选择名为「账单」的可见工作表，否则读取第一个可见工作表。每次最多 10,000 笔、文件最多 20 MB。不支持 `.xls`、加密表格和公式单元格，请先转换为值。

出现错误行时，整批不会写入。已有账单 ID 会跳过，不覆盖已有金额和图片；没有 ID 的行每次导入都会新增。同一文件内重复 ID 会提示错误。

## 验证

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
# 先启动模拟器或连接测试设备：
./gradlew :app:connectedDebugAndroidTest
```

单元测试覆盖金额精度、保存 / 重载 / 图片清理、保存失败回滚、损坏账本保护、付款方式约束、月份统计、批量导入原子性与重复 ID；Excel 测试覆盖往返、iPhone 模板、外部共享字符串、两种日期系统、公式 / 错误行、文件限额与实体拒绝。

设备测试覆盖真实界面新增 / 编辑 / Activity 重建、月度统计、自定义付款方式，以及真实图片压缩至 1800 像素、保存、移除和文件清理。测试会清空测试设备上这个应用的账本，请在模拟器或独立测试设备运行。

已在 2026-10-05 通过 debug APK 编译、13 项单元测试、3 项 Android 17 / API 37 模拟器测试，以及 Lint（0 错误）。也已手动验证系统文件保存、导出后重复 ID 预览、外部共享字符串表格导入、新付款方式添加，以及强制关闭后的数据保留。

界面截图见 `docs/screenshots/`，截图中的账单为验证用示例，不会预置到新安装的应用中。

## 主要文件

| 文件 | 用途 |
| --- | --- |
| `MainActivity.kt` | 四个页面、账单编辑、系统文件选择与导入预览 |
| `AppModel.kt` | 生命周期状态、后台操作、图片方向与压缩 |
| `Models.kt` | 收支分类、金额解析与统计 |
| `LedgerStore.kt` | JSON、图片及原子读写、批量导入 |
| `ExcelWorkbook.kt` | ZIP / SpreadsheetML 编码与逐行校验 |
| `ZoomImageView.kt` | 图片预览缩放与拖动 |
| `assets/ledger-template.xlsx` | 与 iPhone 版相同的导入模板 |

Kotlin 源码位于 `app/src/main/java/com/lingansir/ooo/`。第三方库及说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。项目沿用原版 MIT 许可证。
