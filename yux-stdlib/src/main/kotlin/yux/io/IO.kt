package yux.io

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 文件 I/O 标准库（yux.io）。
 *
 * Yux 语言程序通过 `import yux.io.IO` + `IO.readText("a.txt")` 静态调用本类方法，
 * 因此全部公开方法为 `object` + `@JvmStatic`。实现基于 JDK 内置 `java.nio.file`，
 * 文本一律按 UTF-8 编解码；I/O 错误统一包装为带路径信息的 [RuntimeException]。
 */
object IO {

    /**
     * 按 UTF-8 读取整个文件内容并返回字符串。
     *
     * @param path 文件路径（相对或绝对）
     * @return 文件内容
     * @throws RuntimeException 读取失败（如文件不存在），消息含路径，cause 保留原始 [IOException]
     */
    @JvmStatic
    fun readText(path: String): String {
        return try {
            String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw RuntimeException("yux.io: 无法读取 $path", e)
        }
    }

    /**
     * 按 UTF-8 将文本写入文件；父目录不存在时自动创建。
     *
     * @param path 文件路径（相对或绝对）
     * @param text 要写入的文本内容
     * @throws RuntimeException 写入失败，消息含路径，cause 保留原始 [IOException]
     */
    @JvmStatic
    fun writeText(path: String, text: String) {
        try {
            val file = Paths.get(path)
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
        } catch (e: IOException) {
            throw RuntimeException("yux.io: 无法写入 $path", e)
        }
    }

    /**
     * 按 UTF-8 读取文件全部行（以换行符分隔），返回 [ArrayList]。
     *
     * 语义与 `java.nio.file.Files.readAllLines` 一致：空文件返回空列表；
     * `\n`、`\r\n`、`\r` 均作为行分隔符，末尾换行不会产生多余空行。
     *
     * @param path 文件路径（相对或绝对）
     * @return 行列表（[ArrayList]）
     * @throws RuntimeException 读取失败（如文件不存在），消息含路径，cause 保留原始 [IOException]
     */
    @JvmStatic
    fun readLines(path: String): List<String> {
        return try {
            Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8).use { reader ->
                reader.readLines()
            }
        } catch (e: IOException) {
            throw RuntimeException("yux.io: 无法读取 $path", e)
        }
    }

    /**
     * 判断文件或目录是否存在。
     *
     * @param path 文件路径（相对或绝对）
     * @return 存在返回 true，否则返回 false
     */
    @JvmStatic
    fun exists(path: String): Boolean = Files.exists(Paths.get(path))
}
