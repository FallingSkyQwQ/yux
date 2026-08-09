package yux.backend.jvm

import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.IrModule

/**
 * JVM 后端入口（T-M5-1 / 06-§9.2 的 AsmBackend）：IrModule → `.class` 字节。
 *
 * 02-§13.1 的 Backend 接口形态：输入 IR，输出产物清单（类名 → 字节）。
 * async 降级（R3/02-§9.1）：v0.1 同步执行 + REMIND 诊断（T-M5-9）。
 */
class AsmBackend {

    /** 编译产物。 */
    data class OutputArtifact(
        val className: String,
        val bytes: ByteArray,
    )

    fun generate(module: IrModule, diagnostics: DiagnosticSink = DiagnosticSink()): List<OutputArtifact> {
        val emitter = AsmEmitter(module)
        val artifacts = mutableListOf<OutputArtifact>()
        for (cls in module.classes) {
            // T-M5-9（R3）：async 同步降级——方法体照常同步发射 + REMIND
            for (method in cls.methods) {
                if (method.isAsync) {
                    diagnostics.remind("async 同步降级（R3）：`${cls.name}.${method.name}` 在 v0.1 同步执行，真正协程 M11")
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
