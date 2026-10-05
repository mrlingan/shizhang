# 参与贡献

感谢你帮助改进拾账。仓库包含 SwiftUI iPhone 版和 `android/` 下的 Kotlin Android 版。

## 报告问题

提交 Issue 时请说明平台、系统版本和 Xcode / Android Studio 版本，以及复现步骤、预期结果和实际结果。截图请使用演示数据，避免包含真实账单、支付账号或其他个人信息。

## 提交改动

1. Fork 仓库，在自己的分支中进行修改。
2. iPhone：用 Xcode 打开 `Shizhang.xcodeproj`，选择 `Shizhang` Scheme。Android：用 Android Studio 打开 `android/`，选择 `app`。
3. 验证涉及的界面与记账流程。更改金额、统计、存储或 Excel 逻辑时，补充必要的测试。
4. iPhone 运行 `⌘U`；Android 运行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`（在 `android/` 中执行）。图片或生命周期相关改动还应在模拟器运行 `:app:connectedDebugAndroidTest`；设备测试会清空应用账本，请使用独立测试设备。
5. 提交 Pull Request，说明问题、改动与验证结果。

金额以整数“分”存储；保存失败时应保留原数据，删除账单后应清理其图片文件，损坏账本不能被覆盖。批量导入应先校验再整批保存；已有账单 ID 不得覆盖已有金额和图片。请保持这些行为，以及两平台 Excel 的列名、分类和 ID 兼容性。

不要提交编译产物、签名证书、密钥、`local.properties`、个人 IDE 配置或真实账单数据。Gradle Wrapper 的脚本和 JAR 属于构建所需文件，应纳入版本控制。
