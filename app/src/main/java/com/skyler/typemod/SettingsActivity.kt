package com.skyler.typemod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.skyler.typemod.ui.App

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式：让内容铺满，同时由 enableEdgeToEdge 按主题自动切换状态栏图标明暗，
        // 避免出现「内容压到状态栏、时间看不见」。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
