import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息：优先读根目录 keystore.properties（不进版本库），
// 其次读命令行 -P 参数（ANDROID_SIGNING_*），两者都没有则出未签名包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signStorePath = keystoreProps.getProperty("storeFile")
    ?: (findProperty("ANDROID_SIGNING_STORE_FILE") as String?)
val signStorePassword = keystoreProps.getProperty("storePassword")
    ?: (findProperty("ANDROID_SIGNING_STORE_PASSWORD") as String?)
val signKeyAlias = keystoreProps.getProperty("keyAlias")
    ?: (findProperty("ANDROID_SIGNING_KEY_ALIAS") as String?)
val signKeyPassword = keystoreProps.getProperty("keyPassword")
    ?: (findProperty("ANDROID_SIGNING_KEY_PASSWORD") as String?)

android {
    namespace = "io.github.skyshadowhero.fancypad"
    // Miuix 0.9.4 / Compose 1.12 要求 compileSdk 37；
    // 本机 SDK 为次版本号式平台 android-37.0，故需同时指定 compileSdkMinor。
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "io.github.skyshadowhero.fancypad"
        minSdk = 35
        targetSdk = 37
        // versionCode 用 major*10000 + minor*10 + patch，便于后续按语义递增
        versionCode = 10014
        versionName = "1.1.4"
    }

    signingConfigs {
        if (!signStorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(signStorePath)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 体积主要来自 Compose / Miuix 的未裁剪代码：
            // 开启 R8 + 资源裁剪后 debug 的 ~78MB 能压到 10MB 量级。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 未提供签名信息时为 null，仍可正常产出 unsigned 包
            // 没配 release 签名时退回 debug 签名，保证 assembleRelease 直接可安装
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            // debug 也跑 R8：不裁剪时 dex 里塞满未用的 Compose/Miuix，
            // 包体 78MB；裁剪后 11MB 左右。调试信息仍在（isDebuggable 默认 true）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // 「检查更新」要读 BuildConfig.VERSION_NAME 跟 GitHub tag 比对
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // 由 LSPosed 框架在运行时提供，绝不能打包进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("androidx.annotation:annotation:1.9.1")

    // 模块 App 与 Hook 进程通信（RemotePreferences），需要打包
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.core:core-ktx:1.17.0")
    // 1.13.0 起 ComponentActivity 才实现 NavigationEventDispatcherOwner，并把
    // OnBackPressedDispatcher 用 DirectNavigationEventInput 接到 NavigationEventDispatcher。
    // 1.11.0 缺这一层，Miuix NavDisplay / NavigationBackHandler 全部收不到系统返回键
    // （表现：无论在哪一页按返回都直接退出 App）。不要降回去。
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // Miuix 弹层（MiuixPopupHost）内部注册 NavigationBackHandler，需要此宿主
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    // 动画 api 传递依赖不保证，显式声明（光标页的颜色展开动画用到）
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    // 光标页导入 SVG 素材时在 App 内栅格化（Android 本身不认 SVG）
    implementation("com.caverock:androidsvg-aar:1.4")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")

    // 识别模型功能：**不走 ONNX Runtime**。手写识别用的是小爱自带的讯飞 HCR
    // （见 IflytekHcrEngine）和系统笔引擎（见 PencilEngine，挂 /system_ext 的
    // xiaomi-pencilengine-pad.jar + ocr_model.tflite），都不需要额外依赖。
    // 早先这里引过 onnxruntime-android 1.15.1（配了一堆「系统里有同版本 so」的说明），
    // 但全工程从未引用过它的任何类 —— 已删除，别按旧注释加回来。

    // 纯 JVM 单元测试：只测不依赖 Android 框架的纯逻辑（编解码 / 阈值 / 常量契约）
    testImplementation("junit:junit:4.13.2")
}
