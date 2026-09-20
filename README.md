# FancyType

超级小爱输入法（Xiaomi Hyper XiaoAi Keyboard）的外观增强 LSPosed 模块。

仓库：<https://github.com/SkyShadowHero/FancyXiaoAiType>

---

## 功能

界面分四个页面：**分离键盘** / **间距** / **超级材质** / **关于**（横屏平板走左侧栏，竖屏走底部菜单）。

| 功能 | 说明 |
|---|---|
| 分离键盘宽度 | 横屏 / 竖屏分别设置中心间隙，上限按对应朝向屏宽 × 9/10 |
| 按键圆角 | 0–48dp，默认 8dp |
| 按键气泡圆角 | 点击按键弹出的放大气泡，0–48dp，默认 14dp |
| 间距调节 | 按键高度 / 键横间距 / 键行间距，横竖屏各一套 |
| 边距调节 | 键盘离屏幕左/右/下的距离 |
| 竖屏强制普通键盘 | 横屏保持分离，竖屏回落为整块键盘 |
| 超级材质 | 解锁毛玻璃键盘背景：可强制所有应用，或手动勾选一批应用 |

## 适用环境

| 项目 | 说明 |
|---|---|
| 目标应用 | 超级小爱输入法 `com.xiaomi.type` |
| 已验证版本 | `0.2.910.ba19145a` |
| 框架 | LSPosed（libxposed API 102），已在 `2.2.0-it (7873)` 验证 |
| 测试设备 | 小米平板 7（2410CRP4CC / Android 17 / 2136×3200 @440dpi） |
| 最低 Android | 15（minSdk 35） |
| root | 仅「重启输入法」需要（KernelSU / Magisk） |

## 安装

1. 安装 APK
2. LSPosed 管理器 → 模块 → 启用 **FancyType**
3. 作用域勾选 `com.xiaomi.type`
4. **重启输入法进程**（重装模块后必做，否则新代码不会注入）

```bash
adb shell su -c "am force-stop com.xiaomi.type"
```

## 构建

需要 JDK 21+（本项目用 JDK 24）与 Android SDK `compileSdk 37` / `build-tools 36.0.0`。

| 组件 | 版本 |
|---|---|
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Kotlin Compose 插件 | 2.4.20 |
| Miuix | 0.9.4 |

```bash
echo "sdk.dir=/path/to/Android/SDK" > local.properties
./gradlew :app:assembleDebug
```

## 已知限制

- **非白名单应用拿不到「真实背景模糊」**，这是 MIUI 框架限制。宿主窗口能否被穿透由
  system_server 侧云端下发的 `PassWindowBlurFilterData` 名单决定，实测
  `isPassWindowBlurWhitelisted` 只对默认白名单应用返回 `true`。模块能解除默认的单应用限制、
  给出圆角与半透明叠加，但给不了真模糊。
- **超级材质仅 `0.2.910` 可用**（依赖的混淆方法在更早版本不存在）；材质还要求系统
  `background_blur_enabled` 已开启，否则默认也不会启用。
- **按键圆角 / 气泡圆角是构建期参数**，改后需重开键盘；间隙、间距、边距在布局期读取，实时生效。
- **无法单独设置个别按键的圆角**（如左下「符」键）：应用对按键只暴露一个统一圆角参数。
- **`0.2.596` 不支持分离键盘宽度**（该版本无对应资源）。
- 混淆类名与资源名基于 `0.2.910`，输入法升级后可能需要重新适配。

## 日志

```bash
adb logcat -s FancyType:*
```

关键事件：`module_loaded` / `install_done hooks=N` / `resid_resolved by_name=N/N` /
`dimen_override`（尺寸覆写命中）/ `split_gate`（竖屏门禁）/ `material_gate`（材质放行）。

## 许可

仅供个人学习与设备定制使用。
