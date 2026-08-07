package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.sema.SemaType.Basic
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M3-1 类型系统：可空性、赋值兼容、函数类型、推断变量求解、渲染。
 */
class TypeSystemTest {

    @Test
    fun `basic type render`() {
        assertEquals("Int", SemaType.INT.render())
        assertEquals("String?", Basic("String", true).render())
        assertEquals("Long", SemaType.LONG.render())
    }

    @Test
    fun `non-null assignable to nullable`() {
        assertTrue(TypeAssignability.isAssignable(SemaType.STRING, Basic("String", true)))
    }

    @Test
    fun `nullable not assignable to non-null`() {
        assertFalse(TypeAssignability.isAssignable(Basic("String", true), SemaType.STRING))
    }

    @Test
    fun `same base assignable regardless of nullability both ways when target nullable`() {
        assertTrue(TypeAssignability.isAssignable(Basic("String", true), Basic("String", true)))
    }

    @Test
    fun `nothing assignable to anything`() {
        assertTrue(TypeAssignability.isAssignable(SemaType.NothingT, SemaType.INT))
        assertTrue(TypeAssignability.isAssignable(SemaType.NothingT, SemaType.STRING))
    }

    @Test
    fun `any accepts everything`() {
        assertTrue(TypeAssignability.isAssignable(SemaType.STRING, SemaType.ANY))
        assertTrue(TypeAssignability.isAssignable(SemaType.INT, SemaType.ANY))
        assertTrue(TypeAssignability.isAssignable(Basic("String", true), Basic("Any", true)))
    }

    @Test
    fun `int not assignable to string`() {
        assertFalse(TypeAssignability.isAssignable(SemaType.INT, SemaType.STRING))
        assertFalse(TypeAssignability.isAssignable(SemaType.STRING, SemaType.INT))
    }

    @Test
    fun `int not assignable to long implicitly`() {
        assertFalse(TypeAssignability.isAssignable(SemaType.INT, SemaType.LONG))
    }

    @Test
    fun `declared type equality by symbol identity`() {
        val jvm = ClassPathSymbolProvider().resolve("java.lang.String")
        assertEquals("String", jvm?.name)
    }

    @Test
    fun `function type render`() {
        val ft = SemaType.Function(listOf(SemaType.INT), SemaType.STRING, nullable = false)
        assertEquals("(Int) -> String", ft.render())
    }

    @Test
    fun `function type nullability`() {
        val ft = SemaType.Function(listOf(SemaType.INT), SemaType.STRING, nullable = false).asNullable()
        assertEquals("((Int) -> String)?", ft.render())
    }

    @Test
    fun `numeric predicate`() {
        assertTrue(SemaType.isNumeric(SemaType.INT))
        assertTrue(SemaType.isNumeric(SemaType.LONG))
        assertTrue(SemaType.isNumeric(SemaType.DOUBLE))
        assertFalse(SemaType.isNumeric(SemaType.STRING))
        assertFalse(SemaType.isNumeric(SemaType.BOOLEAN))
    }

    @Test
    fun `inference var resolves through solution chain`() {
        val varA = SemaType.InferenceVar(1)
        val varB = SemaType.InferenceVar(2)
        varA.solution = varB
        varB.solution = SemaType.STRING
        assertEquals("String", varA.render())
    }

    @Test
    fun `jvm classpath provider caches and resolves`() {
        val provider = ClassPathSymbolProvider()
        val list = provider.resolve("java.util.List")
        val again = provider.resolve("java.util.List")
        assertTrue(list === again, "应命中缓存")
        assertEquals("List", list?.name)
        assertTrue(list?.isInterface == true)
    }

    @Test
    fun `jvm classpath provider resolves simple name via builtin classpath`() {
        val provider = ClassPathSymbolProvider()
        val sys = provider.resolve("java.lang.System")
        val method = sys?.staticMethods?.firstOrNull { it.name == "currentTimeMillis" }
        assertEquals("Long", method?.returnType?.render())
    }

    @Test
    fun `jvm method returns nullable reference type`() {
        val provider = ClassPathSymbolProvider()
        val obj = provider.resolve("java.lang.Object")
        assertEquals("String?", obj?.methods?.first { it.name == "toString" }?.returnType?.render())
    }

    @Test
    fun `generic arity from jvm reflection`() {
        val provider = ClassPathSymbolProvider()
        assertEquals(listOf("E"), provider.resolve("java.util.List")?.typeParams)
        assertEquals(listOf("K", "V"), provider.resolve("java.util.Map")?.typeParams)
    }

    @Test
    fun `resolver handles type params`() {
        val r = SemaTestSupport.analyze(
            """
            data Result T {
                ok:Boolean
                value:T
            }
            fun main() {
                r = Result(true, 5)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `nullable declared type renders`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                name:String? = null
                x = name
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
        assertEquals("String?", r.analysis.exprTypes.values.first { it is Basic }.render())
    }

    @Test
    fun `generic arity over args reported by resolver`() {
        // 解析器层拦截过度实参，此处直接驱动 TypeResolver 覆盖 E0005 路径
        val diags = yux.compiler.diag.DiagnosticSink()
        val provider = ClassPathSymbolProvider()
        val symTable = SymbolTable(provider)
        val file = FileScope("t.yux", "")
        symTable.files += file
        val resolver = TypeResolver(symTable, provider, diags)
        val span = yux.compiler.ast.SourceSpan(yux.compiler.lexer.SourcePosition(1, 1, 1), yux.compiler.lexer.SourcePosition(1, 1, 1))
        val intT = yux.compiler.ast.YxNamedType(listOf("Int"), emptyList(), nullable = false, span)
        val twoArgs = yux.compiler.ast.YxNamedType(listOf("List"), listOf(intT, intT), nullable = false, span)
        val resolved = resolver.resolve(twoArgs, file)
        assertEquals("List", (resolved as SemaType.Declared).symbol.name)
        assertTrue(
            diags.diagnostics.any { it.code == yux.compiler.diag.ErrorCodes.TYPE_ARGUMENT_COUNT_MISMATCH },
            diags.diagnostics.toString(),
        )
    }
}
