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
        // versionCode 用 major*10000 + minor*100 + patch，便于后续按语义递增
        // FancyPad 是三个模块合并后的新应用（新包名），从 1.0.0 起算
        versionCode = 10010
        versionName = "1.1.0"
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
            isMinifyEnabled = false
            isShrinkResources = false
            // 未提供签名信息时为 null，仍可正常产出 unsigned 包
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
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
        // ONNX Runtime 的 native 库**不打进 APK**：本机系统里本来就有同版本的
        // `/system_ext/lib64/libonnxruntime.so` + `libonnxruntime4j_jni.so`（都是 1.15.1），
        // 实测普通应用进程按名字/绝对路径都能 dlopen。这里只保留 AAR 里的 Java 绑定类（约 95KB），
        // 否则 arm64 的两个 .so 会白白多占 ~15MB。
        jniLibs {
            excludes += "**/libonnxruntime.so"
            excludes += "**/libonnxruntime4j_jni.so"
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
    implementation("androidx.activity:activity-compose:1.11.0")
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

    // 识别模型功能：ONNX Runtime 的 **Java 绑定**（native 库由系统提供，见上面的 packaging 排除）。
    // 版本必须跟系统里那份 .so 一致（1.15.1），否则 JNI 签名对不上会 UnsatisfiedLinkError。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.15.1")
}
