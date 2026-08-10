package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * S-8.3 JSON 序列化端到端：Yux 源码调用 `serialize`/`deserialize`（内置函数 → yux.json.Json），
 * 类字面量实参（`deserialize(json, PlayerData)`）编译为 Class 常量，反序列化经 `as` 收窄。
 */
class SerializationE2eTest {

    @Test
    fun `serialize data class and deserialize back with as cast`() {
        val out = BackendTestSupport.compileAndRun(
            """
            data PlayerData {
                name:String
                health:Int
                alive:Boolean
            }
            fun main() {
                p = PlayerData("Steve", 20, true)
                json = serialize(p)
                print json
                q = deserialize(json, PlayerData) as PlayerData
                print q.name
                print q.health
                print q.alive
            }
            """.trimIndent(),
        )
        assertEquals("""{"name":"Steve","health":20,"alive":true}Steve20true""", out)
    }

    @Test
    fun `nested data class round trip preserves json text`() {
        val out = BackendTestSupport.compileAndRun(
            """
            data Inner {
                x:Int
            }
            data Outer {
                label:String
                inner:Inner
            }
            fun main() {
                o = Outer("top", Inner(7))
                json = serialize(o)
                print json
                back = deserialize(json, Outer) as Outer
                print back.label
                print back.inner.x
            }
            """.trimIndent(),
        )
        assertEquals("""{"label":"top","inner":{"x":7}}top7""", out)
    }

    @Test
    fun `data class with list and map fields reserializes to identical json`() {
        val out = BackendTestSupport.compileAndRun(
            """
            data PlayerData {
                name:String
                health:Int
            }
            data Team {
                leader:String
                members:List
                bonus:Map
            }
            fun main() {
                roster: List Any = List Any() as List Any
                roster.add(PlayerData("a", 1))
                roster.add(PlayerData("b", 2))
                rewards: Map Any Any = Map Any Any() as Map Any Any
                rewards.put("k", 3)
                t = Team("Alex", roster, rewards)
                json = serialize(t)
                print json
                back = deserialize(json, Team) as Team
                print (serialize(back) == json)
                print back.leader
            }
            """.trimIndent(),
        )
        // List 元素为运行时不可知的 data 类 → 反序列化为 Map，重序列化 JSON 文本保持不变
        assertEquals(
            """{"leader":"Alex","members":[{"name":"a","health":1},{"name":"b","health":2}],"bonus":{"k":3}}trueAlex""",
            out,
        )
    }

    @Test
    fun `deserialize scalar markers via builtin`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                print deserialize("42", Int)
                print deserialize("3.5", Double)
                print deserialize("\"hi\"", String)
            }
            """.trimIndent(),
        )
        assertEquals("423.5hi", out)
    }
}
