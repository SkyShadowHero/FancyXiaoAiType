package io.github.skyshadowhero.fancypad

import android.app.Application
import java.io.File
import java.util.concurrent.Executors

class TunerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) installCrashLog()
        // 不要在 onCreate 主线程里绑定 LSPosed 服务：registerListener 会触发 binder 往返，
        // 直接把冷启动拖慢（实测约 1s）。放到后台线程，UI 先出，服务就绪后再读配置。
        Executors.newSingleThreadExecutor().execute {
            RemoteConfig.init(this)
        }
    }

    /**
     * 本机 logd 不收集常规缓冲区（见 [L] 的说明），闪退时 adb logcat 是空的，
     * 因此把未捕获异常落一份到应用私有目录：adb shell run-as <pkg> cat files/crash.txt。
     * 只在 debuggable 构建里装，发布包不会留下这个文件。
     */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                        .format(java.util.Date()))
                    append("  thread=").append(thread.name).append('\n')
                    append(android.util.Log.getStackTraceString(error)).append('\n')
                }
                File(filesDir, "crash.txt").writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
