# XiaoAiTypeMod

超级小爱输入法（Xiaomi Hyper XiaoAi Keyboard）的 **LSPosed 模块**：调整分离键盘的宽度、按键圆角、按键间距，提供「竖屏强制普通键盘」开关，并解锁**超级材质**（毛玻璃键盘背景）的适用范围。

配置界面使用 [Miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 设计语言的 Compose 组件库）构建，支持底部菜单 / 平板侧栏自适应。

---

## 功能

| 功能 | 说明 | 实时生效 |
|---|---|---|
| **分离键盘宽度** | 横屏 / 竖屏**分别**设置中心间隙；上限按对应朝向的物理屏宽 × 9/10 动态计算 | ✅ |
| **按键圆角** | 统一调整所有按键的圆角（0–48dp，默认 8dp） | ⚠️ 构建期参数，需重开键盘 |
| **按键间距** | 键高 / 键横向间距 / 行间距，横竖屏各一套；与间隙共用同一套「超过上限 6/10」过大警告 | ✅ |
| **竖屏强制普通键盘** | 开启后：横屏保持分离，竖屏强制变为整块普通键盘 | ✅ |
| **超级材质** | 解锁毛玻璃键盘背景：可**强制所有应用**或**手动勾选**一批应用；离屏填充随开关自动处理 | ✅ |
| **重启输入法** | 右上角图标 → Miuix 二次确认 → 强制关闭并重启输入法进程 | ✅ |

界面分四个页面：**分离键盘** / **间距** / **超级材质** / **关于**。横屏平板走左侧栏菜单，竖屏 / 手机走底部菜单。

---

## 适用环境

- **目标应用**：`com.xiaomi.type`（超级小爱输入法）
- **已验证版本**：`0.2.910.ba19145a`
- **框架**：LSPosed（API 102），已在 `LSPosed 2.2.0-it (7873)` 上验证
- **测试设备**：小米平板 7（2410CRP4CC，Android 17，2136×3200 @440dpi）

### 多版本兼容性

模块做了两层兼容设计：**反射按方法名+参数个数匹配** 、**运行时按资源名解析 ID**（资源 ID 跨版本会变，名字稳定）。

已静态比对三个版本的目标方法（`tools/cmp_material*.py` 用自写 DEX 解析器直接读 class_def）：

| Hook 目标 | 0.2.596 | 0.2.790 | 0.2.910 |
|---|---|---|---|
| `n9.e->a(String,boolean)` 配置读取 | ✅ | ✅ | ✅ |
| `n9.e->o()` 分离键盘开关 | ✅ | ✅ | ✅ |
| `MiInputMethodService->onWindowShown / onStartInput / onStartInputView` | ✅ | ✅ | ✅ |
| `xe.b->a(View,xe.e)` 材质应用入口 | ✅ | ✅ | ✅ |
| `bb.b0->j()` 材质状态机 | ❌ 有同名 `j` 但参数不同 | ❌ 该类只有 `a/b/c/d` | ✅ |
| `pc.m->L0(Iterable,Object)` 白名单判定 | ❌ 无此方法 | ❌ 只有 `L0(Iterable):List` | ✅ |

**结论：超级材质是 `0.2.910` 专属功能。** 在 `0.2.790` / `0.2.596` 上这两个 Hook 定位不到，模块会记 `method_missing` 并跳过（不崩溃），其余功能照常。另外 `0.2.596` 没有分离键盘资源（`pad_qwerty_*_split_center_gap` 不存在），该版本上「分离键盘宽度」也会自动跳过。

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

> 💡 每次**重装模块**后都需要重启作用域内的应用才会加载新代码；日志中出现 `event=install_done hooks=12` 即表示已生效。

---

## 验证（logcat）

```bash
adb logcat -s TypeMod:*
```

关键事件：

| 事件 | 含义 |
|---|---|
| `module_loaded` / `install_done hooks=12` | 模块注入成功、12 个 Hook 装载完成 |
| `resid_resolved by_name=17/17` | 资源按名解析成功（多版本兼容的关键） |
| `dimen_override ... newPx=... (Xdp)` | 尺寸覆写命中（间隙 / 圆角 / 间距） |
| `split_gate raw=... final=... landscape=...` | 竖屏门禁判定 |
| `restart_baseline signal=N` | 重启基线（避免残留信号导致自杀循环） |
| `restart_executing signal=N prev=M -> killing` | 执行杀进程，系统随后重新拉起 |
| `material_gate pkg=... allowed=...` | 超级材质放行判定 |
| `material_caps setMiBlurWinType(int)=ok ...` | 框架模糊接口能力探测（`MISS` 表示该机型缺这个接口） |
| `material_env_gate z7.a{...=true}` | 离屏填充门是否被强制打开 |
| `material_desc descriptor{...}` | 材质描述符（含模糊半径与混合色，用于核对材质是否真带模糊） |
| `offscreen_fill_applied` | 已补上离屏填充标记 |

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
| `bb.b0->j()` | 材质状态机的调用范围锚点（不改行为，只用于收窄下面的拦截范围） |
| `pc.m->L0(Iterable,Object)` | 超级材质放行判定（详见下文） |
| `xe.b->a(View,xe.e)` | 材质应用入口：观测描述符 + 补离屏填充 |
| `android.os.SystemProperties->get(String,String)` | 只在读 `persist.sys.advanced_visual_release` 时按配置回答 |

> ⚠️ 分离键盘横屏间隙走的是 `bb.h1.h()` → `Resources.getDimension()`，**不经过** Compose 尺寸解析器 `a.a.t`。早期误判过这个点，实测确认后改为 Hook `Resources`。

### 超级材质（毛玻璃键盘背景）

原厂只允许 `com.android.quicksearchbox` 使用毛玻璃键盘背景，判定写在 `bb.b0.j()`：

```java
z10 = this.s && pc.m.L0(versions.keySet(), 当前前台包名);   // 唯一的门
enable = z10;
dark   = force_dark 命中包名（或内部标志）;
light  = !dark && z10 && force_light 命中包名;
```

`this.s` 是设备能力位（背景模糊支持 且 用户已开启背景模糊），本机为 `true`；`versions` 来自配置
`hyper_material_package_versions`，缺失时由 `hyper_material_allowed_packages` 推导。

模块**在判定入口 `pc.m.L0` 上接管**，而不是去改 prefs —— 这样无论云端配置之后如何覆写
`allowed_packages` / `package_versions`，放行结果都由本模块决定。为免误伤，判定收了三道条件：
第一参数必须是 `bb.b0.j()` 内部 `new LinkedHashMap()` 的 keySet（类名 `java.util.LinkedHashMap$LinkedKeySet`）、
第二参数必须是包名字符串、且当前正处于 `bb.b0.j()` 调用内（ThreadLocal 深度计数）。

#### 为什么背景是实心的：离屏填充

「背景模糊」需要先把窗口背后的画面渲染到一块**离屏缓冲**里，模糊才有采样源 —— 这一步叫**离屏填充**。
目标应用自己就有这个门，并把它连日志一起打了出来：

```java
o5.q.E("AdvancedVisualGate",
    "persist.sys.advanced_visual_release=" + i5 + ", supportsOffScreenFill=" + z2);
```

判据是 `persist.sys.advanced_visual_release >= 6`。本机该属性为 **5**，于是应用认定「不支持离屏填充」，
跳过了把窗口标记为可模糊的关键调用：

```java
if (supportsOffScreenFill) {
    xe.h.x(view, 65536);   // setMiBlurWinType —— 被跳过
    view.postDelayed(...);
}
```

更麻烦的是这个值在进程内**只算一次**（静态字段），所以单靠「改属性」要重启输入法才生效，表现为「改了没反应」。

模块因此做两件事，**都随「启用超级材质」自动生效，没有单独开关**：

1. 在该属性被读取时回答 `6`（只影响本进程、只影响这一个 key）；
2. 在每次材质被应用时（Hook `xe.b->a`）**直接补调** `setMiBlurWinType(65536)` → 500ms 后 `setMiBlurWinType(1)`
   —— 所以开启超级材质后**立刻生效**，无需重启。

所有框架调用都是反射 + 异常吞掉，机型/版本缺接口时静默降级，不会把输入法搞崩。
启动时会打一份能力清单（`event=material_caps ...`），缺哪个接口一眼可见。

#### 应用选择器

「手动选择应用」列出的是**可启动的应用**（`MAIN`/`LAUNCHER`），只需在 Manifest 里声明 `<queries>` 意图，
不必申请 `QUERY_ALL_PACKAGES` 这种范围过大的权限。选中结果存成换行分隔的字符串而不是 `StringSet`：
RemotePreferences 的集合类型在跨进程同步上更容易出意外，字符串最稳且便于日志排查。

> ⚠️ 材质能否生效还取决于系统：需要 `persist.sys.background_blur_supported=true`
> 且 `Settings.Secure["background_blur_enable"]=1`。两者不满足时原厂自己也不会启用。

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
├─ java/com/skyler/fancytype/
│  ├─ XposedEntry.kt        # 模块入口，装载全部 Hook
│  ├─ Target.kt             # 目标包名 / 混淆类名 / 资源名与 ID
│  ├─ ConfigLoader.kt       # Hook 侧配置读取
│  ├─ RemoteConfig.kt       # App 侧 RemotePreferences 读写
│  ├─ PortraitGuard.kt      # 竖屏强制普通键盘
│  ├─ RestartSignal.kt      # 重启信号轮询执行
│  ├─ MaterialGate.kt       # 超级材质放行判定
│  ├─ MaterialEnhancer.kt   # 模糊接口能力探测 + 离屏填充
│  ├─ MaterialDiag.kt       # 材质链路诊断日志
│  ├─ MaterialPackages.kt   # 应用清单编解码
│  ├─ PrefKeys.kt           # 配置键名与默认值
│  ├─ TunerApp.kt / SettingsActivity.kt
│  └─ ui/                   # Miuix 界面
│     ├─ App.kt             # 根组件 + 配置装载（含写入守卫）
│     ├─ AppShell.kt        # 侧栏 / 底部菜单自适应外壳
│     ├─ SettingsPage.kt    # 分离键盘页
│     ├─ SpacesPage.kt      # 间距页
│     ├─ MaterialPage.kt    # 超级材质页（含应用选择器）
│     ├─ AppCatalog.kt      # 可启动应用枚举
│     ├─ SpaceWarning.kt    # 尺寸过大警告判定
│     ├─ AboutPage.kt       # 关于页（外观 + 项目仓库）
│     └─ Theme.kt
└─ resources/META-INF/xposed/
   ├─ module.prop           # API 102 元数据
   ├─ java_init.list        # 入口类
   └─ scope.list            # 作用域：com.xiaomi.type
```

---

## 已知限制

- **非白名单应用的「背景模糊」是框架限制，模块解决不了。**
  键盘毛玻璃的本质是 MIUI 的**透过窗口模糊**（pass window blur）：输入法窗口要糊的是宿主应用窗口的画面，
  而宿主窗口能否被穿透，由 system_server 侧的 **`PassWindowBlurFilterData`**（**云端下发**的名单）决定。
  实测：
  ```
  host=com.android.quicksearchbox   hostWhitelisted=true    ← 原厂唯一放行的那个
  host=tv.danmaku.bili              hostWhitelisted=false
  ```
  这正是原厂 `hyper_material_allowed_packages` 里只有 `com.android.quicksearchbox` 的原因 ——
  那份名单是框架能力的镜像，不是产品选择。名单位于 `/system_ext/framework/miui-services.jar`（`android` 进程），
  `framework-res.apk` / `services.jar` / 应用可改的任何位置都没有它。
  分屏 / 小窗下之所以能糊，是因为那些排布下键盘上方是已注册 pass-blur 表面的另一层（如 launcher / 壁纸），
  糊到的是「别的窗口」而不是当前宿主窗口。
  **模块能给的**：圆角 + 半透明叠加（描述符中的混合色为 `0x80FFFFFF`，50% 白），
  以及让原厂限定失效；**给不了的**：非白名单宿主的真实背景模糊。
  诊断入口：`adb logcat -s TypeMod:* | grep pass_window_blur`。
- **超级材质仅 `0.2.910` 可用**（依赖的 `bb.b0->j()` 与 `pc.m->L0(Iterable,Object)` 在更早版本不存在）。
- **超级材质依赖系统能力**：`persist.sys.background_blur_supported` 必须为 `true`，且
  `Settings.Secure["background_blur_enable"]` 必须为 `1`；不满足时原厂自己也不会启用材质。
- **按键圆角是构建期参数**：圆角值在键盘布局构建时读取并生成形状，改后需重开键盘才生效（间隙与间距在布局/绘制期读取，可实时跟随）。
- **无法单独设置个别按键的圆角**（如左下「符」键、右下「回车」键）：目标应用对按键只暴露**一个统一圆角参数**（`j0.e.a(单个float)`，四角同值），按键身份在几何展平后已丢失。应用内部虽有四角独立的形状构造 `j0.e.b(...)`，但缺少「当前画的是哪个键」的信息，无法挂钩。
- **0.2.596 不支持分离键盘宽度**（该版本无对应资源）。
- 混淆类名与资源名基于 `0.2.910` 样本，输入法升级后可能需要重新适配。

## 待办

- 超级材质的圆角 / 玻璃强度等细化参数
- 启动优化（当前冷启动约 1.1s，需要 release + R8 + Baseline Profile）
- 在 `0.2.790` / `0.2.596` 上做真机验证

---

## 许可

仅供个人学习与设备定制使用。使用前请确认符合相关服务条款。
