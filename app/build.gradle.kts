plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.skyler.typemod"
    // Miuix 0.9.4 / Compose 1.12 要求 compileSdk 37；
    // 本机 SDK 为次版本号式平台 android-37.0，故需同时指定 compileSdkMinor。
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.skyler.typemod"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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

    // 模块 App 与 Hook 进程通信（RemotePreferences），需要打包
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // Miuix 弹层（MiuixPopupHost）内部注册 NavigationBackHandler，需要此宿主
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
}
