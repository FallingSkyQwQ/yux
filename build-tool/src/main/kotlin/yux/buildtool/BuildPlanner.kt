package yux.buildtool

import yux.compiler.Compiler
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * 编译计划（T-M7-3，05-§4 编译顺序编排）：一次 [BuildPlanner.plan] 的产物，
 * 是 Gradle 生成器 / 执行器的输入契约。
 */
data class BuildPlan(
    /** 对应 build.yml 解析结果。 */
    val config: BuildConfig,
    /** 项目根目录（绝对路径）。 */
    val projectDir: Path,
    /** Yux 源码文件（绝对路径，按文件名排序）。 */
    val yuxSources: List<Path>,
    /** Kotlin 源码文件（绝对路径，按文件名排序）。 */
    val kotlinSources: List<Path>,
    /** Java 源码文件（绝对路径，按文件名排序）。 */
    val javaSources: List<Path>,
    /** 是否存在 Kotlin 源码。 */
    val hasKotlin: Boolean,
    /** 是否存在 Java 源码。 */
    val hasJava: Boolean,
    /** 编译顺序（05-§4 编排视图）：`yux` 先行，其后按 `java`、`kotlin` 追加。 */
    val compileOrder: List<String>,
    /** 主类名：`config.mainClass ?: "Main"`（05-§5.3 文件类命名：src/main.yux → Main）。 */
    val mainClassName: String,
)

/**
 * 编译顺序编排器（T-M7-3，05-§2/§4）。
 *
 * v0.1 编排方式：Yux 源码由 yuxc 在进程内先行编译为 `build/yux-classes`
 * （[BuildPaths.YUX_CLASSES_DIR]），随后 Gradle 处理 java/kotlin 源码编译与打包
 * （javac/kotlinc 消费 yux-classes）。因此有效编译顺序为 `["yux"]` 开头，其后按
 * 固定次序 `java`、`kotlin` 追加（S-05.1：Yux 反向引用 Java/Kotlin 总是允许）。
 */
class BuildPlanner {

    /**
     * 编制编译计划：递归收集 [BuildConfig.sourceMain] 下的 `.yux`、[BuildConfig.sourceJava]
     * 下的 `.java`、[BuildConfig.sourceKotlin] 下的 `.kt`，各列表按文件名排序。
     *
     * 缺失的源码目录视为空列表（非错误）；但 `source.main` 下没有任何 `.yux` 文件时抛
     * [BuildConfigException]（05-§2：Yux 为默认且必需的源码）。
     */
    fun plan(projectDir: Path, config: BuildConfig): BuildPlan {
        val yuxRoot = BuildPaths.resolve(projectDir, config.sourceMain)
        val yuxSources = discover(yuxRoot, ".yux")
        if (yuxSources.isEmpty()) {
            throw BuildConfigException("未找到 Yux 源码: $yuxRoot")
        }
        val javaSources = config.sourceJava?.let { discover(BuildPaths.resolve(projectDir, it), ".java") }.orEmpty()
        val kotlinSources = config.sourceKotlin?.let { discover(BuildPaths.resolve(projectDir, it), ".kt") }.orEmpty()
        val hasJava = javaSources.isNotEmpty()
        val hasKotlin = kotlinSources.isNotEmpty()
        return BuildPlan(
            config = config,
            projectDir = projectDir,
            yuxSources = yuxSources,
            kotlinSources = kotlinSources,
            javaSources = javaSources,
            hasKotlin = hasKotlin,
            hasJava = hasJava,
            compileOrder = buildList {
                add("yux")
                if (hasJava) add("java")
                if (hasKotlin) add("kotlin")
            },
            mainClassName = config.mainClass ?: defaultMainClass(yuxSources),
        )
    }

    /**
     * 默认主类：`main.yux` 的文件类（05-§5.3）。文件声明 `package com.example` 时返回
     * 包限定名 `com.example.Main`（与 IRGen 文件类命名一致），否则 `Main`。
     * 无 `main.yux` / 读取或解析失败 → 回退 `Main`。
     */
    private fun defaultMainClass(yuxSources: List<Path>): String {
        val main = yuxSources.firstOrNull { it.fileName.toString().substringBeforeLast('.') == "main" } ?: return "Main"
        val pkg = try {
            val compiler = Compiler()
            val decls = compiler.parseToDecls(SourceFile(main.toString(), Files.readString(main)))
            decls.filterIsInstance<yux.compiler.ast.YxPackage>().firstOrNull()?.name ?: ""
        } catch (e: Exception) {
            ""
        }
        return if (pkg.isEmpty()) "Main" else "$pkg.Main"
    }

    /** 递归收集 [dir] 下后缀为 [ext] 的普通文件，按文件名排序；目录缺失返回空列表。 */
    private fun discover(dir: Path, ext: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(ext) }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
    }
}
