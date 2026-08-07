package yux.compiler.sema

import yux.compiler.ast.YxBinary
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxNullLiteral
import yux.compiler.ast.YxUnary

/**
 * 智能转型环境（T-M3-6 / 02-§7.3）。
 *
 * 规则（01-S-6.3.1 / 02-§7.3）：
 * - `is T` 分支、`!= null` 分支内，变量视为转型后类型；
 * - 仅**不可变引用**（声明后未重新赋值）允许转型；可变引用 → REMIND（R0002）且不转型。
 *
 * 实现：单一 `name → type` 覆盖映射，配合 `enterBlock/exitBlock`（块内转型不外泄）
 * 与 `snapshot/restore`（if/else 分支合并，只保留两分支共同成立的转型）。
 */
class SmartCast {
    private val casts = mutableMapOf<String, SemaType>()
    private val saveStack = ArrayDeque<Map<String, SemaType>>()

    /** 当前变量被覆盖为的类型；无覆盖返回 null。 */
    fun castedType(name: String): SemaType? = casts[name]

    fun register(name: String, type: SemaType) {
        casts[name] = type
    }

    /** 进入块：保存当前覆盖，块内注册不外泄。 */
    fun enterBlock() {
        saveStack.addLast(casts.toMap())
    }

    fun exitBlock() {
        casts.clear()
        casts.putAll(saveStack.removeLast())
    }

    fun snapshot(): Map<String, SemaType> = casts.toMap()

    fun restore(map: Map<String, SemaType>) {
        casts.clear()
        casts.putAll(map)
    }

    /**
     * `x is T` 转型：将 [name] 注册为 [castType]。
     * 返回是否已转型（供调用方判断是否需要 R0002）。
     */
    fun applyIsCast(name: String, castType: SemaType, isReassigned: (String) -> Boolean): Boolean {
        if (isReassigned(name)) return false
        register(name, castType)
        return true
    }

    /**
     * 空值判空转型（S-8.1）：从条件提取「非空分支」的变量名并注册为声明的非空版本。
     * 支持 `x != null`（then）、`x == null`（else）、`!(x == null)`（then）。
     */
    fun applyNullCondition(
        condition: YxExpr,
        thenBranch: Boolean,
        declaredType: (String) -> SemaType?,
        isReassigned: (String) -> Boolean,
        onMutableCast: (String) -> Unit,
    ) {
        val name = nullCastTarget(condition, thenBranch) ?: return
        val type = declaredType(name) ?: return
        if (type.isError) return
        if (isReassigned(name)) {
            onMutableCast(name)
            return
        }
        register(name, type.nonNull())
    }

    /** 解析空值判空条件 → 应转型为「非空」的变量名（无则 null）。 */
    private fun nullCastTarget(condition: YxExpr, thenBranch: Boolean): String? = when (condition) {
        is YxBinary -> {
            val name = extractNameVsNull(condition)
            when (condition.op) {
                "!=" -> if (thenBranch) name else null
                "==" -> if (!thenBranch) name else null
                else -> null
            }
        }
        is YxUnary -> {
            val inner = condition.operand
            if (condition.op == "!" && inner is YxBinary) {
                if (inner.op == "==" && thenBranch) extractNameVsNull(inner) else null
            } else {
                null
            }
        }
        else -> null
    }

    /** 形如 `id == null` / `null == id` 的变量名；否则 null。 */
    private fun extractNameVsNull(binary: YxBinary): String? {
        val left = binary.left
        val right = binary.right
        return when {
            left is YxIdentifier && right is YxNullLiteral -> left.name
            right is YxIdentifier && left is YxNullLiteral -> right.name
            else -> null
        }
    }
}
