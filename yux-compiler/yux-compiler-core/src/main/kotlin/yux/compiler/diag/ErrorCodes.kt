package yux.compiler.diag

/**
 * 诊断码常量（T-M3-10）。唯一事实源为同目录 `ErrorCodes.md`。
 */
object ErrorCodes {
    // ── E 系列：编译错误 ──────────────────────────────────────────────────────
    const val UNRESOLVED_REFERENCE = "E0001"
    const val DUPLICATE_DECLARATION = "E0002"
    const val CONDITION_NOT_BOOLEAN = "E0003"
    const val TYPE_MISMATCH = "E0004"
    const val TYPE_ARGUMENT_COUNT_MISMATCH = "E0005"
    const val ARGUMENT_COUNT_MISMATCH = "E0006"
    const val VARIABLE_NOT_INITIALIZED = "E0007"
    const val PARAMETER_TYPE_MISSING = "E0008"
    const val UNRESOLVED_TYPE = "E0009"
    const val ILLEGAL_ACCESSOR = "E0010"
    const val ILLEGAL_DATA_MEMBER = "E0011"
    const val SERVICE_CYCLE = "E0012"
    const val INVALID_ANNOTATION_TARGET = "E0013"
    const val INFERENCE_FAILURE = "E0014"
    const val RETURN_TYPE_MISMATCH = "E0015"
    const val UNRESOLVED_MEMBER = "E0016"
    const val INVALID_OPERATOR_OPERAND = "E0017"
    const val THIS_OUTSIDE_CLASS = "E0018"
    const val OVERRIDE_NO_SUPER = "E0019"
    const val VAL_ASSIGNMENT = "E0020"
    const val BREAK_OUTSIDE_LOOP = "E0021"
    const val LOOP_ITERABLE_INVALID = "E0022"
    const val NULLABLE_WRITE_NEEDS_CHECK = "E0023"
    const val SUPER_OUTSIDE_CLASS = "E0024"
    const val DUPLICATE_OVERRIDE = "E0025"
    const val SERVICE_PROPERTY_NO_INIT = "E0026"
    const val AMBIGUOUS_REFERENCE = "E0027"
    const val ILLEGAL_ASSIGN_TARGET = "E0028"

    // ── W 系列：警告 ──────────────────────────────────────────────────────────
    const val TYPE_FALLBACK = "W0001"
    const val UNUSED_DECLARATION = "W0002"

    // ── R 系列：REMIND ────────────────────────────────────────────────────────
    const val NULL_GUARD_INSERTED = "R0001"
    const val SMART_CAST_MUTABLE = "R0002"
}
