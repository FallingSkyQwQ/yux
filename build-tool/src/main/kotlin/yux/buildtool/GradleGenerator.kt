package yux.buildtool

/**
 * 生成的 Gradle 工程文本（T-M7-2，02-§11.3 / 05-§6）。
 *
 * 脚本仅作产物，由 orchestrator 写入 `<project>/build/generated/`（gitignored），
 * 不参与真实 Gradle 构建；模板契约见 02-§11.3 与 05-§6。
 */
data class GeneratedProject(
    /** settings.gradle 文本。 */
    val settingsGradle: String,
    /** build.gradle.kts 文本。 */
    val buildGradleKts: String,
    /** plugin.yml 文本；仅当 `pluginsEnabled` 含 `minecraft` 时非 null。 */
    val pluginYml: String?,
)

/**
 * Gradle 脚本生成器（T-M7-2，02-§11.3 / 05-§6）。
 *
 * 将 [BuildConfig] 渲染为 build.gradle.kts / settings.gradle（可选 plugin.yml）文本；
 * 生成内容仅作产物，不触发真实 Gradle 构建。
 */
class GradleGenerator {

    /** 生成工程脚本文本；[yuxClassesDir] 为 Yux 编译产物目录，缺省 `build/yux-classes`。 */
    fun generate(config: BuildConfig, yuxClassesDir: String = BuildPaths.YUX_CLASSES_DIR): GeneratedProject =
        GeneratedProject(
            settingsGradle = "rootProject.name = \"${config.name}\"",
            buildGradleKts = renderBuildGradleKts(config, yuxClassesDir),
            pluginYml = if ("minecraft" in config.pluginsEnabled) renderPluginYml(config) else null,
        )

    /** 渲染 build.gradle.kts：各节按模板顺序输出，节间空一行，末节后无空行。 */
    private fun renderBuildGradleKts(config: BuildConfig, yuxClassesDir: String): String {
        val sections = listOf(
            pluginsSection(config),
            javaSection(config),
            applicationSection(config),
            dependenciesSection(config),
            sourceSetsSection(config, yuxClassesDir),
            jarSection(config, yuxClassesDir),
        )
        return sections.filterNotNull().joinToString("\n\n")
    }

    /** 插件块：kotlin 行仅当含 Kotlin 源码；application 行仅当有主类。 */
    private fun pluginsSection(config: BuildConfig): String = buildString {
        append("// 由 yuxc 生成（T-M7-2，02-§11.3 / 05-§6）——手动修改会被下次构建覆盖\n")
        append("plugins {\n")
        append("    java\n")
        if (config.sourceKotlin != null) {
            append("    kotlin(\"jvm\") version \"2.0.21\"\n")
        }
        if (config.mainClass != null) {
            append("    application\n")
        }
        append("}")
    }

    /** Java 工具链块：声明目标 JVM 版本。 */
    private fun javaSection(config: BuildConfig): String = buildString {
        append("java {\n")
        append("    toolchain {\n")
        append("        languageVersion.set(JavaLanguageVersion.of(${config.targetJvm}))\n")
        append("    }\n")
        append("}")
    }

    /** 应用块：声明主类；无主类时整节省略。 */
    private fun applicationSection(config: BuildConfig): String? = config.mainClass?.let { main ->
        buildString {
            append("application {\n")
            append("    mainClass.set(\"$main\")\n")
            append("}")
        }
    }

    /** 依赖块：maven 坐标在前、paper-api 在后；无任何依赖时整节省略。 */
    private fun dependenciesSection(config: BuildConfig): String? {
        if (config.mavenDeps.isEmpty() && config.paperVersion == null) return null
        return buildString {
            append("dependencies {\n")
            for (dep in config.mavenDeps) {
                append("    implementation(\"${dep.toGradleNotation()}\")\n")
            }
            if (config.paperVersion != null) {
                append("    implementation(\"io.papermc.paper:paper-api:${config.paperVersion}-R0.1-SNAPSHOT\")\n")
            }
            append("}")
        }
    }

    /** 源码集：Yux 产物目录恒定，Java/Kotlin 目录按配置追加。 */
    private fun sourceSetsSection(config: BuildConfig, yuxClassesDir: String): String = buildString {
        append("sourceSets {\n")
        append("    main {\n")
        append("        java {\n")
        append("            srcDir(\"$yuxClassesDir\")\n")
        if (config.sourceJava != null) {
            append("            srcDir(\"${config.sourceJava}\")\n")
        }
        if (config.sourceKotlin != null) {
            append("            srcDir(\"${config.sourceKotlin}\")\n")
        }
        append("        }\n")
        append("        resources.srcDir(\"${config.resourcesDir}\")\n")
        append("    }\n")
        append("}")
    }

    /** 打包节：显式把 Yux 编译产物（.class）并入 jar（java.srcDir 仅作源码/类路径，不会进 jar）。 */
    private fun jarSection(config: BuildConfig, yuxClassesDir: String): String = buildString {
        append("tasks.jar {\n")
        append("    from(\"$yuxClassesDir\")\n")
        append("}")
    }

    /** plugin.yml：主类缺省回退项目名；api-version 缺省 `1.21`。 */
    private fun renderPluginYml(config: BuildConfig): String = buildString {
        append("name: ${config.name}\n")
        append("version: ${config.version}\n")
        append("main: ${config.mainClass ?: config.name}\n")
        append("api-version: ${config.paperVersion ?: "1.21"}")
    }
}
