package yux.cli

import java.util.Properties

/**
 * `yuxc version` 版本来源（M16，T-M16-5）：Gradle 构建时生成
 * `yuxc-version.properties`（值 = project.version），运行时读取，单一事实源。
 */
object YuxcVersion {
    /** 版本号；构建资源缺失时回退 "dev"（IDE 直跑测试场景）。 */
    val version: String =
        runCatching {
            val stream = YuxcVersion::class.java.getResourceAsStream("/yuxc-version.properties")
            stream?.use { Properties().apply { load(it) }.getProperty("version") }
        }.getOrNull() ?: "dev"
}
