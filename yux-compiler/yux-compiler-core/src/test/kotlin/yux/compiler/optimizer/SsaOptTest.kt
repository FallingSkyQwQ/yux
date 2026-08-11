package yux.compiler.optimizer

import org.junit.jupiter.api.Test
import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrParam
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 完整 SSA 优化器单测：纯 IR 构造（不依赖解析器/IRGen）。
 * 覆盖 CFG/φ/SCCP/拷贝传播/平凡 φ/激进 DCE/φ 销毁（边分裂）。
 */
class SsaOptTest {

    private val local0 = IrLocal("x", IrType.INT, 0)
    private val local1 = IrLocal("y", IrType.INT, 1)
    private val bool0 = IrLocal("b", IrType.BOOLEAN, 0)
    private val p0 = IrLocal("p", IrType.INT, 0)

    private fun buildMethod(params: List<IrParam> = emptyList()): IrMethod {
        val cls = IrClass("Main", isFileClass = true, isData = false, isService = false, superType = null, interfaces = emptyList())
        return IrMethod(
            name = "main",
            params = params,
            returnType = IrType.Void,
            isStatic = true,
            isConstructor = false,
            isAsync = false,
            isOverride = false,
            isSynthetic = false,
            owner = cls,
        )
    }

    private fun optimize(body: List<IrStmt>, params: List<IrParam> = emptyList()): List<IrStmt> {
        val method = buildMethod(params)
        method.body.addAll(body)
        method.owner!!.methods.add(method)
        SsaOpt.optimize(IrModule(mutableListOf(method.owner!!)))
        return method.body
    }

    @Test
    fun `constant propagates across blocks into both branches`() {
        val l1 = IrLabel("L1")
        val l2 = IrLabel("L2")
        val l3 = IrLabel("L3")
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(5)),
                IrStmt.Branch(IrExpr.LocalRead(bool0), l1, l2),
                IrStmt.Label(l1),
                IrStmt.LocalAssign(local1, IrExpr.Arith(ArithOp.ADD, IrExpr.LocalRead(local0), IrExpr.Const(1))),
                IrStmt.Goto(l3),
                IrStmt.Label(l2),
                IrStmt.LocalAssign(local1, IrExpr.Arith(ArithOp.ADD, IrExpr.LocalRead(local0), IrExpr.Const(2))),
                IrStmt.Goto(l3),
                IrStmt.Label(l3),
                IrStmt.Return(IrExpr.LocalRead(local1)),
            ),
            params = listOf(IrParam("b", IrType.BOOLEAN)),
        )
        // x=5 的赋值被消除（传播后无使用）；两分支中 y 折叠为 6/7
        val assigns = body.filterIsInstance<IrStmt.LocalAssign>()
        assertFalse(body.any { it is IrStmt.LocalAssign && it.value == IrExpr.Const(5) }, "x=5 应被传播消除: $body")
        assertTrue(assigns.any { it.value == IrExpr.Const(6) }, "then 分支应折叠为 6: $body")
        assertTrue(assigns.any { it.value == IrExpr.Const(7) }, "else 分支应折叠为 7: $body")
    }

    @Test
    fun `branch on constant folds and dead block removed`() {
        val l1 = IrLabel("L1")
        val l2 = IrLabel("L2")
        val body = optimize(
            listOf(
                IrStmt.Branch(IrExpr.Const(true), l1, l2),
                IrStmt.Label(l1),
                IrStmt.Return(IrExpr.Const(1)),
                IrStmt.Label(l2),
                IrStmt.Return(IrExpr.Const(2)),
            ),
        )
        assertFalse(body.any { it is IrStmt.Branch }, "常量分支应折叠为 Goto: $body")
        assertFalse(body.any { it is IrStmt.Return && it.value == IrExpr.Const(2) }, "死分支应移除: $body")
        assertTrue(body.any { it is IrStmt.Return && it.value == IrExpr.Const(1) }, "保留可达返回: $body")
    }

    @Test
    fun `phi at join is lowered to copies in predecessors`() {
        val l1 = IrLabel("L1")
        val l2 = IrLabel("L2")
        val l3 = IrLabel("L3")
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(0)),
                IrStmt.Branch(IrExpr.LocalRead(bool0), l1, l2),
                IrStmt.Label(l1),
                IrStmt.LocalAssign(local0, IrExpr.Const(1)),
                IrStmt.Goto(l3),
                IrStmt.Label(l2),
                IrStmt.LocalAssign(local0, IrExpr.Const(2)),
                IrStmt.Goto(l3),
                IrStmt.Label(l3),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
            params = listOf(IrParam("b", IrType.BOOLEAN)),
        )
        // join 处合并：两个前驱各产生目标版本拷贝；保留两条路径与返回
        val copies = body.filterIsInstance<IrStmt.LocalAssign>()
        assertTrue(copies.size >= 2, "join 应落地为前驱拷贝: $body")
        assertTrue(body.any { it is IrStmt.Return }, "应有返回: $body")
    }

    @Test
    fun `branch predecessor of join is edge split`() {
        val l1 = IrLabel("L1")
        val l3 = IrLabel("L3")
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(0)),
                IrStmt.Branch(IrExpr.LocalRead(bool0), l1, l3),
                IrStmt.Label(l1),
                IrStmt.LocalAssign(local0, IrExpr.Const(1)),
                IrStmt.Goto(l3),
                IrStmt.Label(l3),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
            params = listOf(IrParam("b", IrType.BOOLEAN)),
        )
        // Branch 前驱有 φ 拷贝 → 需边分裂块（拷贝不能落在分支两路共同执行）；
        // φ 入边来自 else 边（A→L3），故 elseLabel 重定向到分裂块
        val splitCopies = body.filterIsInstance<IrStmt.LocalAssign>().filter {
            it.value is IrExpr.LocalRead && it.value.local.index != it.local.index
        }
        assertTrue(splitCopies.isNotEmpty(), "应存在分支路径的 φ 拷贝: $body")
        val branch = body.filterIsInstance<IrStmt.Branch>().first()
        assertTrue(branch.elseLabel.name.startsWith("L\$ssa"), "分支应重定向到分裂块: ${branch.elseLabel.name}")
    }

    @Test
    fun `copy propagation removes the copy statement`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.LocalRead(p0)),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
            params = listOf(IrParam("p", IrType.INT)),
        )
        // x = p 拷贝被传播：Return 直接读 p；拷贝语句被 DCE
        assertEquals(listOf(IrStmt.Return(IrExpr.LocalRead(p0))), body)
    }

    @Test
    fun `unused cross-block assignment is removed`() {
        val l1 = IrLabel("L1")
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(1)),
                IrStmt.Branch(IrExpr.LocalRead(bool0), l1, l1),
                IrStmt.Label(l1),
                IrStmt.Return(null),
            ),
            params = listOf(IrParam("b", IrType.BOOLEAN)),
        )
        assertFalse(body.any { it is IrStmt.LocalAssign }, "无用赋值应被 DCE: $body")
    }

    @Test
    fun `loop phi keeps loop structure`() {
        val condL = IrLabel("cond")
        val bodyL = IrLabel("body")
        val endL = IrLabel("end")
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(0)),
                IrStmt.Label(condL),
                IrStmt.Branch(
                    IrExpr.Compare(CompareOp.LT, IrExpr.LocalRead(local0), IrExpr.Const(3)),
                    bodyL,
                    endL,
                ),
                IrStmt.Label(bodyL),
                IrStmt.LocalAssign(local0, IrExpr.Arith(ArithOp.ADD, IrExpr.LocalRead(local0), IrExpr.Const(1))),
                IrStmt.Goto(condL),
                IrStmt.Label(endL),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertTrue(body.any { it is IrStmt.Label && it.label == condL }, "循环头保留: $body")
        assertTrue(body.any { it is IrStmt.Goto && it.label == condL }, "回边保留: $body")
        assertTrue(body.any { it is IrStmt.Return }, "返回保留: $body")
    }

    @Test
    fun `try defines local read after reset to original`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Const(1)),
                IrStmt.Try(
                    body = listOf(
                        IrStmt.LocalAssign(local0, IrExpr.Arith(ArithOp.ADD, IrExpr.LocalRead(local0), IrExpr.Const(1))),
                    ),
                    catches = emptyList(),
                    finallyBody = null,
                ),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        // Try 前有 flush 拷贝（原寄存器初始化，值可能已折叠为常量）；Try 后读取原局部
        val flush = body.filterIsInstance<IrStmt.LocalAssign>().firstOrNull { it.local === local0 }
        assertTrue(flush != null, "Try 前应有 flush 拷贝: $body")
        val ret = body.filterIsInstance<IrStmt.Return>().first()
        assertTrue(ret.value is IrExpr.LocalRead && ret.value.local === local0, "Try 后读取原局部: $ret")
    }

    @Test
    fun `try region not self-contained is left untouched`() {
        // try 体内 Goto 逃逸到外层标签：SSA 放弃，原样保留
        val outerL = IrLabel("Lout")
        val body = listOf(
            IrStmt.Try(
                body = listOf(
                    IrStmt.Goto(outerL),
                ),
                catches = emptyList(),
                finallyBody = null,
            ),
            IrStmt.Label(outerL),
            IrStmt.Return(null),
        )
        val result = optimize(body)
        assertEquals(body, result)
    }

    @Test
    fun `side-effecting assignment is never removed even if unused`() {
        val target = yux.compiler.ir.IrJvmCall("print", "yux.core.CoreLib", static = true, params = listOf(IrType.ANY), retType = IrType.Void)
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Invoke(target, null, listOf(IrExpr.Const("hi")))),
                IrStmt.Return(null),
            ),
        )
        assertTrue(body[0] is IrStmt.LocalAssign, "副作用赋值不得删除: $body")
    }

    @Test
    fun `division by zero is not folded but preserved`() {
        val body = optimize(
            listOf(
                IrStmt.LocalAssign(local0, IrExpr.Arith(ArithOp.DIV, IrExpr.Const(1), IrExpr.Const(0))),
                IrStmt.Return(IrExpr.LocalRead(local0)),
            ),
        )
        assertTrue(body[0] is IrStmt.LocalAssign, "除零不折叠且不得删除: $body")
        val value = (body[0] as IrStmt.LocalAssign).value
        assertTrue(value is IrExpr.Arith, "除零保留运行时语义: $value")
    }
}
