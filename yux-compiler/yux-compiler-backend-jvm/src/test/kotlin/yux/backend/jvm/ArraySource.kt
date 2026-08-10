package yux.backend.jvm

/**
 * 数组互操作测试辅助（S-6.4.1 数组迭代）：JVM 原生数组经互操作进入 Yux 侧。
 * 测试类与编译管线同 classloader，Yux 源码 `import yux.backend.jvm.ArraySource` 后静态调用。
 */
object ArraySource {
    @JvmStatic
    fun ints(): IntArray = intArrayOf(10, 20, 30)

    @JvmStatic
    fun chars(): CharArray = charArrayOf('x', 'y', 'z')
}
