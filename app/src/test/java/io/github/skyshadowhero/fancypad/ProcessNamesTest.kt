package io.github.skyshadowhero.fancypad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程名 → 包名归一的契约测试。
 *
 * 这块早先出过真事故：热重载把 `processName` 当包名用，system_server 因此掉进
 * 「普通应用」分支去装右键菜单域。这里把三种系统进程写法与子进程后缀都钉住。
 */
class ProcessNamesTest {

    @Test
    fun systemProcessNamesAreRecognized() {
        // system_server 在不同框架/ROM 上报的名字不一样，三种都必须认
        assertTrue(ProcessNames.isSystemProcess("system"))
        assertTrue(ProcessNames.isSystemProcess("system_server"))
        assertTrue(ProcessNames.isSystemProcess("android"))
        assertTrue(ProcessNames.isSystemProcess(" android "))
        // 子进程后缀不影响判定
        assertTrue(ProcessNames.isSystemProcess("system:ui"))
    }

    @Test
    fun ordinaryProcessesAreNotSystem() {
        assertFalse(ProcessNames.isSystemProcess("com.xiaomi.type"))
        assertFalse(ProcessNames.isSystemProcess("com.android.systemui:screenshot"))
        assertFalse(ProcessNames.isSystemProcess("mark.via"))
        assertFalse(ProcessNames.isSystemProcess(null))
        assertFalse(ProcessNames.isSystemProcess(""))
    }

    @Test
    fun systemProcessMapsToAndroidPackage() {
        // 与 onPackageReady 的 packageName 对齐，热重载才能走同一个分支
        assertEquals(Target.SYSTEM_PACKAGE, ProcessNames.packageOf("system_server"))
        assertEquals(Target.SYSTEM_PACKAGE, ProcessNames.packageOf("system"))
        assertEquals(Target.SYSTEM_PACKAGE, ProcessNames.packageOf("android"))
    }

    @Test
    fun childProcessMapsToItsPackage() {
        assertEquals("com.android.systemui", ProcessNames.packageOf("com.android.systemui:screenshot"))
        assertEquals("com.xiaomi.type", ProcessNames.packageOf("com.xiaomi.type"))
        assertEquals("mark.via", ProcessNames.packageOf("mark.via:remote"))
    }

    @Test
    fun missingProcessNameYieldsNull() {
        assertNull(ProcessNames.packageOf(null))
        assertNull(ProcessNames.packageOf(""))
        assertNull(ProcessNames.packageOf("   "))
    }

    @Test
    fun diagDirIsSystemDirOnlyForSystemProcesses() {
        // 单测跑在 JVM（Windows 上分隔符是 `\`），所以统一归一成 `/` 再比 —— 设备上本来就是 `/`
        fun norm(p: java.io.File) = p.path.replace('\\', '/')

        assertEquals("/data/system", norm(ProcessNames.diagDir("system_server")))
        assertEquals("/data/system", norm(ProcessNames.diagDir("system")))
        assertEquals("/data/data/com.xiaomi.type/files", norm(ProcessNames.diagDir("com.xiaomi.type")))
        // 子进程也写主包的数据目录
        assertEquals(
            "/data/data/com.android.systemui/files",
            norm(ProcessNames.diagDir("com.android.systemui:screenshot")),
        )
    }
}
