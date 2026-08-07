package yux.compiler.source

/** 编译单元的磁盘表示：路径 + 源码文本（UTF-8）。 */
data class SourceFile(
    val path: String,
    val text: String,
) {
    /** 不含目录的纯文件名，如 `main.yux`。 */
    val fileName: String get() = path.substringAfterLast('/')
}
