package yux.testsupport

/**
 * 编译单元的内存表示：虚拟路径 + 源码文本。
 *
 * 供词法/语法等测试直接构造源码，无需落地文件（T-M0-5 内存源文件工具）。
 */
data class SourceFile(val path: String, val text: String) {
    /** 不含目录的纯文件名，如 `main.yux`。 */
    val fileName: String get() = path.substringAfterLast('/')
}

/** 内存源文件工具。 */
object Sources {
    fun of(text: String, path: String = "main.yux"): SourceFile = SourceFile(path, text)

    fun multiple(vararg files: SourceFile): List<SourceFile> = files.asList()
}
