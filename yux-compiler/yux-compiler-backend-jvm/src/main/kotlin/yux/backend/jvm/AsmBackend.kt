package yux.backend.jvm

import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.IrModule

/**
 * JVM 后端入口（T-M5-1 / 06-§9.2 的 AsmBackend）：IrModule → `.class` 字节。
 *
 * 02-§13.1 的 Backend 接口形态：输入 IR，输出产物清单（类名 → 字节）。
 * 并发语义（S-8.4，T-M11-2）：`async fun` 声明仍以同步方法编译（REMIND 提示）；
 * `async { }`/`parallel { }` 块在 IRGen 编译为 Tasks.launch/parallelAll 调用（真并发）。
 */
class AsmBackend(
    /** 项目类解析加载器（M10 混合项目：Java/Kotlin 产物目录的 URLClassLoader）；默认编译管线自身加载器。 */
    private val classLoader: ClassLoader = JvmDescResolver::class.java.classLoader,
) {

    /** 编译产物。 */
    data class OutputArtifact(
        val className: String,
        val bytes: ByteArray,
    )

    fun generate(module: IrModule, diagnostics: DiagnosticSink = DiagnosticSink()): List<OutputArtifact> {
        val emitter = AsmEmitter(module, classLoader)
        val artifacts = mutableListOf<OutputArtifact>()
        for (cls in module.classes) {
            // `async fun` 声明：方法体照常同步发射 + REMIND（块级 `async {}` 已由 IRGen 降级为 Tasks.launch）
            for (method in cls.methods) {
                if (method.isAsync) {
                    diagnostics.remind("async fun 声明同步执行（S-8.4）：`${cls.name}.${method.name}` 方法体不挂起；块级 `async {}` 经 yux.async.Tasks.launch 真并发")
                }
            }
            artifacts += try {
                OutputArtifact(cls.name, emitter.emitClass(cls))
            } catch (e: RuntimeException) {
                throw RuntimeException("ASM 生成失败: 类 ${cls.name}: ${e.message}", e)
            }
        }
        return artifacts
    }
}
