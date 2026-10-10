# FancyPad

多个 LSPosed 功能域合并成的**单模块**：小米平板（HyperOS 4 / Android 17）上的输入法外观增强、触控笔随手写、系统光标主题、平行窗口动画修复、小窗控制菜单与文本/长按菜单改造。

> 本分支（`fancypad`）是合并版；`master` 仍是原 **FancyType**（只有输入法外观）。

---

## 功能域

| 域 | 作用域 | 做什么 |
|---|---|---|
| 小爱输入法 | `com.xiaomi.type` | 分离键盘宽度/中心间隙、按键圆角与间距、竖屏强制普通键盘、悬浮键盘工具栏与候选窗口尺寸、超级材质（模糊背景白名单判定） |
| 随手写（触控笔手写） | `com.xiaomi.type` + `system` + `com.miui.securitycore` | 给小爱补上 AOSP 的触控笔手写：笔直接在输入框上写字、笔迹转文字上屏（讯飞 HCR 或系统笔引擎，纯本地），可显示笔迹；另在系统侧把它标成「支持随手写」，否则安全中心会把你切到搜狗 |
| 光标主题 | `system` | 接管系统光标渲染（25 种类型），内置 AOSP / Material / MacOS / GoogleDot / BreezeX 预设，AOSP 与 GoogleDot 可任意改色；支持逐项导入 PNG/SVG 自定义光标；恢复被阉割的光标大小/颜色链路 |
| 平行窗口动画 | `com.android.systemui` | 把平行窗口（Activity Embedding）的转场从小米 Folme 引擎换回 AOSP 原生实现，并禁止转场合并与跳切 |
| 小窗控制器 | `com.android.systemui` | 小窗控制点的按钮条：按钮显隐/排序、× → −、红色强制关闭 |
| AOSP长按菜单 | `com.android.systemui` | 文本选择浮动工具栏改用 Miuix 外观 |
| 右键菜单 | 目标应用（默认 `mark.via`，可自加） | 把目标应用的列表长按/右键菜单改用 Miuix 外观 |

各功能域的开关与参数都在同一个 App 里，界面统一用 **Miuix 0.9.4（Compose）**，平板 ≥840dp 走左侧栏、窄屏走底部菜单。

## 开关

- **光标**：`接管系统光标`（总开关）→ 关闭后不接管渲染，两个只读 aconfig flag 也交回系统。
- **平行窗口**：`恢复 AOSP 原生动画`（总开关）+ `禁用 Folme 引擎` / `禁止转场合并` / `禁止跳切` 三个子开关。
- **随手写**：`随手写（触控笔手写）`（总开关，默认关）+ 停笔识别延迟 + **笔迹显示**（开关 / 颜色 / 粗细 px）+ `让小爱进入随手写白名单`（默认**开**，见下文）。
- **输入法外观**：每个功能组各自一个开关（分离键盘、按键圆角、按键间距、候选词、悬浮键盘栏、超级材质、悬浮键盘大小…），沿用原 FancyType 的行为。

### 随手写为什么白名单默认是「开」

关掉它会让 MIUI 设置页里的「随手写」开关**根本打不开**（小爱被判为不支持 → 用户不会去动模块开关 → 永远不支持），所以声明必须默认开。另外 `com.miui.securitycore` 必须进作用域：真正会把你切到搜狗的是安全中心，它在改写默认输入法前先问一句「当前输入法支持随手写吗」，那个进程里装上同一个声明它就自己早退了。

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
3. 作用域勾上：`com.xiaomi.type`、`system`、`com.android.systemui`、`com.miui.securitycore`
   （最后一个是随手写必需：漏了它，系统设置里打开随手写会把你切到搜狗）
   只在用「右键菜单」功能时才需要再加目标应用（默认 `mark.via`）
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
│   ├── XposedEntry.kt        # 唯一入口：按进程分发各功能域
│   ├── HookPrefs.java        # Hook 侧开关缓存（各进程共用）
│   ├── ConfigLoader.kt       # 输入法侧配置快照
│   ├── Target.kt / AppTargets.kt  # 输入法侧按版本分档的混淆名
│   ├── CursorHooks.java      # [system] 光标接管
│   ├── CursorIcons.java      # 【生成】25 种类型 × 各主题资源表
│   ├── XCursor.kt            # Linux/XCursor 二进制解析（整包导入用，当前未挂入口）
│   ├── StylusSystemHooks.kt  # [system / 安全中心] 把「支持随手写」声明成 true
│   ├── StylusImeHooks.kt     # [输入法] 补 AOSP 四个 stylus 回调 + 识别 + 笔迹
│   ├── StylusInkOverlay.kt   # 笔迹画布：挂进框架手写窗口，可见部分是一条横带
│   ├── StylusInkView.kt      # 笔迹绘制（按线段包围盒重绘，不整屏刷）
│   ├── PencilEngine.kt       # 系统笔引擎（xiaomi-pencilengine-pad）访问层
│   ├── EmbeddingHooks.java   # [SystemUI] 平行窗口动画
│   ├── CaptionHooks.java     # [SystemUI] 小窗控制菜单
│   ├── SelectionToolbarHooks.kt  # [SystemUI] 文本选择工具栏
│   ├── AppMenuHooks.kt       # [目标应用] 右键菜单
│   └── ui/                   # Miuix 界面（各功能域页面）
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
- **笔迹画布挂在系统给小爱的手写窗口里**（那个窗口是按内容 wrap 出来的，所以尺寸由我们给）。书写区就是**整屏**（屏幕宽 × 高），写哪儿都有笔迹。早先那版是满宽一条横带、跟着落笔点上下移动，已废弃 —— 带子会在书写中途跳一下，屏幕边缘也写不出笔迹。
- 随手写的识别有两条路：小爱当前挂着讯飞手写引擎时走它（**逐点流式**，喂的是手写窗口坐标），否则走系统笔引擎（**攒一批**、按包围盒归零后识别）。两条都失败只会留下日志，不会让输入法崩。
- 笔迹的颜色 / 粗细在**每次起笔时**读取，改完下一次落笔生效（不是即时刷新画布）。
- **书写会话会短暂"接管"触控笔**：会话活着的时候系统把笔的事件从宿主应用抢给输入法（framework 的 `HandwritingModeController.pilferPointers`），所以这段时间里点界面是没反应的。模块把会话空闲超时压到秒级、并在字上屏后主动收会话 —— 一停笔笔就交回系统；之后"点一下"不会开手写会话（系统只在位移超过 touchSlop 时才开），只有画线才进入书写。
- **点击 vs 画线的判据**与框架一致：整笔位移不超过系统 `touchSlop`（本机 ≈21px）就算点击，不识别、只把会话收掉。若发现汉字的「点」画被吃掉、或点一下仍会出字，看 LSPosed 日志里 `event=stylus_` 的行（本机 logd 不收集常规缓冲区，`L` 把日志镜像到 `/data/adb/lspd/log/`）；阈值定义在 `StylusImeHooks.tapSlop()`，取的就是系统 `touchSlop`，正常不需要调。

## 许可

仅供个人学习与设备定制使用。
素材遵循各自仓库许可：AOSP 矢量（Tech-Tac/aosp-cursors，Apache-2.0）、material-cursors（varlesh）、apple_cursor（ful1e5，GPL-3.0）、Google_Cursor / BreezeX_Cursor（ful1e5）。
