package yux.buildtool

import java.nio.file.Path

/**
 * build.yml 解析后的构建配置模型（T-M7-1，02-§11.2 / 05-§3）。
 *
 * 字段与 build.yml 语义一一对应；缺省值见各属性注释。
 * 该模型是 build-tool 各组件（Parser / Planner / Generator / Runner）的共享契约。
 */
data class BuildConfig(
    /** `name`（必填）：项目名；同时作为 jar/插件产物名。 */
    val name: String,
    /** `version`（必填）：项目版本，用于 jar 版本号与 plugin.yml。 */
    val version: String,
    /** `language.yux`（缺省 "1.0"）：Yux 语言版本。 */
    val languageYux: String = "1.0",
    /** `target.jvm`（缺省 21）：目标 JVM 版本。 */
    val targetJvm: Int = 21,
    /** `source.main`（缺省 "src"）：Yux 源码根目录。 */
    val sourceMain: String = "src",
    /** `source.kotlin`（可选）：Kotlin 源码目录（05-§2 混合项目）。 */
    val sourceKotlin: String? = null,
    /** `source.java`（可选）：Java 源码目录（05-§2 混合项目）。 */
    val sourceJava: String? = null,
    /** `resources`（可选，缺省 "resources"）：资源目录，随 jar 打包。 */
    val resourcesDir: String = "resources",
    /** `tests`（可选，缺省 "tests"）：测试源码目录（05-§2）。 */
    val testsDir: String = "tests",
    /** `dependencies.maven`：额外 Maven 坐标（05-§3）。 */
    val mavenDeps: List<MavenCoord> = emptyList(),
    /** `dependencies.minecraft.paper`（可选）：Paper 版本，启用 paper-api 依赖。 */
    val paperVersion: String? = null,
    /** `plugins`：启用的插件开关（如 serialization/injection/minecraft），值为 true 的键。 */
    val pluginsEnabled: Set<String> = emptySet(),
    /** `main`（可选）：显式主类名；缺省为 src/main.yux 的文件类（`Main`）。 */
    val mainClass: String? = null,
)

/** Maven 坐标（`dependencies.maven` 列表项，05-§3）。 */
data class MavenCoord(
    val group: String,
    val artifact: String,
    val version: String,
) {
    /** 坐标字符串 `group:artifact:version`（Gradle 依赖记法）。 */
    fun toGradleNotation(): String = "$group:$artifact:$version"
}

/** build.yml schema 校验失败（T-M7-1）：缺必填字段 / 字段类型错误 / 路径非法。 */
class BuildConfigException(message: String) : Exception(message)

/** build-tool 各组件共享的产物/目录布局常量（T-M7-3/6，02-§11/12）。 */
object BuildPaths {
    /** Yux 编译产物目录（相对项目根），Gradle 打包时作为源码集输入。 */
    const val YUX_CLASSES_DIR = "build/yux-classes"
    /** 增量缓存目录（02-§12.3）。 */
    const val CACHE_DIR = "build/yux-cache"
    /** 生成 Gradle 工程目录（02-§11.3：生成脚本仅作产物）。 */
    const val GENERATED_DIR = "build/generated"
    /** 最终产物目录：jar 输出到 `build/libs`。 */
    const val LIBS_DIR = "build/libs"
    /** IDE 映射文件 `yux-symbols.json`（T-M7-7，05-§8）。 */
    const val SYMBOLS_FILE = "build/yux-symbols.json"

    /** 解析项目根下的相对路径（防御外部路径逃逸：禁止 `..`）。 */
    fun resolve(projectDir: Path, relative: String): Path {
        val path = projectDir.resolve(relative).normalize()
        if (!path.startsWith(projectDir.normalize())) {
            throw BuildConfigException("路径越出项目根: $relative")
        }
        return path
    }
}
