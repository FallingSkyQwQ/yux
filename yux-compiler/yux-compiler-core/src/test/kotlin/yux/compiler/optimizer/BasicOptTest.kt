package yux.compiler.optimizer

import org.junit.jupiter.api.Test
import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M4-5 验收：最小优化（常量折叠、死代码消除、冗余跳转、空守卫折叠）。
 * 全部为纯 IR 构造（不依赖解析器/IRGen）。
 */
class BasicOptTest {

    private val local0 = IrLocal("x", IrType.INT, 0)
    private val local1 = IrLocal("y", IrType.INT, 1)

    /** 构造单方法模块并对 body 执行优化。 */
    private fun optimize(body: List<IrStmt>): List<IrStmt> {
        val cls = IrClass("Main", isFileClass = true, isData = false, isService = false, superType = null, interfaces = emptyList())
        val method = IrMethod(
            name = "main",
            params = emptyList(),
            returnType = IrType.Void,
            isStatic = true,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = false,
            owner = cls,
        )
        method.body.addAll(body)
        cls.methods.add(method)
        BasicOpt.optimize(IrModule(mutableListOf(cls)))
        return method.body
    }

    @Test
    fun `2+3 folds to 5`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Arith(ArithOp.ADD, IrExpr.Const(2), IrExpr.Const(3))),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.Const(5)), IrStmt.Return(IrExpr.LocalRead(local0))),
            body,
        )
    }

    @Test
    fun `unreachable code after return is removed`() {
        val body = optimize(
            listOf(
                IrStmt.Return(IrExpr.Const(1)),
                IrStmt.LocalAssign(local0, IrExpr.Const(2)),
                IrStmt.LocalAssign(local1, IrExpr.Const(3)),
            ),
        )
        assertEquals(listOf(IrStmt.Return(IrExpr.Const(1))), body)
    }

    @Test
    fun `branch on constant folds to goto and redundant goto is dropped`() {
        val l1 = IrLabel("L1")
        val l2 = IrLabel("L2")
        val l3 = IrLabel("L3")
        val body = optimize(
            listOf(
                IrStmt.Branch(IrExpr.Const(true), l1, l2),
                IrStmt.Label(l1),
                IrStmt.Goto(l3),
                IrStmt.Label(l2),
                IrStmt.Label(l3),
            ),
        )
        // 折叠后 Goto(L1)；随后的 Label(L1) 使该 Goto 冗余 → 删除
        assertFalse(body.any { it is IrStmt.Branch }, "常量分支应折叠为 Goto: $body")
        assertEquals(listOf(IrStmt.Label(l1), IrStmt.Goto(l3), IrStmt.Label(l2), IrStmt.Label(l3)), body)
    }

    @Test
    fun `null guard with non-null receiver folds away`() {
        val field = IrField("len", IrType.INT, isStatic = false, isFinal = false)
        val guarded = IrExpr.NullGuard(IrExpr.FieldRead(IrExpr.Const("x"), field))
        val body = optimize(
            listOf(IrStmt.LocalAssign(local0, guarded), IrStmt.Return(IrExpr.LocalRead(local0))),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.FieldRead(IrExpr.Const("x"), field)), IrStmt.Return(IrExpr.LocalRead(local0))),
            body,
        )
    }

    @Test
    fun `null guard on null receiver folds to default value`() {
        val field = IrField("len", IrType.INT, isStatic = false, isFinal = false)
        val guarded = IrExpr.NullGuard(IrExpr.FieldRead(IrExpr.Const(null), field))
        val body = optimize(
            listOf(IrStmt.LocalAssign(local0, guarded), IrStmt.Return(IrExpr.LocalRead(local0))),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.Const(0)), IrStmt.Return(IrExpr.LocalRead(local0))),
            body,
        )
    }

    @Test
    fun `dead local assignment removed when unused`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(1)),
                IrStmt.Return(null),
            ),
        )
        assertEquals(listOf(IrStmt.Return(null)), body)
    }

    @Test
    fun `logical not on constant folds`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Not(IrExpr.Const(false))),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.Const(true)), IrStmt.Return(IrExpr.LocalRead(local0))),
            body,
        )
    }

    @Test
    fun `constant compare folds to boolean`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Compare(CompareOp.LT, IrExpr.Const(1), IrExpr.Const(2))),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.Const(true)), IrStmt.Return(IrExpr.LocalRead(local0))),
            body,
        )
    }

    @Test
    fun `used local assignment is kept`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(1)),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.Const(1)), IrStmt.Return(IrExpr.LocalRead(local0))),
            body,
        )
    }

    @Test
    fun `call with side effects is never removed`() {
        val target = yux.compiler.ir.IrJvmCall("print", "yux.core.CoreLib", static = true, params = listOf(IrType.ANY), retType = IrType.Void)
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Invoke(target, null, listOf(IrExpr.Const("hi")))),
                IrStmt.Return(null),
            ),
        )
        // Invoke 有副作用：赋值保留（返回后不可达语句被移除，但赋值在 Return 前）
        assertTrue(body[0] is IrStmt.LocalAssign, "副作用调用不得被删除: $body")
    }

    @Test
    fun `branch with equal targets is removed`() {
        val l1 = IrLabel("L1")
        val body = optimize(
            listOf(
                IrStmt.Branch(IrExpr.Const(true), l1, l1),
                IrStmt.Label(l1),
                IrStmt.Return(null),
            ),
        )
        assertFalse(body.any { it is IrStmt.Branch }, "同目标分支应删除: $body")
    }

    @Test
    fun `min value div and mod by minus one fold per jvm semantics`() {
        // JVM 上 MIN_VALUE / -1 == MIN_VALUE、MIN_VALUE % -1 == 0（不抛异常，可折叠）
        val div = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Arith(ArithOp.DIV, IrExpr.Const(Int.MIN_VALUE), IrExpr.Const(-1))),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.Const(Int.MIN_VALUE)), IrStmt.Return(IrExpr.LocalRead(local0))),
            div,
        )
        val mod = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Arith(ArithOp.MOD, IrExpr.Const(Int.MIN_VALUE), IrExpr.Const(-1))),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(local0, IrExpr.Const(0)), IrStmt.Return(IrExpr.LocalRead(local0))),
            mod,
        )
    }

    @Test
    fun `long min value fold keeps exact semantics`() {
        val longLocal = IrLocal("l", IrType.LONG, 0)
        val div = optimize(
            listOf(
                IrStmt.LocalAssign(longLocal, IrExpr.Arith(ArithOp.DIV, IrExpr.Const(Long.MIN_VALUE), IrExpr.Const(-1L))),
                IrStmt.Return(IrExpr.LocalRead(longLocal)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(longLocal, IrExpr.Const(Long.MIN_VALUE)), IrStmt.Return(IrExpr.LocalRead(longLocal))),
            div,
        )
    }

    @Test
    fun `long compare near max keeps exact ordering`() {
        // Long 必须保留原类型比较：转 Double 会丢失 Long.MAX_VALUE 附近精度
        val boolLocal = IrLocal("b", IrType.BOOLEAN, 0)
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(boolLocal, IrExpr.Compare(CompareOp.GT, IrExpr.Const(Long.MAX_VALUE), IrExpr.Const(Long.MAX_VALUE - 1))),
                IrStmt.Return(IrExpr.LocalRead(boolLocal)),
            ),
        )
        assertEquals(
            listOf(IrStmt.LocalAssign(boolLocal, IrExpr.Const(true)), IrStmt.Return(IrExpr.LocalRead(boolLocal))),
            body,
        )
    }
}
