package yux.minecraft.config

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import yux.minecraft.annotations.YuxConfig
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 顶层配置：仅标量字段（T-M8-8 测试样例）。 */
@YuxConfig(name = "Server")
data class ServerConfig(
    val motd: String = "Welcome!",
    val maxPlayers: Int = 100,
    val spawn: String = "0,64,0",
)

/** 嵌套 data 类（无注解，作为 [WorldConfig] 的嵌套字段）。 */
data class Spawn(val x: Int = 0, val z: Int = 0)

/** 含嵌套 data 类的配置。 */
@YuxConfig(name = "World")
data class WorldConfig(val world: String = "world", val spawn: Spawn = Spawn())

/** 含 List 字段的配置。 */
@YuxConfig(name = "ListCfg")
data class ListConfig(val names: List<String> = emptyList())

/**
 * T-M8-8 ConfigManager 单元测试：默认值、字段覆盖、缓存、保存/重载、嵌套与列表。
 */
class ConfigManagerTest {

    @TempDir
    lateinit var dataFolder: File

    /** 构造假 Plugin：getDataFolder → [dir]、getLogger → 测试 Logger，其余方法抛 UnsupportedOperationException。 */
    private fun fakePlugin(dir: File): Plugin = Proxy.newProxyInstance(
        Plugin::class.java.classLoader,
        arrayOf(Plugin::class.java),
        InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDataFolder" -> dir
                "getLogger" -> java.util.logging.Logger.getLogger("ConfigManagerTest")
                else -> throw UnsupportedOperationException("stub: ${method.name}")
            }
        },
    ) as Plugin

    private val plugin: Plugin by lazy { fakePlugin(dataFolder) }

    /** 默认路径 "config.yml" 对应的文件。 */
    private fun configFile(): File = File(plugin.dataFolder, "config.yml")

    @Test
    fun `load without file returns defaults`() {
        val cfg = ConfigManager.load(plugin, ServerConfig())
        assertEquals("Welcome!", cfg.motd)
        assertEquals(100, cfg.maxPlayers)
        assertEquals("0,64,0", cfg.spawn)
    }

    @Test
    fun `load overrides defaults from yaml and caches by name`() {
        configFile().writeText("maxPlayers: 50\n")
        val cfg = ConfigManager.load(plugin, ServerConfig())
        assertEquals("Welcome!", cfg.motd)
        assertEquals(50, cfg.maxPlayers)
        assertSame(cfg, ConfigManager.get("Server"))
    }

    @Test
    fun `save writes file and reload reads edited values`() {
        ConfigManager.save(plugin, ConfigManager.load(plugin, ServerConfig()))
        assertTrue(configFile().isFile)
        configFile().writeText("maxPlayers: 77\nmotd: Changed\n")
        val reloaded = ConfigManager.reload(plugin, ServerConfig())
        assertEquals(77, reloaded.maxPlayers)
        assertEquals("Changed", reloaded.motd)
    }

    @Test
    fun `nested data class merges nested yaml map`() {
        configFile().writeText("spawn:\n  x: 10\n  z: 20\n")
        val cfg = ConfigManager.load(plugin, WorldConfig())
        assertEquals("world", cfg.world)
        assertEquals(10, cfg.spawn.x)
        assertEquals(20, cfg.spawn.z)
    }

    @Test
    fun `list field loads from yaml list`() {
        configFile().writeText("names:\n  - a\n  - b\n")
        val cfg = ConfigManager.load(plugin, ListConfig())
        assertEquals(listOf("a", "b"), cfg.names)
    }

    @Test
    fun `get for missing name returns null`() {
        assertNull(ConfigManager.get("missing"))
    }

    @Test
    fun `saved file parses back equal`() {
        val original = ServerConfig(motd = "Hi", maxPlayers = 42, spawn = "1,2,3")
        ConfigManager.save(plugin, original)
        val loaded = ConfigManager.load(plugin, ServerConfig())
        assertEquals(original, loaded)
    }

    @Test
    fun `save 原子写不留残留临时文件且可读回`() {
        val original = ServerConfig(motd = "Hi", maxPlayers = 42, spawn = "1,2,3")
        ConfigManager.save(plugin, original)
        assertTrue(configFile().isFile, "应写入 config.yml")
        assertTrue(!File(dataFolder, "config.yml.tmp").exists(), "不应残留 .tmp 临时文件")
        assertEquals(original, ConfigManager.load(plugin, ServerConfig()))
    }

    @Test
    fun `损坏配置文件回退默认值不崩溃`() {
        configFile().writeText("maxPlayers: [unclosed\n: : bad yaml")
        val cfg = ConfigManager.load(plugin, ServerConfig())
        assertEquals(100, cfg.maxPlayers)
        assertEquals("Welcome!", cfg.motd)
    }

    @Test
    fun `save 可覆盖损坏文件并重新读回`() {
        configFile().writeText("maxPlayers: [unclosed")
        ConfigManager.save(plugin, ServerConfig(motd = "ok", maxPlayers = 1, spawn = "0,0,0"))
        val reloaded = ConfigManager.load(plugin, ServerConfig())
        assertEquals("ok", reloaded.motd)
        assertEquals(1, reloaded.maxPlayers)
    }
}
