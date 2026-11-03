# FancyPad · R8 规则
#
# 模块入口：LSPosed 通过 META-INF/xposed/java_init.list 里的类名反射加载，
# 名字被改掉就等于模块不工作，所以整类保留。
-keep class io.github.skyshadowhero.fancypad.XposedEntry { *; }

# libxposed 的模块基类与回调：框架侧会按注解/接口反射查找。
-keep class * implements io.github.libxposed.api.XposedModule { *; }
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }

# Hook 目标进程里的类名是运行期字符串（Class.forName / cl.loadClass），
# 这些字符串指的是**系统类**，不受本包 R8 影响；但本包里被字符串引用的成员要保留。
-keepclassmembers class io.github.skyshadowhero.fancypad.** {
    public static <fields>;
}

# Miuix / Glasmio / Compose 的可组合函数通过反射（Compose 编译器生成的
# 合成方法、`@Composable` 调用点）使用，保守保留其构建器与 ABI 名称。
-keep class top.yukonga.miuix.kmp.** { *; }
-keep class io.github.neoruaa.glasmio.** { *; }

# 避免 R8 把 Kotlin metadata 清掉后导致 Compose 反射取元数据失败
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature

# 调试信息不需要
-dontwarn org.jetbrains.annotations.**
