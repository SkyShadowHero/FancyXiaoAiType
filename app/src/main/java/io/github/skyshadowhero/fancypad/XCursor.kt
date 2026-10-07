package io.github.skyshadowhero.fancypad

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Linux/XCursor 主题读取：把 `.xcur`（或主题里无扩展名的光标文件）解成 ARGB 帧，
 * 并支持把 `.zip` / `.tar` / `.tar.gz` 主题包逐条读出来。
 *
 * XCursor 文件格式（小端）：
 * ```
 * header : magic("Xcur") | headerSize | version | ntoc
 * toc[i] : type | subtype | position
 *   type 0xfffd0002 = image chunk：
 *     headerSize | type | subtype | version | width | height | xhot | yhot | delay | ARGB 像素(预乘)
 *   type 0xfffe0001 = comment chunk（作者/授权信息）
 * ```
 * 一个文件里通常有多档尺寸；同尺寸多张 = 动画帧（delay 为毫秒）。
 */
internal data class XCursorImage(
    val width: Int,
    val height: Int,
    val hotX: Int,
    val hotY: Int,
    val delay: Int,
    val argb: IntArray,
)

internal object XCursor {

    private const val MAGIC = 0x72756358          // "Xcur" little-endian
    private const val TYPE_IMAGE = -131348 // 0xfffd0002 as signed
    private const val MAX_SIDE = 512

    /** 解析一个 XCursor 文件；失败返回 null。 */
    fun parse(data: ByteArray): List<XCursorImage>? {
        if (data.size < 16) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != MAGIC) return null
        val headerSize = buf.getInt(4)
        val ntoc = buf.getInt(12)
        if (headerSize < 16 || ntoc <= 0 || ntoc > 4096) return null
        val out = ArrayList<XCursorImage>()
        for (i in 0 until ntoc) {
            val off = headerSize + i * 12
            if (off + 12 > data.size) break
            val type = buf.getInt(off)
            val pos = buf.getInt(off + 8)
            if (type != TYPE_IMAGE) continue
            if (pos < 0 || pos + 36 > data.size) continue
            val w = buf.getInt(pos + 16)
            val h = buf.getInt(pos + 20)
            if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) continue
            val hotX = buf.getInt(pos + 24)
            val hotY = buf.getInt(pos + 28)
            val delay = buf.getInt(pos + 32)
            val pixStart = pos + 36
            if (pixStart + w * h * 4 > data.size) continue
            val px = IntArray(w * h)
            for (j in 0 until w * h) {
                val b = buf.get(pixStart + j * 4).toInt() and 0xFF
                val g = buf.get(pixStart + j * 4 + 1).toInt() and 0xFF
                val r = buf.get(pixStart + j * 4 + 2).toInt() and 0xFF
                val a = buf.get(pixStart + j * 4 + 3).toInt() and 0xFF
                // XCursor 存的是预乘 alpha，Bitmap.setPixels 要非预乘
                val rr = if (a == 0) 0 else (r * 255 / a).coerceAtMost(255)
                val gg = if (a == 0) 0 else (g * 255 / a).coerceAtMost(255)
                val bb = if (a == 0) 0 else (b * 255 / a).coerceAtMost(255)
                px[j] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.add(XCursorImage(w, h, hotX, hotY, delay, px))
        }
        return out.ifEmpty { null }
    }

    /** 取最大尺寸的那些帧（同尺寸多张即动画帧）。 */
    fun bestFrames(images: List<XCursorImage>): List<XCursorImage> {
        val maxSide = images.maxOf { it.width * it.height }
        return images.filter { it.width * it.height == maxSide }
    }

    fun toBitmap(image: XCursorImage): Bitmap =
        Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)

}

/** 主题包里的一条文件。 */
internal class ThemeEntry(val name: String, val data: ByteArray)

internal object ThemeArchive {

    /**
     * 读 zip / tar / tar.gz，返回 (文件名, 内容) 列表（文件名已去掉目录前缀）。
     * 目录项与超出大小上限的项会被跳过。
     */
    fun read(fileName: String, bytes: ByteArray): List<ThemeEntry> {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") -> readZip(bytes)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> readTar(gunzip(bytes))
            lower.endsWith(".tar") -> readTar(bytes)
            else -> emptyList()
        }
    }

    private fun readZip(bytes: ByteArray): List<ThemeEntry> {
        val out = ArrayList<ThemeEntry>()
        try {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.size <= MAX_ENTRY) {
                        out.add(ThemeEntry(e.name, zip.readBytes()))
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            // 忽略损坏的包
        }
        return out
    }

    /** 最小 tar 读取器：512 字节块头 + 内容补齐到 512。 */
    private fun readTar(bytes: ByteArray): List<ThemeEntry> {
        val out = ArrayList<ThemeEntry>()
        var pos = 0
        while (pos + 512 <= bytes.size) {
            val nameEnd = (0 until 100).firstOrNull { bytes[pos + it] == 0.toByte() } ?: 100
            var name = String(bytes, pos, nameEnd, Charsets.UTF_8).trim()
            if (name.isEmpty()) break                        // 结束块
            val sizeField = String(bytes, pos + 124, 12, Charsets.UTF_8).trim().trimEnd('\u0000')
            val size = sizeField.toLongOrNull(8) ?: 0L
            val typeFlag = bytes[pos + 156].toInt().toChar()
            if (name.startsWith("./")) name = name.substring(2)
            val start = pos + 512
            if (typeFlag == '0' || typeFlag == '\u0000') {
                if (size in 1..MAX_ENTRY.toLong() && start + size <= bytes.size) {
                    out.add(ThemeEntry(name, bytes.copyOfRange(start, (start + size).toInt())))
                }
            }
            pos = start + (((size + 511) / 512) * 512).toInt()
        }
        return out
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        try {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } catch (t: Throwable) {
            ByteArray(0)
        }

    private const val MAX_ENTRY = 4 * 1024 * 1024
}
