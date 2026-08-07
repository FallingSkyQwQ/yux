package yux.compiler.sema

import yux.compiler.ast.YxAnnotation
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes

/** 注解可应用的目标（01-S-8.2.1）。 */
enum class AnnotationTarget {
    CLASS,
    DATA,
    SERVICE,
    FUNCTION,
    PROPERTY,
    PARAMETER,
}

/**
 * 注解解析与目标校验（T-M3-9 / 01-§8.2）。
 *
 * 内置注解校验目标；未知注解视为 Java/Kotlin 兼容注解直接放行（S-8.2.2）。
 * `@Serializable` 的完整语义（S-8.3）由后端序列化器实现，本阶段仅校验目标。
 */
object Annotations {

    /** 内置注解 → 允许的目标集合。 */
    private val BUILTIN_TARGETS = mapOf(
        "Serializable" to setOf(AnnotationTarget.CLASS, AnnotationTarget.DATA),
        "YuxService" to setOf(AnnotationTarget.SERVICE),
        "Override" to setOf(AnnotationTarget.FUNCTION),
        "Deprecated" to setOf(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY),
    )

    fun validate(
        annotations: List<YxAnnotation>,
        targets: Set<AnnotationTarget>,
        diagnostics: DiagnosticSink,
    ) {
        for (a in annotations) {
            val name = a.qualifiedName.last()
            val allowed = BUILTIN_TARGETS[name] ?: continue
            if (allowed.none { it in targets }) {
                diagnostics.error(
                    "注解 @$name 不能应用于此目标（S-8.2.1）",
                    a.span.start,
                    ErrorCodes.INVALID_ANNOTATION_TARGET,
                )
            }
        }
    }

    fun simpleName(a: YxAnnotation): String = a.qualifiedName.last()
}
