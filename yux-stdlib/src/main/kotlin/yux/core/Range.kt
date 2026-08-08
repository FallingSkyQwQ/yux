package yux.core

/**
 * 整数范围（01-§6.4 for 循环的 `0..10` 语义；02-§8.2 的 `Range` 运行时）。
 *
 * IRGen 将 `a..b` 下沉为 `New Range(a, b)` 并调用 `iterator()`（StmtGen.genFor：
 * `hasNext`/`next` 走 java.util.Iterator）。迭代语义：闭区间 `[start, endInclusive]`。
 */
class Range(
    private val start: Int,
    private val endInclusive: Int,
) : Iterable<Int> {

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        private var next = start

        override fun hasNext(): Boolean = next <= endInclusive

        override fun next(): Int {
            if (!hasNext()) throw NoSuchElementException("Range 迭代越界")
            return next++
        }
    }
}
