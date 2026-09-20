package com.skyler.typemod

/**
 * 「超级材质」手动选择的应用清单编解码。
 *
 * 存成换行分隔的字符串而不是 `StringSet`：RemotePreferences 的集合类型在
 * 跨进程（模块 App ↔ 输入法进程）同步时更容易出意外，纯字符串最稳，
 * 而且 logcat 里能直接看懂。包名不含换行符，所以分隔符是安全的。
 */
object MaterialPackages {

    private const val SEP = "\n"

    fun encode(packages: Collection<String>): String =
        packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(SEP)

    fun decode(raw: String?): Set<String> =
        raw?.split(SEP)
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
}

/** 供 [ConfigLoader] 直接调用的小工具。 */
internal fun splitPackages(raw: String?): Set<String> = MaterialPackages.decode(raw)
