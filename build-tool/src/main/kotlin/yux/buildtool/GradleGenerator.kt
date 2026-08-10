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
    fun generate(
        config: BuildConfig,
        yuxClassesDir: String = BuildPaths.YUX_CLASSES_DIR,
        shadeJars: List<String> = emptyList(),
    ): GeneratedProject =
        GeneratedProject(
            settingsGradle = renderSettingsGradle(config),
            buildGradleKts = renderBuildGradleKts(config, yuxClassesDir, shadeJars),
            pluginYml = if ("minecraft" in config.pluginsEnabled) renderPluginYml(config) else null,
        )

    /** settings.gradle：声明仓库，供 kotlin 插件解析 kotlin-stdlib 与 maven 依赖（M10 混合项目必需）。 */
    private fun renderSettingsGradle(config: BuildConfig): String = buildString {
        append("rootProject.name = \"${kt(config.name)}\"\n")
        append("dependencyResolutionManagement {\n")
        append("    repositories {\n")
        append("        mavenCentral()\n")
        append("        gradlePluginPortal()\n")
        if (config.paperVersion != null) {
            append("        maven { url = 'https://repo.papermc.io/repository/maven-public/' }\n")
        }
        append("    }\n")
        append("}")
    }

    /** 渲染 build.gradle.kts：各节按模板顺序输出，节间空一行，末节后无空行。 */
    private fun renderBuildGradleKts(config: BuildConfig, yuxClassesDir: String, shadeJars: List<String>): String {
        val sections = listOf(
            pluginsSection(config),
            javaSection(config),
            kotlinSection(config),
            applicationSection(config),
            dependenciesSection(config, yuxClassesDir),
            sourceSetsSection(config, yuxClassesDir),
            jarSection(config, yuxClassesDir, shadeJars),
        )
        return sections.filterNotNull().joinToString("\n\n")
    }

    /** 插件块：kotlin 行仅当含 Kotlin 源码；application 行仅当有主类。 */
    private fun pluginsSection(config: BuildConfig): String = buildString {
        append("// 由 yuxc 生成（T-M7-2，02-§11.3 / 05-§6）——手动修改会被下次构建覆盖\n")
        append("plugins {\n")
        append("    java\n")
        if (config.sourceKotlin != null) {
            append("    kotlin(\"jvm\") version \"2.3.21\"\n")
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

    /**
     * Kotlin 工具链块：kotlinc 固定运行于目标 JVM（M10：daemon 为更高版本 JDK 时
     * 编译器版本解析会失败，jvmToolchain 保证编译进程使用 [BuildConfig.targetJvm]）。
     */
    private fun kotlinSection(config: BuildConfig): String? = if (config.sourceKotlin == null) {
        null
    } else {
        buildString {
            append("kotlin {\n")
            append("    jvmToolchain(${config.targetJvm})\n")
            append("}")
        }
    }

    /** 应用块：声明主类；无主类时整节省略。 */
    private fun applicationSection(config: BuildConfig): String? = config.mainClass?.let { main ->
        buildString {
            append("application {\n")
            append("    mainClass.set(\"${kt(main)}\")\n")
            append("}")
        }
    }

    /**
     * 依赖块：maven 坐标在前、paper-api 在后；混合项目（含 Java/Kotlin 源码）追加
     * yux 产物目录为 files() 依赖——javac/kotlinc 的 compile classpath 由此可见 Yux 类型
     * （M10，05-§5.3/5.4「Kotlin/Java → Yux」）。无任何依赖时整节省略。
     */
    private fun dependenciesSection(config: BuildConfig, yuxClassesDir: String): String? {
        val hasExternal = config.mavenDeps.isNotEmpty() || config.paperVersion != null
        val mixed = config.sourceJava != null || config.sourceKotlin != null
        if (!hasExternal && !mixed) return null
        return buildString {
            append("dependencies {\n")
            for (dep in config.mavenDeps) {
                append("    implementation(\"${kt(dep.toGradleNotation())}\")\n")
            }
            if (config.paperVersion != null) {
                append("    implementation(\"io.papermc.paper:paper-api:${kt(config.paperVersion)}-R0.1-SNAPSHOT\")\n")
            }
            if (mixed) {
                append("    // M10（05-§4）：Yux 编译产物加入编译 classpath（Yux 类型对 Java/Kotlin 可见）\n")
                append("    implementation(files(\"${kt(yuxClassesDir)}\"))\n")
            }
            append("}")
        }
    }

    /** 源码集：Yux 产物目录恒定，Java/Kotlin 目录按配置追加。 */
    private fun sourceSetsSection(config: BuildConfig, yuxClassesDir: String): String = buildString {
        append("sourceSets {\n")
        append("    main {\n")
        append("        java {\n")
        append("            srcDir(\"${kt(yuxClassesDir)}\")\n")
        if (config.sourceJava != null) {
            append("            srcDir(\"${kt(config.sourceJava)}\")\n")
        }
        if (config.sourceKotlin != null) {
            append("            srcDir(\"${kt(config.sourceKotlin)}\")\n")
        }
        append("        }\n")
        append("        resources.srcDir(\"${kt(config.resourcesDir)}\")\n")
        append("    }\n")
        append("}")
    }

    /**
     * 打包节：显式把 Yux 编译产物（.class）并入 jar；minecraft 项目附带 shade 运行时与第三方依赖。
     */
    private fun jarSection(config: BuildConfig, yuxClassesDir: String, shadeJars: List<String>): String = buildString {
        append("tasks.jar {\n")
        append("    from(\"${kt(yuxClassesDir)}\")\n")
        // M9（T-M9-1）：Paper 加载要求插件 jar 自包含 SDK 运行时与第三方依赖
        // （PluginBootstrap/注解/HomeStore + snakeyaml/kotlin-stdlib，服务器不提供）
        for (jar in shadeJars) {
            append("    from(zipTree(\"${kt(jar)}\")) {\n")
            append("        exclude(\"META-INF/**\")\n")
            append("    }\n")
        }
        append("}")
    }

    /** plugin.yml：主类缺省回退项目名；api-version 缺省 `1.21`。 */
    private fun renderPluginYml(config: BuildConfig): String = buildString {
        append("name: ${yaml(config.name)}\n")
        append("version: ${yaml(config.version)}\n")
        append("main: ${yaml(config.mainClass ?: config.name)}\n")
        append("api-version: ${yaml(config.paperVersion ?: "1.21")}")
    }

    /** Kotlin DSL 双引号字符串转义：`\`、`"`、`$`（Windows 反斜杠路径与内插符号均安全）。 */
    private fun kt(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    /** YAML 双引号字符串：始终加引号（规避冒号/井号/前导引号/数字字面量等解析歧义），值内转义 `\`、`"`、换行。 */
    private fun yaml(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
