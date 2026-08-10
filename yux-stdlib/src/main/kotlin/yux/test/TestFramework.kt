package yux.test

/**
 * `@Test` 标记注解（01-§10.8）：`yuxc test` 反射扫描带本注解的方法并执行。
 * RUNTIME 保留使编译产物（RuntimeVisible 注解）可被类加载器反射发现。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Test

/**
 * 测试断言运行时（01-§10.8：`TestFramework.assertTrue` / `assertEquals` / `assertNull`）。
 *
 * Yux 测试源码 `import yux.test.TestFramework` 后静态调用；断言失败抛 [AssertionError]，
 * 由 `yuxc test` 捕获并记为失败用例。方法为显式重载（无默认参数）——
 * 编译器 ClassPathSymbolProvider 跳过 synthetic 方法，Kotlin 默认参数桥接不可见。
 */
object TestFramework {

    @JvmStatic
    fun assertTrue(condition: Boolean) {
        if (!condition) throw AssertionError("assertTrue 失败")
    }

    @JvmStatic
    fun assertTrue(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw AssertionError("assertEquals 失败: 期望 <$expected>，实际 <$actual>")
        }
    }

    @JvmStatic
    fun assertNull(value: Any?) {
        if (value != null) throw AssertionError("assertNull 失败: 实际 <$value>")
    }
}
