package yux.buildtool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M7-3：编译顺序编排——源码发现 / 排序 / 顺序规则（05-§2/§4）。
 */
class BuildPlannerTest {

    private val planner = BuildPlanner()
    private val baseConfig = BuildConfig(name = "demo", version = "1.0.0")

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `纯 Yux 项目：按文件名排序且无 java kotlin`() {
        Files.createDirectories(projectDir.resolve("src/sub"))
        projectDir.resolve("src/main.yux").writeText("fun main() {}\n")
        projectDir.resolve("src/sub/Economy.yux").writeText("fun f() {}\n")

        val plan = planner.plan(projectDir, baseConfig)

        assertEquals(
            listOf("Economy.yux", "main.yux"),
            plan.yuxSources.map { it.fileName.toString() },
        )
        assertTrue(plan.yuxSources.all { it.isAbsolute })
        assertFalse(plan.hasJava)
        assertFalse(plan.hasKotlin)
        assertEquals(emptyList(), plan.javaSources)
        assertEquals(emptyList(), plan.kotlinSources)
        assertEquals(listOf("yux"), plan.compileOrder)
        assertEquals("Main", plan.mainClassName)
    }

    @Test
    fun `混合项目：java kotlin 就位时顺序为 yux java kotlin`() {
        Files.createDirectories(projectDir.resolve("src"))
        Files.createDirectories(projectDir.resolve("java/com/example"))
        Files.createDirectories(projectDir.resolve("kotlin/com/example"))
        projectDir.resolve("src/main.yux").writeText("fun main() {}\n")
        projectDir.resolve("java/com/example/Legacy.java").writeText("package com.example;\nclass Legacy {}\n")
        projectDir.resolve("kotlin/com/example/Bridge.kt").writeText("package com.example\nclass Bridge\n")

        val plan = planner.plan(projectDir, baseConfig.copy(sourceJava = "java", sourceKotlin = "kotlin"))

        assertTrue(plan.hasJava)
        assertTrue(plan.hasKotlin)
        assertEquals(1, plan.javaSources.size)
        assertEquals(1, plan.kotlinSources.size)
        assertEquals(listOf("yux", "java", "kotlin"), plan.compileOrder)
    }

    @Test
    fun `java 目录缺失：javaSources 为空且顺序不受影响`() {
        Files.createDirectories(projectDir.resolve("src"))
        projectDir.resolve("src/main.yux").writeText("fun main() {}\n")

        val plan = planner.plan(projectDir, baseConfig.copy(sourceJava = "java", sourceKotlin = "kotlin"))

        assertEquals(emptyList(), plan.javaSources)
        assertEquals(emptyList(), plan.kotlinSources)
        assertFalse(plan.hasJava)
        assertFalse(plan.hasKotlin)
        assertEquals(listOf("yux"), plan.compileOrder)
    }

    @Test
    fun `无 Yux 源码：抛 BuildConfigException`() {
        Files.createDirectories(projectDir.resolve("src"))

        val e = assertFailsWith<BuildConfigException> { planner.plan(projectDir, baseConfig) }

        assertTrue(e.message!!.contains("未找到 Yux 源码"))
        assertTrue(e.message!!.contains(projectDir.resolve("src").toString()))
    }

    @Test
    fun `显式 mainClass 生效`() {
        Files.createDirectories(projectDir.resolve("src"))
        projectDir.resolve("src/main.yux").writeText("fun main() {}\n")

        val plan = planner.plan(projectDir, baseConfig.copy(mainClass = "com.example.Server"))

        assertEquals("com.example.Server", plan.mainClassName)
    }
}
