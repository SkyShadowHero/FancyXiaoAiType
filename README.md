# XiaoAiTypeMod

超级小爱输入法（Xiaomi Hyper XiaoAi Keyboard）的 **LSPosed 模块**，用于调整分离键盘的宽度、按键圆角、按键间距，并提供「竖屏强制普通键盘」开关。

配置界面使用 [Miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 设计语言的 Compose 组件库）构建，支持底部菜单 / 平板侧栏自适应。

---

## 功能

| 功能 | 说明 | 实时生效 |
|---|---|---|
| **分离键盘宽度** | 横屏 / 竖屏**分别**设置中心间隙；上限按对应朝向的物理屏宽 × 9/10 动态计算 | ✅ |
| **按键圆角** | 统一调整所有按键的圆角（0–48dp，默认 8dp） | ⚠️ 构建期参数，需重开键盘 |
| **按键间距** | 键高 / 键横向间距 / 行间距，横竖屏各一套；超过上限 6/10 时提示可能破坏布局 | ✅ |
| **竖屏强制普通键盘** | 开启后：横屏保持分离，竖屏强制变为整块普通键盘 | ✅ |
| **重启输入法** | 右上角图标 → Miuix 二次确认 → 强制关闭并重启输入法进程 | ✅ |

界面分三个页面：**分离键盘** / **间距** / **关于**（外观与目标应用信息）。横屏平板走左侧栏菜单，竖屏 / 手机走底部菜单。

---

## 适用环境

- **目标应用**：`com.xiaomi.type`（超级小爱输入法）
- **已验证版本**：`0.2.910.ba19145a`
- **框架**：LSPosed（API 102），已在 `LSPosed 2.2.0-it (7873)` 上验证
- **测试设备**：小米平板 7（2410CRP4CC，Android 17，2136×3200 @440dpi）

### 多版本兼容性

模块做了两层兼容设计：**反射按方法名+参数个数匹配** 、**运行时按资源名解析 ID**（资源 ID 跨版本会变，名字稳定）。

已静态比对三个版本的目标方法，模块依赖的 Hook 点全部存在：

| Hook 目标 | 0.2.596 | 0.2.790 | 0.2.910 |
|---|---|---|---|
| `n9.e->a(String,boolean)` 配置读取 | ✅ | ✅ | ✅ |
| `n9.e->o()` 分离键盘开关 | ✅ | ✅ | ✅ |
| `MiInputMethodService->onWindowShown / onStartInput / onStartInputView` | ✅ | ✅ | ✅ |

**但 `0.2.596` 没有分离键盘资源**（`pad_qwerty_*_split_center_gap` 不存在），该版本上「分离键盘宽度」会自动跳过（不崩溃，只是不生效），其余功能可用。

> ⚠️ 实机验证仅在 `0.2.910` 上完成；其他版本为静态代码比对结论。

---

## 构建

**环境要求**

- JDK 21+（本项目用 JDK 24 构建）
- Android SDK：`compileSdk 37`（次版本号式平台 `android-37.0`）、`build-tools 36.0.0`

**工具链版本**（AGP 9.0+ 内置 Kotlin 支持，**不要**再单独应用 `kotlin.android` 插件）

| 组件 | 版本 |
|---|---|
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Kotlin Compose 插件 | 2.4.20 |
| compileSdk | 37 (minor 0) |
| Miuix | 0.9.4 |

**步骤**

```bash
# 1) 配置 SDK 路径
echo "sdk.dir=/path/to/Android/SDK" > local.properties

# 2) 构建
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 安装与启用

1. 安装 APK
2. 打开 **LSPosed 管理器 → 模块 → 启用「分离键盘调节」**
3. 勾选作用域：`com.xiaomi.type`
4. **重启输入法进程**（重要，否则新代码不会注入）：
   ```bash
   adb shell am force-stop com.xiaomi.type
   ```
5. 打开模块 App 进行配置；改动通过 RemotePreferences 实时下发

> 💡 每次**重装模块**后都需要重启作用域内的应用才会加载新代码；日志中出现 `event=install_done hooks=8` 即表示已生效。

---

## 验证（logcat）

```bash
adb logcat -s TypeMod:*
```

关键事件：

| 事件 | 含义 |
|---|---|
| `module_loaded` / `install_done hooks=8` | 模块注入成功、8 个 Hook 装载完成 |
| `resid_resolved by_name=17/17` | 资源按名解析成功（多版本兼容的关键） |
| `dimen_override ... newPx=... (Xdp)` | 尺寸覆写命中（间隙 / 圆角 / 间距） |
| `split_gate raw=... final=... landscape=...` | 竖屏门禁判定 |
| `restart_baseline signal=N` | 重启基线（避免残留信号导致自杀循环） |
| `restart_executing signal=N prev=M -> killing` | 执行杀进程，系统随后重新拉起 |

---

## 技术原理

### 为什么不能直接改 prefs 文件

目标应用的配置读取集中在单例门面 `n9.e`，它内部有 `ConcurrentHashMap` 快照缓存，启动时由 `getAll()` 一次性填充。**外部改写 XML 对已缓存值无效**，所以模块选择 Hook **读取入口**而非改磁盘。

### Hook 点

| 目标 | 作用 |
|---|---|
| `Resources.getDimension` / `getDimensionPixelSize` | 尺寸覆写的唯一可靠拦截点 |
| `n9.e->a(String,boolean)` / `n9.e->o()` | 配置读取 / 分离键盘开关 |
| `MiInputMethodService->onWindowShown / onStartInput / onStartInputView` | 重启信号轮询（多挂点，任一输入会话开始即检查） |
| `la.n-><init>` | 观测分离键盘真实几何（诊断日志） |

> ⚠️ 分离键盘横屏间隙走的是 `bb.h1.h()` → `Resources.getDimension()`，**不经过** Compose 尺寸解析器 `a.a.t`。早期误判过这个点，实测确认后改为 Hook `Resources`。

### 「重启输入法」的实现边界

**只有输入法进程能杀自己**。模块 App 没有权限杀别的进程（即便调用 `Process.killProcess(Process.myPid())`，杀的也是 App 自己；`am force-stop` 需要 shell/root）。

因此采用**信号 + 轮询**：

```
App 写入自增计数 → 输入法进程在生命周期点轮询 → 发现比基线大 → 自杀 → 系统重新拉起
```

启动时先用当前值初始化基线（`initBaseline()`），否则配置里残留的信号会导致**每个新进程一启动就自杀**的死循环。

### 图标与主题

- 主题：`MiuixTheme` + `ThemeController`，选择持久化到 `theme_mode`
- 弹窗：使用 `WindowDialog`（独立窗口层）而非 `OverlayDialog`。后者依赖 `Scaffold` 的 `MiuixPopupHost`，在本应用的多 Scaffold 结构下**无法渲染**
- 弹层依赖 `LocalNavigationEventDispatcherOwner`，缺失会抛 `IllegalStateException`

---

## 项目结构

```
app/src/main/
├─ java/com/skyler/typemod/
│  ├─ XposedEntry.kt        # 模块入口，装载全部 Hook
│  ├─ Target.kt             # 目标包名 / 混淆类名 / 资源名与 ID
│  ├─ ConfigLoader.kt       # Hook 侧配置读取
│  ├─ RemoteConfig.kt       # App 侧 RemotePreferences 读写
│  ├─ PortraitGuard.kt      # 竖屏强制普通键盘
│  ├─ RestartSignal.kt      # 重启信号轮询执行
│  ├─ PrefKeys.kt           # 配置键名与默认值
│  ├─ TunerApp.kt / SettingsActivity.kt
│  └─ ui/                   # Miuix 界面
│     ├─ App.kt             # 根组件 + 配置装载（含写入守卫）
│     ├─ AppShell.kt        # 侧栏 / 底部菜单自适应外壳
│     ├─ SettingsPage.kt    # 分离键盘页
│     ├─ SpacesPage.kt      # 间距页
│     ├─ AboutPage.kt       # 关于页（外观 + 目标应用）
│     └─ Theme.kt
└─ resources/META-INF/xposed/
   ├─ module.prop           # API 102 元数据
   ├─ java_init.list        # 入口类
   └─ scope.list            # 作用域：com.xiaomi.type
```

---

## 已知限制

- **按键圆角是构建期参数**：圆角值在键盘布局构建时读取并生成形状，改后需重开键盘才生效（间隙与间距在布局/绘制期读取，可实时跟随）。
- **无法单独设置个别按键的圆角**（如左下「符」键、右下「回车」键）：目标应用对按键只暴露**一个统一圆角参数**（`j0.e.a(单个float)`，四角同值），按键身份在几何展平后已丢失。应用内部虽有四角独立的形状构造 `j0.e.b(...)`，但缺少「当前画的是哪个键」的信息，无法挂钩。
- **0.2.596 不支持分离键盘宽度**（该版本无对应资源）。
- 混淆类名与资源名基于 `0.2.910` 样本，输入法升级后可能需要重新适配。

## 待办

- 启动优化（当前冷启动约 1.1s，需要 release + R8 + Baseline Profile）
- 在 `0.2.790` / `0.2.596` 上做真机验证

---

## 许可

仅供个人学习与设备定制使用。使用前请确认符合相关服务条款。
