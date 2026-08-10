package yux.minecraft.io

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-M9-1：HomeStore data 类 ↔ YAML 落盘/读回（04-§6 数据存储桥接，无服务器依赖）。 */
class HomeStoreTest {

    data class LocationData(
        val world: String = "",
        val x: Double = 0.0,
        val y: Double = 0.0,
        val z: Double = 0.0,
        val yaw: Float = 0f,
        val pitch: Float = 0f,
    )

    data class HomeData(
        val player: String = "",
        val homes: Map<String, LocationData> = emptyMap(),
    )

    @TempDir
    lateinit var dataFolder: File

    @Test
    fun `toYaml 序列化 data 类为块式 YAML`() {
        val home = HomeData("steve", mapOf("default" to LocationData("world", 1.0, 64.0, 2.0, 0.0f, 0.0f)))
        val yaml = HomeStore.toYaml(home)
        assertTrue(yaml.contains("player: steve"), yaml)
        assertTrue(yaml.contains("default:"), yaml)
        assertTrue(yaml.contains("world: world"), yaml)
        assertTrue(yaml.contains("x: 1.0"), yaml)
    }

    @Test
    fun `fromYaml 反序列化为 data 类 round-trip`() {
        val home = HomeData("steve", mapOf("home" to LocationData("world", 1.0, 64.0, 2.0, 0.5f, 0.25f)))
        val back = HomeStore.fromYaml(HomeStore.toYaml(home), HomeData::class.java) as HomeData
        assertEquals("steve", back.player)
        assertEquals(1, back.homes.size)
        val loc = back.homes["home"]!!
        assertEquals("world", loc.world)
        assertEquals(64.0, loc.y)
        assertEquals(0.5f, loc.yaw)
    }

    @Test
    fun `fromYaml 缺省字段使用零值`() {
        val back = HomeStore.fromYaml("player: alex\n", HomeData::class.java) as HomeData
        assertEquals("alex", back.player)
        assertTrue(back.homes.isEmpty())
    }

    @Test
    fun `save 写入 dataFolder 相对路径且父目录自动创建`() {
        HomeStore.save(dataFolder, "data/steve.yml", HomeData("steve", emptyMap()))
        val file = File(dataFolder, "data/steve.yml")
        assertTrue(file.isFile, "应写入 $file")
        assertTrue(file.readText().contains("steve"))
    }

    @Test
    fun `load 读回文件内容`() {
        val home = HomeData("steve", mapOf("a" to LocationData("w", 1.0, 2.0, 3.0, 0.0f, 0.0f)))
        HomeStore.save(dataFolder, "homes.yml", home)
        val loaded = HomeStore.load(dataFolder, "homes.yml", HomeData::class.java) as HomeData
        assertEquals(home.player, loaded.player)
        assertEquals(home.homes["a"]!!.x, loaded.homes["a"]!!.x)
    }

    @Test
    fun `load 文件缺失返回 null 且 exists 判定正确`() {
        assertNull(HomeStore.load(dataFolder, "missing.yml", HomeData::class.java))
        assertFalse(HomeStore.exists(dataFolder, "missing.yml"))
        HomeStore.save(dataFolder, "present.yml", HomeData("x", emptyMap()))
        assertTrue(HomeStore.exists(dataFolder, "present.yml"))
    }

    @Test
    fun `save 原子写不留残留临时文件`() {
        HomeStore.save(dataFolder, "data/steve.yml", HomeData("steve", emptyMap()))
        assertTrue(File(dataFolder, "data/steve.yml").isFile)
        assertFalse(File(dataFolder, "data/steve.yml.tmp").exists(), "不应残留 .tmp 临时文件")
        val loaded = HomeStore.load(dataFolder, "data/steve.yml", HomeData::class.java) as HomeData
        assertEquals("steve", loaded.player)
    }

    @Test
    fun `load 损坏文件回退 null`() {
        File(dataFolder, "broken.yml").writeText("player: [unclosed\n: : bad")
        assertNull(HomeStore.load(dataFolder, "broken.yml", HomeData::class.java))
    }

    @Test
    fun `loadMap 损坏文件回退空映射`() {
        File(dataFolder, "broken-map.yml").writeText("player: [unclosed")
        assertTrue(HomeStore.loadMap(dataFolder, "broken-map.yml", LocationData()).isEmpty())
    }
}
