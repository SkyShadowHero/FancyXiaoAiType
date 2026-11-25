# FancyPad

面向小米平板（HyperOS 4 / Android 17）的 LSPosed 综合模块，包含输入法与随手写、系统光标、平板动画、分栏、小窗及菜单增强。

> 本分支（`fancypad`）是合并版；`master` 仍是原 **FancyType**（只有输入法外观）。

---

## 功能域

| 域 | 作用域 | 做什么 |
|---|---|---|
| 输入法与随手写 | `com.xiaomi.type` | 键盘尺寸与样式、悬浮工具栏、候选窗口；触控笔识别、笔迹和手势，支持屏幕键盘/实体键盘/随手写切换 |
| 光标主题 | `system` | 接管 25 种系统光标，提供多套预设、改色及 PNG/SVG 导入 |
| 平行窗口动画 | `com.android.systemui` | 调整 Folme、转场合并和跳切行为 |
| 平行视界分栏 | 各目标 App | 分隔线松手不吸附，可调拖动范围 |
| 一镜到底 | `system` + 各目标 App | 为小红书、B站等恢复平板端共享元素动画 |
| 小窗控制器 | `com.android.systemui` | 自定义小窗/多分屏按钮及强制关闭动作 |
| 右键菜单 | 目标 App | 将列表长按或右键菜单替换为 Miuix 外观 |
| 应用工具 | 模块 App（Root） | 多选批量强制关闭已配置的目标应用 |

## 适用环境

| 项目 | 说明 |
|---|---|
| 设备 | 小米平板 `2410CRP4CC`（uke）/ HyperOS 4.0 / Android 17（SDK 37） |
| 框架 | LSPosed（libxposed **API 102**；模块 `minApiVersion=102`，用了 102 才有的热重载回调与 `libxposed:service`） |
| 目标输入法 | 超级小爱输入法 `com.xiaomi.type`（已验证 0.2.910 ~ 0.2.1448） |
| 最低 Android | 15（minSdk 35） |

## 安装

1. 安装 APK
2. LSPosed 管理器 → 模块 → 启用 **FancyPad**
3. 作用域勾上：`com.xiaomi.type`、`system`、`com.android.systemui`、`com.miui.securitycore`
   「一镜到底」另需把目标 App 自身（`com.xingin.xhs`、`tv.danmaku.bili`）加进作用域
   「右键菜单」「分栏无级」另需把目标应用加进作用域，并**强停该应用**让它重新被注入（例如 `mark.via`）
4. `system` / `com.android.systemui` / 安全中心是开机注入的 → **重启一次平板**；输入法侧只需重启输入法进程

## 构建

需要 JDK 21+ 与 Android SDK `compileSdk 37` / `compileSdkMinor 2`。

| 组件 | 版本 |
|---|---|
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Kotlin Compose 插件 | 2.4.20 |
| Miuix | 0.9.5-beta04 |

```bash
echo "sdk.dir=/path/to/Android/SDK" > local.properties
./gradlew :app:assembleDebug         # 或 assembleRelease
./gradlew :app:testDebugUnitTest     # 纯 JVM 单测
```

> Hook 与 UI 依赖系统环境，仍需真机验收。

## 工程结构

```
app/src/main/
├── java/io/github/skyshadowhero/fancypad/
│   ├── hooks/       # LSPosed Hook 与随手写引擎
│   ├── ui/          # Miuix 设置界面
│   └── data/        # 配置与持久化
├── res/             # Android 资源与光标素材
└── resources/META-INF/xposed/ # 模块入口和默认作用域
app/src/test/        # 纯 JVM 单测
tools/cursor/        # 光标素材生成工具
```

## 许可

仅供个人学习与设备定制使用。
素材遵循各自仓库许可：AOSP 矢量（Tech-Tac/aosp-cursors，Apache-2.0）、material-cursors（varlesh）、apple_cursor（ful1e5，GPL-3.0）、Google_Cursor / BreezeX_Cursor（ful1e5）。
