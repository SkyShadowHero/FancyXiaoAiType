# FancyType

超级小爱输入法（Xiaomi Hyper XiaoAi Keyboard）的外观增强 LSPosed 模块。

仓库：<https://github.com/SkyShadowHero/FancyXiaoAiType>

---

## 功能

| 功能 | 说明 |
|---|---|
| 分割键盘宽度 | 分割键盘中心间隙 |
| 虚拟按键 | 间距边距圆角候选词等 |
| 竖屏强制普通键盘 | 竖屏回落为普通键盘 |
| 超级材质 | 暂时不支持解锁模糊白名单 |
| 工具栏 | 圆角阴影间距边框等 |
| 候选窗口 | 圆角阴影间距边框宽度等 |

## 适用环境

| 项目 | 说明 |
|---|---|
| 目标应用 | 超级小爱输入法 `com.xiaomi.type` |
| 已验证版本 | `0.2.910.ba19145a~0.2.974.1cbd12c2` |
| 框架 | LSPosed（libxposed API 102），已在 `2.2.0-it (7873)` 验证 |
| 测试设备 | 小米平板 7（2410CRP4CC / Android 17 / 2136×3200 @440dpi） |
| 最低 Android | 15（minSdk 35） |

## 安装

1. 安装 APK
2. LSPosed 管理器 → 模块 → 启用 **FancyType**
3. 作用域勾选 `com.xiaomi.type`
4. **重启输入法**

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

## 许可

仅供个人学习与设备定制使用。
如有侵权请联系删除。
