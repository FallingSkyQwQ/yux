package yux.compiler.lowering

import yux.compiler.ast.SourceSpan
import yux.compiler.ast.YxMemberAccess
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes
import yux.compiler.sema.SemaType

/** 空安全守卫插入点（T-M3-7 产物，供 M4 Lowering 消费）。 */
class GuardPoint(
    val node: YxMemberAccess,
    val receiverType: SemaType,
    val span: SourceSpan,
)

/**
 * 空安全守卫分析（T-M3-7 / 01-S-8.1 / 02-§7.4、§9.4）。
 *
 * 规则（S-8.1）：
 * - 可空接收者**读路径** `.b` 自动插入守卫（`if(a != null) a.b else 默认值`），
 *   同时产生 REMIND（R0001）提示开发者显式处理；
 * - **写路径**（`a.b = x`）与副作用调用不守卫（E0023 提示显式判空由 TypeChecker 负责）。
 *
 * 本阶段仅完成「判定 + 记录插入点 + 诊断」；真正生成 `ifnonnull` IR 在 M4/M5。
 */
class NullGuard(private val diagnostics: DiagnosticSink) {
    val guardPoints = mutableListOf<GuardPoint>()

    /** 判定一次成员读取是否需要守卫；需要则记录并报告 R0001。 */
    fun checkMemberRead(access: YxMemberAccess, receiverType: SemaType, isPropertyRead: Boolean) {
        if (isPropertyRead && receiverType.nullable && !receiverType.isError) {
            guardPoints += GuardPoint(access, receiverType, access.span)
            diagnostics.remind(
                "可空接收者 '${receiverType.render()}' 的属性读取已插入空安全守卫（S-8.1）",
                access.span.start,
                ErrorCodes.NULL_GUARD_INSERTED,
            )
        }
    }

    /** 写路径不守卫（S-8.1）：由 TypeChecker 在赋值目标处报告 E0023。 */
    fun reportWriteOnNullable(access: YxMemberAccess, receiverType: SemaType) {
        if (receiverType.nullable && !receiverType.isError) {
            diagnostics.error(
                "可空接收者 '${receiverType.render()}' 的写路径需显式判空（S-8.1）",
                access.span.start,
                ErrorCodes.NULLABLE_WRITE_NEEDS_CHECK,
            )
        }
    }

    /**
     * 取值方法调用守卫（02-§7.4）：返回非 Unit 的方法调用视为「读取」，
     * 可空接收者上调用时插入守卫并 R0001（副作用调用不守卫）。
     */
    fun checkMethodCall(access: YxMemberAccess, receiverType: SemaType, returnType: SemaType) {
        if (receiverType.nullable && !receiverType.isError && !returnType.isError && returnType !is SemaType.UnitT) {
            guardPoints += GuardPoint(access, receiverType, access.span)
            diagnostics.remind(
                "可空接收者 '${receiverType.render()}' 的方法调用已插入空安全守卫（S-8.1）",
                access.span.start,
                ErrorCodes.NULL_GUARD_INSERTED,
            )
        }
    }
}
