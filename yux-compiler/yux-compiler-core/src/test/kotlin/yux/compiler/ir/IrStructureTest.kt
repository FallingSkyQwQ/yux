package yux.compiler.ir

import org.junit.jupiter.api.Test
import yux.compiler.irgen.TypeBridge
import yux.compiler.sema.SemaType
import yux.compiler.sema.YxClassSymbol
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-M4-1 验收：IrModule 层次（属性 → backing 字段 + getter/setter 方法映射）。
 * T-M4-2 验收：IrStmt/IrExpr 结构完整（02-§8.2 全集 + 必要补充节点）。
 */
class IrStructureTest {

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
            isSealed = false,
            annotations = emptyList(),
            fileScope = null,
            span = null,
        )

    /** 属性 → backing 字段 + getter + setter 的三件套（T-M4-1 核心验收）。 */
    @Test
    fun `property maps to backing field getter and setter`() {
        val owner = IrClass("Player", isFileClass = false, isData = false, isService = false, superType = null, interfaces = emptyList())
        val field = IrField("name", IrType.STRING, isStatic = false, isFinal = false)
        val getter = IrMethod("getName", emptyList(), IrType.STRING, isStatic = false, isConstructor = false, isAsync = false, isOverride = false, isSynthetic = true, owner = owner)
        val setter = IrMethod("setName", listOf(IrParam("value", IrType.STRING)), IrType.Void, isStatic = false, isConstructor = false, isAsync = false, isOverride = false, isSynthetic = true, owner = owner)
        val prop = IrProperty("name", IrType.STRING, isVal = false, isStatic = false, backingField = field, getter = getter, setter = setter)

        assertEquals("name", prop.name)
        assertEquals(field, prop.backingField)
        assertEquals("getName", prop.getter!!.name)
        assertEquals("setName", prop.setter!!.name)
        assertEquals("value", prop.setter!!.params.single().name)
        assertTrue(prop.getter!!.isSynthetic)
    }

    /** 只读属性（isVal）无 setter（S-5.2.2）。 */
    @Test
    fun `val property has no setter`() {
        val prop = IrProperty("id", IrType.INT, isVal = true, isStatic = false, backingField = IrField("id", IrType.INT, false, true), getter = IrMethod("getId", emptyList(), IrType.INT, false, false, false, false, true), setter = null)
        assertNull(prop.setter)
        assertTrue(prop.isVal)
    }

    @Test
    fun `module and class registry`() {
        val fileClass = IrClass("Main", isFileClass = true, isData = false, isService = false, superType = null, interfaces = emptyList())
        val module = IrModule(mutableListOf(fileClass))
        assertEquals(listOf("Main"), module.allClasses.map { it.name })
        assertEquals(fileClass, module.classNamed("Main"))
        assertNull(module.classNamed("Nope"))
        assertTrue(fileClass.isFileClass)
    }

    @Test
    fun `method param locals are indexed from zero`() {
        val m = IrMethod(
            "add",
            listOf(IrParam("a", IrType.INT), IrParam("b", IrType.INT)),
            IrType.INT,
            isStatic = true,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = false,
        )
        assertEquals(listOf(0, 1), m.paramLocals.map { it.index })
        assertEquals("a", m.paramLocals[0].name)
        assertEquals(1, m.paramLocals[1].index)
        assertTrue(m.paramLocals.all { it.type.equivalent(IrType.INT) })
    }

    @Test
    fun `statement set covers branch goto return throw try`() {
        val l0 = IrLabel("L0")
        val l1 = IrLabel("L1")
        val stmts: List<IrStmt> = listOf(
            IrStmt.Label(l0),
            IrStmt.Branch(IrExpr.Const(true), l0, l1),
            IrStmt.Goto(l1),
            IrStmt.Return(null),
            IrStmt.Throw(IrExpr.Const("boom")),
            IrStmt.Try(emptyList(), listOf(IrCatch("e", null, emptyList())), null),
            IrStmt.Nop,
        )
        assertTrue(stmts[0] is IrStmt.Label)
        assertTrue(stmts[1] is IrStmt.Branch)
        assertEquals(l1, (stmts[1] as IrStmt.Branch).elseLabel)
        assertTrue(stmts[4] is IrStmt.Throw)
        assertTrue(stmts[5] is IrStmt.Try)
        assertEquals("e", ((stmts[5] as IrStmt.Try).catches.single()).paramName)
    }

    @Test
    fun `expression set covers invoke template guard lambda`() {
        val owner = IrClass("Main", isFileClass = true, isData = false, isService = false, superType = null, interfaces = emptyList())
        val method = IrMethod("main", emptyList(), IrType.Void, isStatic = true, isConstructor = false, isAsync = false, isOverride = false, isSynthetic = false, owner = owner)
        val builtin = IrJvmCall("print", "yux.core.CoreLib", static = true, params = listOf(IrType.ANY), retType = IrType.Void)
        val exprs: List<IrExpr> = listOf(
            IrExpr.Const(5),
            IrExpr.This,
            IrExpr.LocalRead(IrLocal("x", IrType.INT, 0)),
            IrExpr.Invoke(IrMethodRef(method), null, emptyList()),
            IrExpr.Invoke(builtin, null, listOf(IrExpr.Const("hi"))),
            IrExpr.StringTemplate(listOf(IrExpr.Const("Hi "), IrExpr.Const("name"))),
            IrExpr.NullGuard(IrExpr.FieldRead(null, IrField("len", IrType.INT, false, true))),
            IrExpr.Lambda(IrMethodRef(method)),
            IrExpr.Arith(ArithOp.ADD, IrExpr.Const(1), IrExpr.Const(2)),
            IrExpr.Compare(CompareOp.EQ, IrExpr.Const(1), IrExpr.Const(1)),
            IrExpr.Not(IrExpr.Const(false)),
            IrExpr.Neg(IrExpr.Const(1)),
            IrExpr.Convert(IrExpr.Const(1), IrType.LONG),
            IrExpr.IsType(IrExpr.Const("x"), IrType.STRING),
        )
        assertEquals(14, exprs.size)
        assertEquals("yux.core.CoreLib.print", builtin.displayName)
        assertEquals("Main.main", IrMethodRef(method).displayName)
        assertEquals(IrType.Void, builtin.retType)
    }

    @Test
    fun `inferType derives expression types`() {
        assertEquals(IrType.STRING, IrExpr.StringTemplate(emptyList()).inferType())
        assertEquals(IrType.BOOLEAN, IrExpr.Not(IrExpr.Const(true)).inferType())
        assertEquals(IrType.BOOLEAN, IrExpr.IsType(IrExpr.Const("x"), IrType.STRING).inferType())
        assertEquals(IrType.INT, IrExpr.Convert(IrExpr.Const(1), IrType.INT).inferType())
        assertEquals(IrType.INT, IrExpr.Arith(ArithOp.ADD, IrExpr.Const(1), IrExpr.Const(2)).inferType())
        assertEquals(IrType.LONG, IrExpr.Arith(ArithOp.ADD, IrExpr.Const(1L), IrExpr.Const(2)).inferType())
        assertEquals(IrType.STRING, IrExpr.Arith(ArithOp.ADD, IrExpr.Const("a"), IrExpr.Const("b")).inferType())
        assertEquals(IrType.INT, IrExpr.FieldRead(null, IrField("x", IrType.INT, false, false)).inferType())
        assertEquals(IrType.BOOLEAN, IrExpr.Compare(CompareOp.LT, IrExpr.Const(1), IrExpr.Const(2)).inferType())
    }

    @Test
    fun `sema declared type bridges to ir declared`() {
        val sym = classSymbol("User")
        val sema = SemaType.Declared(sym, emptyList(), nullable = false)
        val ir = TypeBridge.toIr(sema)
        assertTrue(ir is IrType.Declared)
        assertEquals("User", ir.render())
    }
}
