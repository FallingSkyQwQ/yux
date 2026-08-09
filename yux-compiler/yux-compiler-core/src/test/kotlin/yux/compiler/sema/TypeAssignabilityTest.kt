package yux.compiler.sema

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M11-3：Yux 函数类型可赋给 JVM FunctionN SAM 接口（lambda 实参传 FunctionN 参数）。
 */
class TypeAssignabilityTest {

    private fun declared(qn: String): SemaType {
        val sym = ClassPathSymbolProvider().resolve(qn)
            ?: error("无法解析 $qn（yux-stdlib 须在测试类路径）")
        return SemaType.Declared(sym, emptyList(), nullable = true)
    }

    private val fn0 = SemaType.Function(emptyList(), SemaType.UnitT, nullable = false)
    private val fn1 = SemaType.Function(listOf(SemaType.INT), SemaType.INT, nullable = false)
    private val fn2 = SemaType.Function(listOf(SemaType.INT, SemaType.INT), SemaType.BOOLEAN, nullable = false)

    @Test
    fun `function assignable to Function1`() {
        assertTrue(TypeAssignability.isAssignable(fn1, declared("yux.core.function.Function1")))
    }

    @Test
    fun `function assignable to Function2`() {
        assertTrue(TypeAssignability.isAssignable(fn2, declared("yux.core.function.Function2")))
    }

    @Test
    fun `function assignable to Function0`() {
        assertTrue(TypeAssignability.isAssignable(fn0, declared("yux.core.function.Function0")))
    }

    @Test
    fun `function assignable to Function3`() {
        assertTrue(TypeAssignability.isAssignable(fn1, declared("yux.core.function.Function3")))
    }

    @Test
    fun `function not assignable to non FunctionN declared`() {
        assertFalse(TypeAssignability.isAssignable(fn1, declared("java.util.List")))
        assertFalse(TypeAssignability.isAssignable(fn1, declared("java.lang.Object")))
    }

    @Test
    fun `non function not assignable to FunctionN`() {
        assertFalse(TypeAssignability.isAssignable(SemaType.INT, declared("yux.core.function.Function1")))
    }
}
