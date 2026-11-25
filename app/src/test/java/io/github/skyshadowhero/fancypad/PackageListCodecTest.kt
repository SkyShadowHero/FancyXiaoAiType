package io.github.skyshadowhero.fancypad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用应用清单的编解码。
 *
 * 这个字符串是**跨进程唯一的数据载体**（模块 App 写、输入法进程读），
 * 格式一改老用户的清单就废了，所以把格式钉在测试里。
 */
class PackageListCodecTest {

    @Test
    fun encodeTrimsDedupesAndSorts() {
        val raw = PackageListCodec.encode(listOf(" mark.via ", "com.a", "mark.via", "", "  "))
        assertEquals("com.a\nmark.via", raw)
    }

    @Test
    fun decodeIgnoresBlankLinesAndDuplicates() {
        assertEquals(setOf("a", "b"), PackageListCodec.decode(" a \n\nb\na\n"))
        assertEquals(emptySet<String>(), PackageListCodec.decode(null))
        assertEquals(emptySet<String>(), PackageListCodec.decode(""))
        assertEquals(emptySet<String>(), PackageListCodec.decode("\n \n"))
    }

    @Test
    fun roundTripIsStable() {
        val packages = setOf("com.android.quicksearchbox", "mark.via", "com.miui.notes")
        assertEquals(packages, PackageListCodec.decode(PackageListCodec.encode(packages)))
    }

    @Test
    fun decodeReadsPersistedLineFormat() {
        val raw = "mark.via\ncom.a"
        assertEquals(setOf("mark.via", "com.a"), PackageListCodec.decode(raw))
    }

    @Test
    fun decodeKeepsPackageNameCase() {
        // 包名大小写敏感，不能顺手 lowercase
        val raw = PackageListCodec.encode(listOf("Com.Example.App"))
        assertTrue(PackageListCodec.decode(raw).contains("Com.Example.App"))
    }
}
