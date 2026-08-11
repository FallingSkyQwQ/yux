package yux.compiler.sema

import yux.compiler.lexer.SourcePosition
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes

/**
 * 局部双向推断（T-M3-5 / ADR-08，02-§7.2）。
 *
 * 实现为「约束收集 + 单次统一（unify）」：推断变量（[SemaType.InferenceVar]）
 * 由上下文（期望类型/初始化表达式）双向收紧，冲突即报 [ErrorCodes.TYPE_MISMATCH]。
 * 不做全局 HM，不猜测 Lambda 参数类型（S-4.5.3）。
 */
class Inference(private val diagnostics: DiagnosticSink) {
    private var varCounter = 0

    /** 新建推断变量 `?T`. */
    fun freshVar(): SemaType.InferenceVar = SemaType.InferenceVar(++varCounter)

    /**
     * 统一两个类型；返回公共类型。
     *
     * - 推断变量：绑定对方（若为字面量/具体类型优先解析字面量）；
     * - `Nothing` 与任意类型统一；
     * - 同底类型：可空性取「任一可空则可空」；
     * - 不同底类型：返回 null（调用方报错）。
     */
    fun unify(a: SemaType, b: SemaType): SemaType? {
        val av = a.resolveInferenceVar()
        val bv = b.resolveInferenceVar()
        if (av === bv) return av
        if (av is SemaType.InferenceVar && bv is SemaType.InferenceVar) {
            if (av.id == bv.id) return av
            av.solution = bv
            return bv
        }
        if (av is SemaType.InferenceVar) { av.solution = bv; return bv }
        if (bv is SemaType.InferenceVar) { bv.solution = av; return av }
        if (av.isError || bv.isError) return SemaType.ErrorT
        if (av is SemaType.NothingT) return bv
        if (bv is SemaType.NothingT) return av
        if (TypeAssignability.sameBase(av, bv)) {
            return if (av.nullable || bv.nullable) av.nonNull().asNullable() else av.nonNull()
        }
        return null
    }

    /**
     * 赋值兼容检查：`actual` 必须可赋值给 `expected`；失败报告 E0004。
     * [context] 用于诊断文案（如「变量声明」「实参 2」）。
     *
     * 具体类型之间走严格可赋值性（含可空性：可空 → 非空不允许，S-4.2）；
     * 涉及推断变量时交给 [unify]（约束收集，02-§7.2）。
     */
    fun expectAssignable(
        actual: SemaType,
        expected: SemaType,
        position: SourcePosition?,
        context: String,
    ): SemaType {
        val result = checkAssignable(actual, expected, position, context, ErrorCodes.TYPE_MISMATCH)
        return result ?: SemaType.ErrorT
    }

    /**
     * 返回类型兼容检查：语义同 [expectAssignable]，但失败报 E0015（S-5.5.2/6.5.1）。
     */
    fun expectReturn(
        actual: SemaType,
        expected: SemaType,
        position: SourcePosition?,
        context: String,
    ): SemaType {
        val result = checkAssignable(actual, expected, position, context, ErrorCodes.RETURN_TYPE_MISMATCH)
        return result ?: SemaType.ErrorT
    }

    private fun checkAssignable(
        actual: SemaType,
        expected: SemaType,
        position: SourcePosition?,
        context: String,
        code: String,
    ): SemaType? {
        if (actual.isError || expected.isError) return SemaType.ErrorT
        // 自引用保护：禁止 `V.solution = V`（S-4.5.4）
        if (actual === expected || (actual is SemaType.InferenceVar && expected is SemaType.InferenceVar && actual.id == expected.id)) {
            return actual
        }
        // 推断变量：约束收集（S-4.5.4 环防护：expected 已（传递）求解回 actual 时不再绑定）。
        // 缺陷修复：actual 已求解时不得被调用点约束覆盖——`println a.speak()`（参数 Any）
        // 曾把已由函数体解出的 String 顶成 Any，导致后续 `speak().length` 报"成员不存在"。
        // 现按既有解参与检查（验证兼容性），而非重绑。
        if (actual is SemaType.InferenceVar) {
            val resolvedActual = actual.resolveInferenceVar()
            if (resolvedActual !== actual) {
                return checkAssignable(resolvedActual, expected, position, context, code)
            }
            val resolvedExpected = expected.resolveInferenceVar()
            if (resolvedExpected === actual) return actual
            actual.solution = resolvedExpected
            return expected
        }
        if (expected is SemaType.InferenceVar) {
            val unified = unify(actual, expected)
            if (unified != null) return unified
        }
        if (TypeAssignability.isAssignable(actual, expected)) return expected
        diagnostics.error(
            "类型不匹配（$context）: 期望 ${expected.render()}，实际 ${actual.render()}",
            position,
            code,
        )
        return null
    }

    /** 推断失败报告（S-4.5.4）：不猜测类型，给出定位。 */
    fun reportInferenceFailure(position: SourcePosition?, detail: String) {
        diagnostics.error("类型推断失败: $detail", position, ErrorCodes.INFERENCE_FAILURE)
    }

    /**
     * 追踪推断变量解链（S-4.5.4，含环检测，镜像 [SemaType.resolveVar]）：
     * 遇环返回 [SemaType.ErrorT]，避免无限递归 → StackOverflow。
     */
    private fun SemaType.resolveInferenceVar(seen: MutableSet<Int> = mutableSetOf()): SemaType = when (this) {
        is SemaType.InferenceVar -> {
            val s = solution
            if (s == null) {
                this
            } else if (!seen.add(id)) {
                SemaType.ErrorT
            } else {
                s.resolveInferenceVar(seen)
            }
        }
        else -> this
    }
}
