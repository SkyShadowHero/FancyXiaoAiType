# FancyPad

多个 LSPosed 功能域合并成的**单模块**：小米平板（HyperOS 4 / Android 17）上的输入法外观增强、触控笔随手写、系统光标主题、平行窗口动画修复、小窗控制菜单与右键菜单改造。

> 本分支（`fancypad`）是合并版；`master` 仍是原 **FancyType**（只有输入法外观）。

---

## 功能域

| 域 | 作用域 | 做什么 |
|---|---|---|
| 小爱输入法 | `com.xiaomi.type` | 分离键盘宽度/中心间隙、按键圆角与间距、悬浮键盘工具栏与候选窗口尺寸 |
| 光标主题 | `system` | 接管系统光标渲染（25 种类型），内置 AOSP / Material / MacOS / GoogleDot / BreezeX 预设，AOSP 与 GoogleDot 可任意改色；支持逐项导入 PNG/SVG 自定义光标；恢复被阉割的光标大小/颜色链路 |
| 平行窗口动画 | `com.android.systemui` | 把平行窗口（Activity Embedding）的转场从小米 Folme 引擎换回 AOSP 原生实现，并禁止转场合并与跳切 |
| 小窗控制器 | `com.android.systemui` | 小窗控制点的按钮条（含 OS4 多分屏顶栏菜单）：按钮显隐/排序、× → −、红色强制关闭 |
| 右键菜单 | 目标应用（默认 `mark.via`，可自加） | 把目标应用的列表长按/右键菜单改用 Miuix 外观 |

## 适用环境

| 项目 | 说明 |
|---|---|
| 设备 | 小米平板 `2410CRP4CC`（uke）/ HyperOS 4.0 / Android 17（SDK 37） |
| 框架 | LSPosed（libxposed **API 102**；模块 `minApiVersion=102`，用了 102 才有的热重载回调与 `libxposed:service`） |
| 目标输入法 | 超级小爱输入法 `com.xiaomi.type`（已验证 0.2.910 ~ 0.2.1246） |
| 最低 Android | 15（minSdk 35） |

## 安装

1. 安装 APK
2. LSPosed 管理器 → 模块 → 启用 **FancyPad**
3. 作用域勾上：`com.xiaomi.type`、`system`、`com.android.systemui`、`com.miui.securitycore`
   只在用「右键菜单」功能时才需要再加目标应用（例如 `mark.via`）
4. `system` / `com.android.systemui` / 安全中心是开机注入的 → **重启一次平板**；输入法侧只需重启输入法进程

## 构建

需要 JDK 21+ 与 Android SDK `compileSdk 37` / `compileSdkMinor 0`。

| 组件 | 版本 |
|---|---|
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Kotlin Compose 插件 | 2.4.20 |
| Miuix | 0.9.4 |

```bash
echo "sdk.dir=/path/to/Android/SDK" > local.properties
./gradlew :app:assembleDebug         # 或 assembleRelease
./gradlew :app:testDebugUnitTest     # 纯 JVM 单测：进程名归一 / 清单编解码 / 阈值 / 常量契约
```

> 单测只覆盖**不依赖 Android 框架**的纯逻辑（`ProcessNames`、`MaterialPackages`、
> `SpaceWarning`、`Target`/`PrefKeys` 的常量契约）。Hook 与 UI 仍靠真机验收，
> 截图与 uiautomator dump 存在同级目录 `_shots/`。

## 工程结构

```
app/src/main/
├── java/io/github/skyshadowhero/fancypad/
│   ├── XposedEntry.kt        # 唯一入口：按包名分发各功能域 + 失败重试 + 热重载
│   ├── ProcessNames.kt       # 进程名 → 包名归一（system / system_server / android）
│   ├── HookPrefs.java        # Hook 侧开关缓存（各进程共用）
│   ├── ConfigLoader.kt       # 输入法侧配置快照（1000ms 缓存 + 变更立即失效）
│   ├── Target.kt / AppTargets.kt  # 资源名表 / 按版本分档的混淆名
│   ├── CursorHooks.java      # [system] 光标接管
│   ├── CursorIcons.java      # 【生成】25 种类型 × 各主题资源表
│   ├── XCursor.kt            # Linux/XCursor 二进制解析（整包导入用，当前未挂入口）
│   ├── StylusSystemHooks.kt  # [system / 安全中心] 把「支持随手写」声明成 true
│   ├── StylusImeHooks.kt     # [输入法] 补 AOSP 四个 stylus 回调 + 识别 + 笔迹 + 手势
│   ├── StylusInkOverlay.kt   # 笔迹画布：挂进框架手写窗口，尺寸整屏
│   ├── StylusInkView.kt      # 笔迹绘制（按线段包围盒重绘，不整屏刷）
│   ├── StylusGestureEngine.kt# 圈选/删除/插入/换行/连接拆分（系统笔引擎的 GestureFacade）
│   ├── PencilEngine.kt       # 系统笔引擎（xiaomi-pencilengine-pad）访问层
│   ├── IflytekHcrEngine.kt   # 小爱自带讯飞 HCR（逐点流式）
│   ├── EmbeddingHooks.java   # [SystemUI] 平行窗口动画
│   ├── CaptionHooks.java     # [SystemUI] 小窗控制菜单
│   ├── AppMenuHooks.kt       # [目标应用] 右键菜单
│   ├── MaterialPackages.kt   # 需要「Miuix 材质」处理的目标包名（右键菜单复用）
│   ├── Diag.kt / L.kt        # 落盘诊断 + 结构化日志（本机 logd 不可用）
│   ├── TunerApp.kt / SettingsActivity.kt  # 模块 App 入口
│   └── ui/                   # Miuix 界面（ds/ 为共享组件层）
├── res/drawable-nodpi/       # 光标素材（各主题原图 + 预览 + wait 24 帧）
└── resources/META-INF/xposed/{module.prop,java_init.list,scope.list}
app/src/test/                 # 纯 JVM 单测
tools/cursor/                 # 光标素材生成器（gen_themes.py / gen_icons.py）
```

## 许可

仅供个人学习与设备定制使用。
素材遵循各自仓库许可：AOSP 矢量（Tech-Tac/aosp-cursors，Apache-2.0）、material-cursors（varlesh）、apple_cursor（ful1e5，GPL-3.0）、Google_Cursor / BreezeX_Cursor（ful1e5）。
