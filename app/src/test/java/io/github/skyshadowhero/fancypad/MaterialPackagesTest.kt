package io.github.skyshadowhero.fancypad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「超级材质」应用清单的编解码。
 *
 * 这个字符串是**跨进程唯一的数据载体**（模块 App 写、输入法进程读），
 * 格式一改老用户的清单就废了，所以把格式钉在测试里。
 */
class MaterialPackagesTest {

    @Test
    fun encodeTrimsDedupesAndSorts() {
        val raw = MaterialPackages.encode(listOf(" mark.via ", "com.a", "mark.via", "", "  "))
        assertEquals("com.a\nmark.via", raw)
    }

    @Test
    fun decodeIgnoresBlankLinesAndDuplicates() {
        assertEquals(setOf("a", "b"), MaterialPackages.decode(" a \n\nb\na\n"))
        assertEquals(emptySet<String>(), MaterialPackages.decode(null))
        assertEquals(emptySet<String>(), MaterialPackages.decode(""))
        assertEquals(emptySet<String>(), MaterialPackages.decode("\n \n"))
    }

    @Test
    fun roundTripIsStable() {
        val packages = setOf("com.android.quicksearchbox", "mark.via", "com.miui.notes")
        assertEquals(packages, MaterialPackages.decode(MaterialPackages.encode(packages)))
    }

    @Test
    fun splitPackagesMatchesDecode() {
        // ConfigLoader 走的是这个小工具，两者不能有分歧
        val raw = "mark.via\ncom.a"
        assertEquals(MaterialPackages.decode(raw), splitPackages(raw))
    }

    @Test
    fun decodeKeepsPackageNameCase() {
        // 包名大小写敏感，不能顺手 lowercase
        val raw = MaterialPackages.encode(listOf("Com.Example.App"))
        assertTrue(MaterialPackages.decode(raw).contains("Com.Example.App"))
    }
}
