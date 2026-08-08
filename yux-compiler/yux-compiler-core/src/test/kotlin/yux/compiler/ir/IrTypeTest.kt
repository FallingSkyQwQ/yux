package yux.compiler.ir

import org.junit.jupiter.api.Test
import yux.compiler.irgen.TypeBridge
import yux.compiler.sema.SemaType
import yux.compiler.sema.YxClassSymbol
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M4-3 验收：IrType 类型等价比较与渲染。
 */
class IrTypeTest {

    private fun classSymbol(name: String): YxClassSymbol =
        YxClassSymbol(
            name = name,
            typeParams = emptyList(),
            isData = false,
            isService = false,
            superType = null,
            interfaces = emptyList(),
            members = mutableListOf(),
            isAbstract = false,
            annotations = emptyList(),
            fileScope = null,
            span = null,
        )

    @Test
    fun `basic types are equivalent by name`() {
        assertTrue(IrType.Basic("Int").equivalent(IrType.Basic("Int")))
        assertFalse(IrType.Basic("Int").equivalent(IrType.Basic("String")))
        assertTrue(IrType.INT.equivalent(IrType.Basic("Int")))
    }

    @Test
    fun `nullable wraps and unwraps`() {
        val nullable = IrType.Nullable(IrType.STRING)
        assertTrue(nullable.nullable)
        assertFalse(IrType.STRING.nullable)
        assertEquals(IrType.STRING, nullable.nonNull())
        assertEquals("String?", nullable.render())
    }

    @Test
    fun `declared types are equivalent by symbol name across instances`() {
        val a = IrType.Declared(classSymbol("User"), emptyList())
        val b = IrType.Declared(classSymbol("User"), emptyList())
        val c = IrType.Declared(classSymbol("Player"), emptyList())
        assertTrue(a.equivalent(b))
        assertFalse(a.equivalent(c))
    }

    @Test
    fun `generic types compare args structurally`() {
        val a = IrType.Generic("List", listOf(IrType.INT))
        val b = IrType.Generic("List", listOf(IrType.INT))
        val c = IrType.Generic("List", listOf(IrType.STRING))
        assertTrue(a.equivalent(b))
        assertFalse(a.equivalent(c))
        assertEquals("List Int", a.render())
    }

    @Test
    fun `function types compare params and ret`() {
        val a = IrType.Function(listOf(IrType.INT, IrType.STRING), IrType.BOOLEAN)
        val b = IrType.Function(listOf(IrType.INT, IrType.STRING), IrType.BOOLEAN)
        val c = IrType.Function(listOf(IrType.INT), IrType.BOOLEAN)
        assertTrue(a.equivalent(b))
        assertFalse(a.equivalent(c))
        assertEquals("(Int, String) -> Boolean", a.render())
    }

    @Test
    fun `unit nothing and error render distinctly`() {
        assertEquals("Unit", IrType.Void.render())
        assertEquals("Nothing", IrType.Nothing.render())
        assertEquals("<error>", IrType.Error.render())
        assertTrue(IrType.Error.isError)
        assertFalse(IrType.Void.isError)
        assertTrue(IrType.Void.equivalent(IrType.Void))
        assertFalse(IrType.Void.equivalent(IrType.Nothing))
    }

    @Test
    fun `default value per base type`() {
        assertEquals(0, IrType.defaultValue(IrType.INT))
        assertEquals(0L, IrType.defaultValue(IrType.LONG))
        assertEquals(0.0f, IrType.defaultValue(IrType.FLOAT))
        assertEquals(0.0, IrType.defaultValue(IrType.DOUBLE))
        assertEquals(false, IrType.defaultValue(IrType.BOOLEAN))
        assertEquals('\u0000', IrType.defaultValue(IrType.CHAR))
        assertEquals(null, IrType.defaultValue(IrType.STRING))
        assertEquals(null, IrType.defaultValue(IrType.Declared(classSymbol("User"), emptyList())))
        // 可空类型默认值取其底类型
        assertEquals(0, IrType.defaultValue(IrType.Nullable(IrType.INT)))
    }

    @Test
    fun `type bridge maps sema types to ir`() {
        assertEquals(IrType.INT, TypeBridge.toIr(SemaType.INT))
        assertEquals(IrType.Nullable(IrType.STRING), TypeBridge.toIr(SemaType.basic("String", nullable = true)))
        assertEquals(IrType.Void, TypeBridge.toIr(SemaType.UnitT))
        assertEquals(IrType.Nothing, TypeBridge.toIr(SemaType.NothingT))
        val declared = SemaType.Declared(classSymbol("User"), emptyList(), nullable = false)
        val bridged = TypeBridge.toIr(declared)
        assertTrue(bridged is IrType.Declared)
        assertTrue(IrType.Declared(classSymbol("User"), emptyList()).equivalent(bridged))
        val fn = SemaType.Function(listOf(SemaType.INT), SemaType.BOOLEAN, nullable = false)
        assertEquals(IrType.Function(listOf(IrType.INT), IrType.BOOLEAN), TypeBridge.toIr(fn))
    }
}
