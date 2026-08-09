package yux.core

/**
 * 字符串工具库（01-§10.2：字符串成员调用降级为对本类的静态调用）。
 *
 * IRGen 将 Yux 的字符串操作（uppercase/lowercase/split/format）解析为
 * `yux.core.Text.<name>` 静态调用（实参类型 ANY → JVM Object），
 * 本类方法签名必须与之一致。
 */
object Text {

    /** 全大写转换。 */
    @JvmStatic
    fun uppercase(s: String): String = s.uppercase()

    /** 全小写转换。 */
    @JvmStatic
    fun lowercase(s: String): String = s.lowercase()

    /**
     * 按字面分隔符拆分。
     *
     * 与 java.lang.String.split 的差异：此处采用 Kotlin `split(sep)` 的字面语义，
     * 把 sep 当作普通字符串匹配，而非 Java 的正则语义，避免 Yux 用户无意中
     * 踩正则元字符（`.`/`|`/`*` 等）的坑。
     */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun split(s: String, sep: String): java.util.List<String> = s.split(sep) as java.util.List<String>

    /**
     * 格式化：按 Java String.format 占位符（%s/%d/%.2f 等）替换。
     *
     * 设计偏差：01-§10.2 规划的 vararg 形式在 v0.1 用 List 参数替代
     * （便于编译器将调用降级为静态调用）。
     */
    @JvmStatic
    fun format(s: String, args: java.util.List<Any?>): String = String.format(s, *args.toTypedArray())
}
