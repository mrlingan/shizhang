# 第三方组件

本项目使用以下 Android / JVM 库，保留各组件原许可证。Gradle 会下载构建及测试依赖。

| 组件 | 用途 | 许可证 / 项目 |
| --- | --- | --- |
| AndroidX（AppCompat、Core、Lifecycle、Activity、ExifInterface、Test） | 界面、生命周期、照片方向、测试 | Apache-2.0；https://android.googlesource.com/platform/frameworks/support/ |
| Material Components for Android | 原生 Material 按钮与主题 | Apache-2.0；https://github.com/material-components/material-components-android |
| Kotlin 标准库 | Kotlin 运行时 | Apache-2.0；https://github.com/JetBrains/kotlin |
| desugar_jdk_libs | Android 7/8 的 java.time 等 API 兼容 | GPL-2.0 with Classpath Exception；https://github.com/google/desugar_jdk_libs |
| JUnit 4 | 单元测试，仅测试依赖 | EPL-1.0；https://github.com/junit-team/junit4 |
| JSON-java | JVM 测试时的 JSON 实现，仅测试依赖 | Public Domain；https://github.com/stleary/JSON-java |

Excel 读写使用 Android / Java 自带 ZIP 与 XML API，不包含 ZIPFoundation 或 Apache POI。导入模板及产品设计移植自原「拾账」iPhone 项目，遵循本仓库 LICENSE。
