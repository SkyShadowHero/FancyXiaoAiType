# FancyPad

三个 LSPosed 模块合并成的**单模块**：小米平板（HyperOS 4 / Android 17）上的输入法外观增强 + 系统光标主题 + 平行窗口动画修复。

> 本分支（`fancypad`）是合并版；`master` 仍是原 **FancyType**（只有输入法外观）。

---

## 功能域

| 域 | 作用域 | 做什么 |
|---|---|---|
| 输入法外观 | `com.xiaomi.type` | 分离键盘宽度/中心间隙、按键圆角与间距、竖屏强制普通键盘、悬浮键盘工具栏与候选窗口尺寸、超级材质（模糊背景白名单判定） |
| 光标主题 | `system` | 接管系统光标渲染（25 种类型），内置 AOSP / Material / MacOS / GoogleDot / BreezeX 预设，AOSP 与 GoogleDot 可任意改色；支持逐项导入 PNG/SVG 自定义光标；恢复被阉割的光标大小/颜色链路 |
| 平行窗口动画 | `com.android.systemui` | 把平行窗口（Activity Embedding）的转场从小米 Folme 引擎换回 AOSP 原生实现，并禁止转场合并与跳切 |

三个域的开关与参数都在同一个 App 里，界面统一用 **Miuix 0.9.4（Compose）**，平板 ≥840dp 走左侧栏、窄屏走底部菜单。

## 开关

- **光标**：`接管系统光标`（总开关）→ 关闭后不接管渲染，两个只读 aconfig flag 也交回系统。
- **平行窗口**：`恢复 AOSP 原生动画`（总开关）+ `禁用 Folme 引擎` / `禁止转场合并` / `禁止跳切` 三个子开关。
- **输入法外观**：每个功能组各自一个开关（分离键盘、按键圆角、按键间距、候选词、悬浮键盘栏、超级材质、悬浮键盘大小…），沿用原 FancyType 的行为。

## 适用环境

| 项目 | 说明 |
|---|---|
| 设备 | 小米平板 `2410CRP4CC`（uke）/ HyperOS 4.0 / Android 17（SDK 37） |
| 框架 | LSPosed（libxposed API 102） |
| 目标输入法 | 超级小爱输入法 `com.xiaomi.type`（已验证 0.2.910 ~ 0.2.1053） |
| 最低 Android | 15（minSdk 35） |

## 安装

1. 安装 APK
2. LSPosed 管理器 → 模块 → 启用 **FancyPad**
3. 作用域**三个都要勾**：`com.xiaomi.type`、`system`、`com.android.systemui`
4. `system` / `com.android.systemui` 是开机注入的 → **重启一次平板**；输入法侧只需重启输入法进程

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
./gradlew :app:assembleDebug        # 或 assembleRelease
```

Termux 上可直接用：

```bash
bash ~/build-fancytype.sh           # 指向本工程，构建 debug
```

> release 若没提供签名信息会退化成未签名/调试签名包：把 `storeFile/storePassword/keyAlias/keyPassword` 写进根目录 `keystore.properties`，或用 `-PANDROID_SIGNING_*` 参数。

## 工程结构

```
app/src/main/
├── java/io/github/skyshadowhero/fancypad/
│   ├── XposedEntry.kt        # 唯一入口：按进程分发三个功能域
│   ├── HookPrefs.java        # Hook 侧开关缓存（三个进程共用）
│   ├── ConfigLoader.kt       # 输入法侧配置快照
│   ├── CursorHooks.java      # [system] 光标接管
│   ├── CursorIcons.java      # 【生成】25 种类型 × 各主题资源表
│   ├── XCursor.kt            # Linux/XCursor 二进制解析（整包导入用，当前未挂入口）
│   ├── EmbeddingHooks.java   # [SystemUI] 平行窗口动画
│   ├── Target.kt / AppTargets.kt  # 输入法侧按版本分档的混淆名
│   └── ui/                   # Miuix 界面（虚拟键盘/悬浮键盘/超级材质/光标/平行窗口/关于）
├── res/drawable-nodpi/       # 光标素材（各主题原图 + 预览 + wait 24 帧）
└── resources/META-INF/xposed/{module.prop,java_init.list,scope.list}
tools/cursor/                 # 光标素材生成器（gen_themes.py / gen_icons.py）
```

## 版本适配

输入法每次发版都会用 R8 重新混淆类名，所以模块把每个输入法版本的混淆名**单独记一档**（`AppTargets.kt`），运行时自动挑出匹配的那档；未记录的新版本会降级：按资源名定位的功能（圆角/间距/宽度/边距等）照常工作，按类名定位的功能提示不可用。

## 已知限制

- **非白名单应用拿不到「真实背景模糊」**：宿主窗口能否被穿透由 system_server 侧云端下发的 `PassWindowBlurFilterData` 名单决定，模块能解除默认的单应用限制、给出圆角与半透明叠加，但给不了真模糊。
- 光标预设里 Material / MacOS / BreezeX 用各仓库**原色图**，不可改色（只有 AOSP 与 GoogleDot 能改）；缺图统一用 AOSP 兜底。
- `wait`（忙碌）光标在部分主题里没有素材，交回系统的动画光标。

## 许可

仅供个人学习与设备定制使用。
素材遵循各自仓库许可：AOSP 矢量（Tech-Tac/aosp-cursors，Apache-2.0）、material-cursors（varlesh）、apple_cursor（ful1e5，GPL-3.0）、Google_Cursor / BreezeX_Cursor（ful1e5）。
