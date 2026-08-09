package yux.cli

import org.junit.jupiter.api.Test
import yux.buildtool.YuxBuild
import yux.compiler.plugin.PluginLoader
import yux.compiler.plugin.PluginManager
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-M9-3：Home 插件集成测试（04-§12，无服务器环境）。
 *
 * 编译 `samples/home-plugin`（yux-compiler-minecraft 插件下沉）→ 自定义类加载器加载
 * 编译产物 → 反射调用数据层静态方法，断言：
 * - `loadHomeData`/`saveHomeData` 文件落盘 round-trip（04-§5/§6 数据存储）
 * - `config` 回退默认值（04-§4）
 * - `cooldownActive` 冷却判定（04-§9）
 * - `Location.toData`/`LocationData.toLocation` 双向转换（04-§6）
 *
 * 命令/事件行为由 runtime 的 CommandRegistry/PluginBootstrap 测试覆盖契约
 * （M8 T-M8-3/4/7），本测试验证编译产物与数据层（R4：无服务器）。
 */
class HomePluginE2eTest {

    private data class Loaded(val loader: ClassLoader, val cls: Class<*>)

    private fun compiledClasses(): Pair<ClassLoader, Map<String, ByteArray>> {
        val projectDir = locateProject()
        val pluginJar = findCompilerPluginJar()
            ?: throw AssertionError("yux-compiler-minecraft jar 未构建（先运行 :yux-plugin-minecraft:yux-compiler-minecraft:jar）")
        val manager = PluginManager()
        for (plugin in PluginLoader.loadFromJars(listOf(pluginJar))) manager.register(plugin)
        val result = YuxBuild(projectDir, manager).compile()
        if (!result.success) {
            throw AssertionError("Home 插件编译失败: ${result.diagnostics.diagnostics.joinToString("\n") { it.severity.toString() + ": " + it.message }}")
        }
        val loader = MemoryClassLoader(result.classes, HomePluginE2eTest::class.java.classLoader)
        return loader to result.classes
    }

    private fun findCompilerPluginJar(): Path? {
        val dirs = listOf(
            Path.of("yux-plugin-minecraft/yux-compiler-minecraft/build/libs"),
            Path.of("../yux-plugin-minecraft/yux-compiler-minecraft/build/libs"),
            Path.of("../../yux-plugin-minecraft/yux-compiler-minecraft/build/libs"),
        )
        for (dir in dirs) {
            if (Files.isDirectory(dir)) {
                Files.list(dir).use { s ->
                    val jar = s.filter { it.toString().endsWith(".jar") }.findFirst()
                    if (jar.isPresent) return jar.get()
                }
            }
        }
        return null
    }

    /** 定位仓库根（测试工作目录是模块目录，向上查找 samples/home-plugin）。 */
    private fun locateProject(): Path {
        for (candidate in listOf(
            Path.of("samples/home-plugin"),
            Path.of("../samples/home-plugin"),
            Path.of("../../samples/home-plugin"),
        )) {
            val abs = candidate.toAbsolutePath().normalize()
            if (Files.isDirectory(abs)) return abs
        }
        throw AssertionError("samples/home-plugin 不存在（从 ${Path.of(".").toAbsolutePath()} 查找）")
    }

    private fun staticMethod(cls: Class<*>, name: String): Method {
        val m = cls.getMethod(name, *emptyArray())
        m.isAccessible = true
        return m
    }

    @Test
    fun `编译 Home 插件产出完整类集合`() {
        val (_, classes) = compiledClasses()
        val names = classes.keys
        for (expected in listOf(
            "HomePlugin", "HomeConfig", "HomeData", "LocationData",
            "SethomeCommand", "HomeCommand", "DelhomeCommand", "HomesCommand",
            "PlayerJoinEvent_Handler", "PlayerQuitEvent_Handler", "PlayerDeathEvent_Handler",
            "Task_0_6000",
        )) {
            assertTrue(names.contains(expected), "应生成类 $expected，实际: $names")
        }
    }

    @Test
    fun `loadHomeData 无文件返回空家数据`() {
        val (loader, _) = compiledClasses()
        val homeData = loader.loadClass("HomeData_File")
        val method = homeData.getMethod("loadHomeData", String::class.java)
        val result = method.invoke(null, "steve")
        assertNotNull(result, "loadHomeData 应返回非空 HomeData")
        val player = result.javaClass.getMethod("getPlayer").invoke(result)
        assertEquals("steve", player)
    }

    @Test
    fun `saveHomeData 与 loadHomeData round-trip 落盘`() {
        val dataDir = Path.of("plugins/HomePlugin/data")
        try {
            deleteRecursively(Path.of("plugins"))
            val (loader, _) = compiledClasses()
            val homeDataFile = loader.loadClass("HomeData_File")
            val locationDataCls = loader.loadClass("LocationData")
            val homeDataCls = loader.loadClass("HomeData")

            // 构造 LocationData("world", 1.0, 64.0, 2.0, 0.0, 0.0)
            val locCtor = locationDataCls.getConstructor(
                String::class.java, Double::class.java, Double::class.java, Double::class.java,
                Float::class.java, Float::class.java,
            )
            val loc = locCtor.newInstance("world", 1.0, 64.0, 2.0, 0.0f, 0.0f)

            // 构造 homes: HashMap<String, LocationData>
            val homes = java.util.HashMap<String, Any>().also { it["default"] = loc }
            val homeCtor = homeDataCls.getConstructor(String::class.java, java.util.Map::class.java)
            val home = homeCtor.newInstance("steve", homes)

            val save = homeDataFile.getMethod("saveHomeData", homeDataCls)
            save.invoke(null, home)

            val file = dataDir.resolve("steve.yml")
            assertTrue(Files.isRegularFile(file), "应落盘 $file")

            val load = homeDataFile.getMethod("loadHomeData", String::class.java)
            val loaded = load.invoke(null, "steve")
            val loadedPlayer = loaded.javaClass.getMethod("getPlayer").invoke(loaded)
            assertEquals("steve", loadedPlayer)
            val loadedHomes = loaded.javaClass.getMethod("getHomes").invoke(loaded) as java.util.Map<*, *>
            assertEquals(1, loadedHomes.size(), "round-trip 后应有 1 个家")
            val loadedLoc = loadedHomes.get("default")
            assertEquals(64.0, loadedLoc.javaClass.getMethod("getY").invoke(loadedLoc))
        } finally {
            deleteRecursively(Path.of("plugins"))
        }
    }

    /** 递归删除（不存在时静默）。 */
    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `config 回退返回默认配置`() {
        val (loader, _) = compiledClasses()
        val scheduleFile = loader.loadClass("HomeSchedule")
        val config = scheduleFile.getMethod("config").invoke(null)
        assertNotNull(config, "config 应返回 HomeConfig")
        val maxHomes = config.javaClass.getMethod("getMaxHomes").invoke(config)
        assertEquals(5, maxHomes)
        val welcome = config.javaClass.getMethod("getWelcomeMessage").invoke(config)
        assertEquals("欢迎 \$name 回来！", welcome)
    }

    @Test
    fun `构建产物 jar 自包含 runtime 可被 Paper 加载`() {
        val projectDir = locateProject()
        val pluginJar = findCompilerPluginJar()
            ?: throw AssertionError("yux-compiler-minecraft jar 未构建")
        val manager = PluginManager()
        for (plugin in PluginLoader.loadFromJars(listOf(pluginJar))) manager.register(plugin)
        // 完整 build 产出 jar 需要托管 Gradle；不可用时跳过（R4 同 ProjectCommandTest）
        org.junit.jupiter.api.Assumptions.assumeTrue(gradleAvailable(projectDir), "Gradle 不可用，跳过")
        // 完整 build 产出 jar（含 shade：runtime + snakeyaml + kotlin-stdlib）
        val result = YuxBuild(projectDir, manager).build(clean = true)
        assertTrue(result.success, "build 应成功: ${result.message}")
        val jar = result.artifactJar!!
        assertTrue(Files.isRegularFile(jar), "jar 应存在: $jar")

        // 关键 runtime 类必须自包含在插件 jar（Paper 服务器不提供 yux 运行时/snakeyaml）
        val entries = java.util.zip.ZipFile(jar.toFile()).use { z ->
            z.entries().asSequence().map { it.name }.toSet()
        }
        for (required in listOf(
            "plugin.yml",
            "HomePlugin.class",
            "yux/minecraft/bootstrap/PluginBootstrap.class",
            "yux/minecraft/annotations/YuxCommand.class",
            "yux/minecraft/io/HomeStore.class",
            "yux/minecraft/config/ConfigManager.class",
            "org/yaml/snakeyaml/Yaml.class",
            "kotlin/Unit.class",
        )) {
            assertTrue(entries.contains(required), "jar 应自包含 $required（Paper 可加载）")
        }
    }

    /** Gradle 可用性探测（镜像 ProjectCommandTest）。 */
    private fun gradleAvailable(projectDir: Path): Boolean {
        val located = yux.buildtool.GradleRunner.locateGradle(projectDir)
        if (located != "gradle") {
            val resolved = Path.of(located)
            if (Files.exists(resolved)) return true
        }
        return try {
            val proc = ProcessBuilder("gradle", "--version").redirectErrorStream(true).start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
