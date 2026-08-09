package yux.buildtool

import yux.backend.jvm.AsmBackend
import yux.compiler.Compiler
import yux.compiler.ast.YxDecl
import yux.compiler.diag.DiagnosticSink
import yux.compiler.diag.ErrorCodes
import yux.compiler.plugin.PluginArtifact
import yux.compiler.plugin.PluginManager
import yux.compiler.sema.ClassPathSymbolProvider
import yux.compiler.source.SourceFile
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.net.URLClassLoader
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
    /** 非 .class 附加产物（如编译器插件的 plugin.yml，T-M8-12）；M8 起并入打包流程。 */
    val resourceArtifacts: List<PluginArtifact> = emptyList(),
    /** 项目 Java/Kotlin 编译产物目录（M10 混合项目；`run -p` 运行时需加入类加载链）。 */
    val projectClasspath: List<Path> = emptyList(),
)

/**
 * 构建编排器（T-M7-3/5/7，02-§11 流水线）：解析 build.yml → 编译计划 → 增量检查 →
 * Yux 编译 → 生成 Gradle 工程 → 托管打包 → 拷贝 jar 到 `build/libs`。
 *
 * 增量行为（T-M7-6）：源文件未变更时跳过 Yux 编译与 Gradle 调用，直接复用既有 jar；
 * 变更任一源文件或 `--clean` 时走全量编译。
 */
class YuxBuild(
    projectDir: Path,
    private val pluginManager: PluginManager = PluginManager(),
    private val gradleBinary: String? = null,
    private val compilerVersion: String = YuxCache.COMPILER_VERSION,
) {
    /** 规范化绝对路径（M9）：相对 projectDir 生成的 Gradle 脚本在 build/generated 运行时路径错位。 */
    private val projectDir: Path = projectDir.toAbsolutePath().normalize()

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
        val generatedDir = BuildPaths.resolve(projectDir, BuildPaths.GENERATED_DIR)
        val mixed = plan.hasJava || plan.hasKotlin
        // M10（05-§4）：混合项目 Gradle 脚本先行——compileMixed 以 `gradle classes` 编译
        // Java/Kotlin（Yux 编译的 classpath 与运行期依赖），脚本必须先生成。
        if (mixed) {
            val written = writeGradleProject(plan.config, generatedDir, emptyList())
            if (written != null) return BuildResult(false, message = written)
        }
        val compiled = if (mixed) {
            compileMixed(plan, generatedDir, clean, offline)
        } else {
            compileInternal(plan, plan.config, clean, emptyList())
        }
        if (!compiled.success) {
            return BuildResult(false, compiled.diagnostics, compiled = true, message = "Yux 编译失败")
        }
        // M8（T-M8-12）：编译器插件产出的 plugin.yml 在 Yux 编译后产生 → 重新生成脚本合并。
        val written = writeGradleProject(plan.config, generatedDir, compiled.resourceArtifacts)
        if (written != null) return BuildResult(false, message = written)
        val targetJar = BuildPaths.resolve(projectDir, BuildPaths.LIBS_DIR)
            .resolve("${plan.config.name}-${plan.config.version}.jar")
        // 纯 Yux 增量命中且 jar 已存在 → 跳过 Gradle（无变更快速重建的可观测行为）；
        // 混合项目恒走 Gradle `jar`（Java/Kotlin 源变更由 Gradle 自身增量编译，跳过会产出过期 jar）。
        if (!mixed && compiled.upToDate && Files.isRegularFile(targetJar)) {
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
        val generatedDir = BuildPaths.resolve(projectDir, BuildPaths.GENERATED_DIR)
        val mixed = plan.hasJava || plan.hasKotlin
        if (mixed) {
            val written = writeGradleProject(plan.config, generatedDir, emptyList())
            if (written != null) return compileFailure(written)
        }
        val compiled = if (mixed) {
            compileMixed(plan, generatedDir, clean, offline = false)
        } else {
            compileInternal(plan, plan.config, clean, emptyList())
        }
        if (!compiled.success || !compiled.upToDate) return compiled
        // 增量命中：从 build/yux-classes 重新加载类；加载失败则强制重编。
        val reloaded = loadClasses()
        if (reloaded != null) {
            return CompiledProject(
                reloaded, plan.mainClassName, compiled.diagnostics, success = true,
                upToDate = true, projectClasspath = compiled.projectClasspath,
            )
        }
        return compileInternal(plan, plan.config, clean = true, projectClasspath = compiled.projectClasspath)
    }

    /**
     * 混合项目编译编排（M10，05-§4 编译顺序）：Yux 与 Java/Kotlin 的双向互操作按依赖方向自适应——
     *
     * 1. **Yux 先行尝试**（Kotlin/Java → Yux 方向）：Yux 不引用 Java/Kotlin 类型时直接成功，
     *    随后 `gradle classes` 在 yux-classes 之上编译 Java/Kotlin（05-§5.3/5.4）。
     * 2. **回退**（Yux → Java/Kotlin 方向）：Yux 编译因未解析类型失败且项目含 Java/Kotlin 源码时，
     *    先 `gradle classes` 编译 Java/Kotlin，再以项目 classpath 重试 Yux（05-§5.1/5.2）。
     *
     * 双向同时引用（Yux↔Kotlin 循环）需统一符号收集（05-§4 未来项），v0.1 明确不支持（S-05.3 后置）。
     */
    private fun compileMixed(plan: BuildPlan, generatedDir: Path, clean: Boolean, offline: Boolean): CompiledProject {
        val gradle = GradleRunner(gradleBinary, offline)
        // 1) Yux 先行：classpath 仅含上次构建留下的 Java/Kotlin 产物（通常为空）。
        val first = compileInternal(plan, plan.config, clean, mixedClassDirs(generatedDir))
        if (first.success) {
            // 2) Java/Kotlin 编译（kotlinc/javac 经生成脚本的 files() 依赖可见 yux-classes）。
            val rc = gradle.run(generatedDir, "classes")
            if (rc.exitCode != 0) {
                return mixedCompileFailure("Java/Kotlin 编译失败（exit ${rc.exitCode}）:\n" + rc.output.takeLast(2000))
            }
            return first.copy(projectClasspath = runtimeClasspath(generatedDir))
        }
        // 3) Yux 先行失败：未解析类型错误且含 Java/Kotlin 源码 → 先编译 Java/Kotlin 再重试。
        if (hasUnresolvedTypeErrors(first.diagnostics) && (plan.hasJava || plan.hasKotlin)) {
            val rc = gradle.run(generatedDir, "classes")
            if (rc.exitCode != 0) {
                return mixedCompileFailure("Java/Kotlin 编译失败（exit ${rc.exitCode}）:\n" + rc.output.takeLast(2000))
            }
            return compileInternal(plan, plan.config, clean, mixedClassDirs(generatedDir))
                .copy(projectClasspath = runtimeClasspath(generatedDir))
        }
        return first
    }

    /** 混合项目 Java/Kotlin 编译失败结果（诊断中记录原因）。 */
    private fun mixedCompileFailure(message: String): CompiledProject {
        val sink = DiagnosticSink()
        sink.error(message)
        return CompiledProject(emptyMap(), "Main", sink, success = false, upToDate = false)
    }

    /** 诊断中是否含「未解析类型/引用/成员」错误（混合编译回退判定，M10）。 */
    private fun hasUnresolvedTypeErrors(diagnostics: DiagnosticSink): Boolean =
        diagnostics.diagnostics.any {
            it.code == ErrorCodes.UNRESOLVED_TYPE ||
                it.code == ErrorCodes.UNRESOLVED_REFERENCE ||
                it.code == ErrorCodes.UNRESOLVED_MEMBER
        }

    /** Gradle 生成的 Java/Kotlin 编译产物目录（存在者；生成工程从 build/generated 运行，产物落于其 build/classes）。 */
    private fun mixedClassDirs(generatedDir: Path): List<Path> = listOf(
        generatedDir.resolve("build/classes/java/main"),
        generatedDir.resolve("build/classes/kotlin/main"),
    ).filter { Files.isDirectory(it) }

    /**
     * 混合项目运行期 classpath：Yux 产物 + Java/Kotlin 产物目录。
     * 单一 URLClassLoader 覆盖三方产物，使 Yux ↔ Java/Kotlin 互相引用可解析
     * （父子类加载器仅单向委托，分开加载会 NoClassDefFoundError，05-§5）。
     */
    private fun runtimeClasspath(generatedDir: Path): List<Path> = listOf(
        BuildPaths.resolve(projectDir, BuildPaths.YUX_CLASSES_DIR),
        generatedDir.resolve("build/classes/java/main"),
        generatedDir.resolve("build/classes/kotlin/main"),
    ).filter { Files.isDirectory(it) }

    /** 项目类加载器：Java/Kotlin 产物目录 → URLClassLoader（父加载器为编译管线默认类加载器）；空目录 → 默认。 */
    private fun projectClassLoader(classDirs: List<Path>): ClassLoader {
        if (classDirs.isEmpty()) return ClassPathSymbolProvider::class.java.classLoader
        val urls = classDirs.map { it.toUri().toURL() }.toTypedArray()
        return URLClassLoader(urls, ClassPathSymbolProvider::class.java.classLoader)
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
    private fun compileInternal(plan: BuildPlan, config: BuildConfig, clean: Boolean, projectClasspath: List<Path>): CompiledProject {
        val cache = YuxCache(projectDir)
        if (!clean && cache.isUpToDate(plan.yuxSources, compilerVersion)) {
            return CompiledProject(emptyMap(), plan.mainClassName, DiagnosticSink(), success = true, upToDate = true)
        }
        // M10：混合项目以项目类加载器解析 Java/Kotlin 类型（compileMixed 传入 build/classes 产物目录）。
        val compiler = Compiler(DiagnosticSink(), pluginManager, classLoader = projectClassLoader(projectClasspath))
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
        val resourceArtifacts = mutableListOf<PluginArtifact>()
        // T-M6-5：插件 CodegenHook 附加产物（与 CLI Runner.kt 的合并模式一致）；
        // M8（T-M8-12）：非 .class 产物（plugin.yml 等）与类分离，进入打包流程。
        for (hook in pluginManager.hooks) {
            for (artifact in hook.generate(module, compiler.diagnostics)) {
                if (artifact.name.endsWith(".class")) {
                    artifacts += AsmBackend.OutputArtifact(artifact.name, artifact.bytes)
                } else {
                    resourceArtifacts += artifact
                }
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
            resourceArtifacts = resourceArtifacts,
            projectClasspath = projectClasspath,
        )
    }

    /**
     * 生成 Gradle 工程：源路径一律为绝对路径（生成工程从 build/generated 运行）。
     * M8（T-M8-12）：编译器插件产出的 plugin.yml（main/permissions）与 build.yml 派生
     * 的 plugin.yml（name/version/api-version）以 YAML 合并——插件内容优先。
     * 返回 null = 成功；IO 失败返回中文错误消息。
     */
    private fun writeGradleProject(config: BuildConfig, generatedDir: Path, resourceArtifacts: List<PluginArtifact>): String? {
        return try {
            val absConfig = config.copy(
                sourceJava = config.sourceJava?.let { BuildPaths.resolve(projectDir, it).toString() },
                sourceKotlin = config.sourceKotlin?.let { BuildPaths.resolve(projectDir, it).toString() },
                resourcesDir = BuildPaths.resolve(projectDir, config.resourcesDir).toString(),
            )
            val generated = GradleGenerator().generate(
                absConfig,
                yuxClassesDir = BuildPaths.resolve(projectDir, BuildPaths.YUX_CLASSES_DIR).toString(),
                shadeJars = if ("minecraft" in config.pluginsEnabled) locateShadeJars() else emptyList(),
            )
            Files.createDirectories(generatedDir)
            Files.writeString(generatedDir.resolve("settings.gradle"), generated.settingsGradle)
            Files.writeString(generatedDir.resolve("build.gradle.kts"), generated.buildGradleKts)
            val hookPluginYml = resourceArtifacts.firstOrNull { it.name == "plugin.yml" }?.bytes?.toString(Charsets.UTF_8)
            val basePluginYml = generated.pluginYml
            val merged = mergePluginYml(basePluginYml, hookPluginYml)
            if (merged != null) {
                val pluginFile = generatedDir.resolve("src/main/resources/plugin.yml")
                Files.createDirectories(pluginFile.parent)
                Files.writeString(pluginFile, merged)
            }
            null
        } catch (e: IOException) {
            "生成 Gradle 工程失败: ${e.message}"
        }
    }

    /** 合并两份 plugin.yml：基础（build.yml 派生）为底，钩子产物（编译器插件）逐键覆盖。 */
    private fun mergePluginYml(base: String?, hook: String?): String? {
        if (base == null && hook == null) return null
        val merged = linkedMapOf<String, Any?>()
        base?.let { merged.putAll(parseYamlMap(it)) }
        hook?.let { merged.putAll(parseYamlMap(it)) }
        return renderYaml(merged)
    }

    /** SnakeYAML 安全解析为有序 Map；解析失败回退为空 Map（不阻塞构建）。 */
    private fun parseYamlMap(text: String): Map<String, Any?> {
        val root = try {
            Yaml().load<Any?>(text)
        } catch (e: RuntimeException) {
            return emptyMap()
        }
        if (root !is Map<*, *>) return emptyMap()
        return root.entries.mapNotNull { (k, v) ->
            (k as? String)?.let { it to v }
        }.toMap(linkedMapOf())
    }

    /** 以块式风格渲染 YAML（snakeyaml DumperOptions 默认即块式）。 */
    private fun renderYaml(map: Map<String, Any?>): String = Yaml().dump(map)

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

    /**
     * 定位插件 jar 需 shade 的依赖（M9）：Paper 加载要求插件 jar 自包含 SDK 运行时与
     * 第三方库（服务器只提供 paper-api）。从当前进程 classpath 定位：
     * - `yux-minecraft-runtime`（PluginBootstrap/注解/HomeStore/ConfigManager）
     * - `snakeyaml`（HomeStore/ConfigManager 的 YAML 依赖）
     * - `kotlin-stdlib`（runtime 的 Kotlin 传递依赖）
     * 逐个解析类 codeSource；未找到则跳过（该依赖由服务器或环境提供）。
     */
    private fun locateShadeJars(): List<String> {
        val markers = listOf(
            "yux.minecraft.bootstrap.PluginBootstrap",
            "org.yaml.snakeyaml.Yaml",
            "kotlin.Unit",
        )
        return markers.mapNotNull { marker ->
            try {
                val location = Class.forName(marker).protectionDomain?.codeSource?.location ?: return@mapNotNull null
                val file = try {
                    java.nio.file.Paths.get(location.toURI())
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                if (file.toString().endsWith(".jar") && java.nio.file.Files.isRegularFile(file)) file.toString() else null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
