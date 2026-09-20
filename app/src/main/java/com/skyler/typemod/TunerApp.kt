package com.skyler.typemod

import android.app.Application
import java.util.concurrent.Executors

class TunerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 不要在 onCreate 主线程里绑定 LSPosed 服务：registerListener 会触发 binder 往返，
        // 直接把冷启动拖慢（实测约 1s）。放到后台线程，UI 先出，服务就绪后再读配置。
        Executors.newSingleThreadExecutor().execute {
            RemoteConfig.init(this)
        }
    }
}
