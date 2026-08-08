package yux.buildtool

import yux.backend.jvm.AsmBackend
import yux.compiler.Compiler
import yux.compiler.ast.YxDecl
import yux.compiler.diag.DiagnosticSink
import yux.compiler.plugin.PluginManager
import yux.compiler.source.SourceFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

/** 完整构建结果（T-M7-5 `yuxc build`）。 */
data class BuildResult(
    /** 是否成功。 */
    val success: Boolean,
    /** 编译诊断（含警告/REMIND）。 */
    val diagnostics: DiagnosticSink = DiagnosticSink(),
    /** 最终产物 jar：`build/libs/<name>-<version>.jar`（打包成功时）。 */
    val artifactJar: Path? = null,
    /** 本次是否执行了 Yux 编译（false = 增量命中跳过）。 */
    val compiled: Boolean = false,
    /** 失败原因 / 提示。 */
    val message: String? = null,
)

/** 编译结果（T-M7-5 `yuxc run -p` 进程内运行用）。 */
data class CompiledProject(
    /** 类名 → 字节码（编译产物）。 */
    val classes: Map<String, ByteArray>,
    /** 主类名（`config.mainClass ?: "Main"`）。 */
    val mainClassName: String,
    /** 编译诊断。 */
    val diagnostics: DiagnosticSink,
    /** 是否成功。 */
    val success: Boolean,
    /** true = 增量命中（未重新编译，产物从磁盘加载）。 */
    val upToDate: Boolean,
)

/**
 * 构建编排器（T-M7-3/5/7，02-§11 流水线）：解析 build.yml → 编译计划 → 增量检查 →
 * Yux 编译 → 生成 Gradle 工程 → 托管打包 → 拷贝 jar 到 `build/libs`。
 *
 * 增量行为（T-M7-6）：源文件未变更时跳过 Yux 编译与 Gradle 调用，直接复用既有 jar；
 * 变更任一源文件或 `--clean` 时走全量编译。
 */
class YuxBuild(
    private val projectDir: Path,
    private val pluginManager: PluginManager = PluginManager(),
    private val gradleBinary: String? = null,
    private val compilerVersion: String = YuxCache.COMPILER_VERSION,
) {

    /** 完整构建（T-M7-5 `yuxc build`）：解析 → 计划 → 增量 → Yux 编译 → 生成 Gradle → 托管打包 → 拷贝 jar。 */
    fun build(clean: Boolean = false, offline: Boolean = false): BuildResult {
        val plan = parseAndPlan()
        if (plan == null) return BuildResult(false, message = lastParseError)
        if (clean) {
            try {
                cleanArtifacts()
            } catch (e: IOException) {
                return BuildResult(false, message = "清理构建产物失败: ${e.message}")
            }
        }
        val compiled = compileInternal(plan, plan.config, clean)
        if (!compiled.success) {
            return BuildResult(false, compiled.diagnostics, compiled = true, message = "Yux 编译失败")
        }
        val generatedDir = BuildPaths.resolve(projectDir, BuildPaths.GENERATED_DIR)
        try {
            writeGradleProject(plan.config, generatedDir)
        } catch (e: IOException) {
            return BuildResult(false, message = "生成 Gradle 工程失败: ${e.message}")
        }
        val targetJar = BuildPaths.resolve(projectDir, BuildPaths.LIBS_DIR)
            .resolve("${plan.config.name}-${plan.config.version}.jar")
        // 增量命中且 jar 已存在 → 跳过 Gradle（无变更快速重建的可观测行为）。
        if (compiled.upToDate && Files.isRegularFile(targetJar)) {
            return BuildResult(true, compiled = false, artifactJar = targetJar)
        }
        val gradle = GradleRunner(gradleBinary, offline).run(generatedDir, "jar")
        if (gradle.exitCode != 0) {
            return BuildResult(false, message = "Gradle 构建失败（exit ${gradle.exitCode}）:\n" + gradle.output.takeLast(2000))
        }
        return copyArtifact(generatedDir, targetJar)
    }

    /** 仅编译 Yux 源码（增量感知），供 `yuxc run -p` 进程内运行。 */
    fun compile(clean: Boolean = false): CompiledProject {
        val plan = parseAndPlan()
        if (plan == null) return compileFailure(lastParseError ?: "build.yml 解析失败")
        if (clean) {
            try {
                cleanArtifacts()
            } catch (e: IOException) {
                return compileFailure("清理构建产物失败: ${e.message}")
            }
        }
        val compiled = compileInternal(plan, plan.config, clean)
        if (!compiled.success || !compiled.upToDate) return compiled
        // 增量命中：从 build/yux-classes 重新加载类；加载失败则强制重编。
        val reloaded = loadClasses()
        if (reloaded != null) {
            return CompiledProject(reloaded, plan.mainClassName, compiled.diagnostics, success = true, upToDate = true)
        }
        return compileInternal(plan, plan.config, clean = true)
    }

    /** 解析 build.yml 并编制计划；失败返回 null（原因记入 [lastParseError]）。 */
    private fun parseAndPlan(): BuildPlan? {
        val config = try {
            BuildYmlParser().parse(projectDir)
        } catch (e: BuildConfigException) {
            lastParseError = e.message
            return null
        }
        val plan = try {
            BuildPlanner().plan(projectDir, config)
        } catch (e: BuildConfigException) {
            lastParseError = e.message
            return null
        }
        return plan
    }

    /**
     * 增量感知的 Yux 编译（T-M7-3/5/6）：缓存命中 → 直接返回 upToDate 结果；
     * 否则解析全部源文件 → 写 yux-symbols.json（T-M7-7）→ 语义分析 → IR 生成 →
     * JVM 后端 + 插件钩子 → 写字节码 → 更新缓存。
     */
    private fun compileInternal(plan: BuildPlan, config: BuildConfig, clean: Boolean): CompiledProject {
        val cache = YuxCache(projectDir)
        if (!clean && cache.isUpToDate(plan.yuxSources, compilerVersion)) {
            return CompiledProject(emptyMap(), plan.mainClassName, DiagnosticSink(), success = true, upToDate = true)
        }
        val compiler = Compiler(DiagnosticSink(), pluginManager)
        // 跨文件引用：先解析全部源文件，再统一 analyze/generate。
        val declsByFile = linkedMapOf<String, List<YxDecl>>()
        for (sourcePath in plan.yuxSources) {
            val text = try {
                Files.readString(sourcePath)
            } catch (e: IOException) {
                compiler.diagnostics.error("读取源码失败: $sourcePath: ${e.message}")
                continue
            }
            val source = SourceFile(sourcePath.toString(), text)
            declsByFile[source.path] = compiler.parseToDecls(source)
        }
        if (compiler.diagnostics.hasErrors) {
            return CompiledProject(emptyMap(), plan.mainClassName, compiler.diagnostics, success = false, upToDate = false)
        }
        // T-M7-7：IDE 映射产物（解析通过后写入，即使后续阶段失败）。
        writeSymbols(config, declsByFile, compiler.diagnostics)
        if (compiler.diagnostics.hasErrors) {
            return CompiledProject(emptyMap(), plan.mainClassName, compiler.diagnostics, success = false, upToDate = false)
        }
        val analysis = compiler.analyze(declsByFile)
        if (compiler.diagnostics.hasErrors) {
            return CompiledProject(emptyMap(), plan.mainClassName, compiler.diagnostics, success = false, upToDate = false)
        }
        val module = compiler.generate(declsByFile, analysis)
        val artifacts = AsmBackend().generate(module, compiler.diagnostics).toMutableList()
        // T-M6-5：插件 CodegenHook 附加产物（与 CLI Runner.kt 的合并模式一致）。
        for (hook in pluginManager.hooks) {
            for (artifact in hook.generate(module, compiler.diagnostics)) {
                artifacts += AsmBackend.OutputArtifact(artifact.name, artifact.bytes)
            }
        }
        if (compiler.diagnostics.hasErrors) {
            return CompiledProject(emptyMap(), plan.mainClassName, compiler.diagnostics, success = false, upToDate = false)
        }
        try {
            writeClasses(BuildPaths.resolve(projectDir, BuildPaths.YUX_CLASSES_DIR), artifacts)
            cache.save(plan.yuxSources, compilerVersion)
        } catch (e: IOException) {
            compiler.diagnostics.error("写入编译产物失败: ${e.message}")
            return CompiledProject(emptyMap(), plan.mainClassName, compiler.diagnostics, success = false, upToDate = false)
        }
        return CompiledProject(
            classes = artifacts.associate { it.className to it.bytes },
            mainClassName = plan.mainClassName,
            diagnostics = compiler.diagnostics,
            success = true,
            upToDate = false,
        )
    }

    /** 生成 Gradle 工程：源路径一律为绝对路径（生成工程从 build/generated 运行）。 */
    private fun writeGradleProject(config: BuildConfig, generatedDir: Path) {
        val absConfig = config.copy(
            sourceJava = config.sourceJava?.let { BuildPaths.resolve(projectDir, it).toString() },
            sourceKotlin = config.sourceKotlin?.let { BuildPaths.resolve(projectDir, it).toString() },
            resourcesDir = BuildPaths.resolve(projectDir, config.resourcesDir).toString(),
        )
        val generated = GradleGenerator().generate(
            absConfig,
            yuxClassesDir = BuildPaths.resolve(projectDir, BuildPaths.YUX_CLASSES_DIR).toString(),
        )
        Files.createDirectories(generatedDir)
        Files.writeString(generatedDir.resolve("settings.gradle"), generated.settingsGradle)
        Files.writeString(generatedDir.resolve("build.gradle.kts"), generated.buildGradleKts)
        val pluginYml = generated.pluginYml
        if (pluginYml != null) {
            val pluginFile = generatedDir.resolve("src/main/resources/plugin.yml")
            Files.createDirectories(pluginFile.parent)
            Files.writeString(pluginFile, pluginYml)
        }
    }

    /** 拷贝 Gradle 产出的 jar 到 `build/libs/<name>-<version>.jar`；无产物 → 失败结果。 */
    private fun copyArtifact(generatedDir: Path, targetJar: Path): BuildResult {
        val sourceJar = try {
            Files.list(generatedDir.resolve("build/libs")).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }
                    .sorted(compareBy { it.fileName.toString() })
                    .toList()
                    .firstOrNull()
            }
        } catch (e: IOException) {
            return BuildResult(false, message = "Gradle 未产出 jar")
        }
        if (sourceJar == null) {
            return BuildResult(false, message = "Gradle 未产出 jar")
        }
        return try {
            Files.createDirectories(targetJar.parent)
            Files.copy(sourceJar, targetJar, StandardCopyOption.REPLACE_EXISTING)
            BuildResult(true, compiled = true, artifactJar = targetJar)
        } catch (e: IOException) {
            BuildResult(false, message = "拷贝 jar 失败: ${e.message}")
        }
    }

    /** 写 `yux-symbols.json`；失败记录为诊断错误。 */
    private fun writeSymbols(config: BuildConfig, declsByFile: Map<String, List<YxDecl>>, diagnostics: DiagnosticSink) {
        val symbolsFile = BuildPaths.resolve(projectDir, BuildPaths.SYMBOLS_FILE)
        try {
            Files.createDirectories(symbolsFile.parent)
            Files.writeString(symbolsFile, YuxSymbols.build(projectDir, config, declsByFile))
        } catch (e: IOException) {
            diagnostics.error("写入 yux-symbols.json 失败: ${e.message}")
        }
    }

    /** 写字节码到 `build/yux-classes/<className.replace('.', '/')>.class`。 */
    private fun writeClasses(classesDir: Path, artifacts: List<AsmBackend.OutputArtifact>) {
        for (artifact in artifacts) {
            val classFile = classesDir.resolve(artifact.className.replace('.', '/') + ".class")
            Files.createDirectories(classFile.parent)
            Files.write(classFile, artifact.bytes)
        }
    }

    /** 从磁盘 `build/yux-classes` 加载全部类；目录缺失或读取失败 → null。 */
    private fun loadClasses(): Map<String, ByteArray>? {
        val classesDir = BuildPaths.resolve(projectDir, BuildPaths.YUX_CLASSES_DIR)
        if (!Files.isDirectory(classesDir)) return null
        return try {
            Files.walk(classesDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .toList()
                    .associate { file ->
                        val rel = classesDir.relativize(file)
                        val className = rel.toString().replace('\\', '/').substringBeforeLast('.').replace('/', '.')
                        className to Files.readAllBytes(file)
                    }
            }
        } catch (e: IOException) {
            null
        }
    }

    /** 递归删除（不存在时静默）。 */
    private fun cleanArtifacts() {
        for (path in listOf(
            BuildPaths.resolve(projectDir, BuildPaths.CACHE_DIR),
            BuildPaths.resolve(projectDir, BuildPaths.YUX_CLASSES_DIR),
            BuildPaths.resolve(projectDir, BuildPaths.GENERATED_DIR),
            BuildPaths.resolve(projectDir, BuildPaths.LIBS_DIR),
            BuildPaths.resolve(projectDir, BuildPaths.SYMBOLS_FILE),
        )) {
            deleteRecursively(path)
        }
    }

    /** 删除单个文件或目录树（不存在时静默）。 */
    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** 构造带错误诊断的编译失败结果。 */
    private fun compileFailure(message: String): CompiledProject {
        val sink = DiagnosticSink()
        sink.error(message)
        return CompiledProject(emptyMap(), "Main", sink, success = false, upToDate = false)
    }

    /** 最近一次 [parseAndPlan] 失败原因。 */
    private var lastParseError: String? = null
}
