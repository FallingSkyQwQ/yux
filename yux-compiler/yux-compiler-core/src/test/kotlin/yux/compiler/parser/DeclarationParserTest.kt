package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M2-4 声明解析（01-§3, §5）：package/import、类/data/service、函数、属性、
 * 访问器、类型参数、extends/implements、顶级属性。
 */
class DeclarationParserTest {

    private fun dump(text: String) = ParseTestSupport.dump(text)

    @Test
    fun `package and import declarations`() {
        val ast = dump("package com.example\n\nimport java.util.UUID\nimport org.bukkit.*")
        assertTrue(ast.contains("package com.example"), ast)
        assertTrue(ast.contains("import java.util.UUID"), ast)
        assertTrue(ast.contains("import org.bukkit.*"), ast)
    }

    @Test
    fun `class with properties`() {
        val ast = dump("Player {\n  name:String\n  health:Int\n}")
        assertTrue(ast.contains("Player"), ast)
        assertTrue(ast.contains("property name: String"), ast)
        assertTrue(ast.contains("property health: Int"), ast)
    }

    @Test
    fun `data class with full constructor semantics`() {
        val ast = dump("data User {\n  id:Int\n  name:String\n}")
        assertTrue(ast.contains("data User"), ast)
        assertTrue(ast.contains("property id: Int"), ast)
        assertTrue(ast.contains("property name: String"), ast)
    }

    @Test
    fun `data class with type parameter`() {
        val ast = dump("data Result T {\n  value:T\n  ok:Boolean\n}")
        assertTrue(ast.contains("data Result T"), ast)
        assertTrue(ast.contains("property value: T"), ast)
    }

    @Test
    fun `service declaration`() {
        val ast = dump("service Database {\n  connect() {\n  }\n}")
        assertTrue(ast.contains("service Database"), ast)
        assertTrue(ast.contains("fun connect()"), ast)
    }

    @Test
    fun `function with typed params and expression body`() {
        val ast = dump("fun add(a:Int, b:Int) = a + b")
        assertTrue(ast.contains("fun add(a: Int, b: Int)"), ast)
        assertTrue(ast.contains("body = Binary(+, Id(a), Id(b))"), ast)
    }

    @Test
    fun `function with default parameter`() {
        val ast = dump("fun greet(name:String, title:String = \"Mr.\") {\n}")
        assertTrue(ast.contains("name: String"), ast)
        assertTrue(ast.contains("title: String = Str(\"\\\"Mr.\\\"\")"), ast)
    }

    @Test
    fun `async function`() {
        val ast = dump("async fun loadPlayer(id:Int):Player {\n  return null\n}")
        assertTrue(ast.contains("fun async loadPlayer(id: Int): Player"), ast)
    }

    @Test
    fun `function with explicit return type`() {
        val ast = dump("fun clamp(v:Int):Int = v")
        assertTrue(ast.contains("fun clamp(v: Int): Int"), ast)
    }

    @Test
    fun `top-level property`() {
        val ast = dump("maxPlayers = 100\nserverName = \"YuxServer\"")
        assertTrue(ast.contains("property maxPlayers = Int(100)"), ast)
        assertTrue(ast.contains("property serverName = Str(\"\\\"YuxServer\\\"\")"), ast)
    }

    @Test
    fun `typed top-level property`() {
        val ast = dump("configPath:String = \"/etc/yux\"")
        assertTrue(ast.contains("property configPath: String"), ast)
    }

    @Test
    fun `class extends and implements`() {
        val ast = dump("PlayerListener extends Listener implements A, B {\n}")
        assertTrue(ast.contains("PlayerListener extends Listener implements A, B"), ast)
    }

    @Test
    fun `custom accessors`() {
        val ast = dump("Player {\n  health:Int {\n    get { return value }\n    set { value = clamp(set, 0, 20) }\n  }\n}")
        assertTrue(ast.contains("property health: Int"), ast)
        assertTrue(ast.contains("accessor get"), ast)
        assertTrue(ast.contains("accessor set"), ast)
    }

    @Test
    fun `annotation on data class`() {
        val ast = dump("@Serializable\ndata PlayerData {\n  name:String\n}")
        assertTrue(ast.contains("@Serializable"), ast)
        assertTrue(ast.contains("data PlayerData"), ast)
    }

    @Test
    fun `annotation with args`() {
        val ast = dump("@EventHandler(priority = HIGH)\nPlayerListener {\n}")
        assertTrue(ast.contains("@EventHandler(priority = Id(HIGH))"), ast)
    }

    @Test
    fun `private class`() {
        val ast = dump("private Secret {\n}")
        assertTrue(ast.contains("private Secret"), ast)
    }

    @Test
    fun `generic function type parameter`() {
        val ast = dump("fun map T(f:(T)->Int) {\n}")
        assertTrue(ast.contains("fun map T(f: (T) -> Int)"), ast)
    }

    @Test
    fun `init block in class`() {
        val ast = dump("Player {\n  health:Int\n  { health = 20 }\n}")
        assertTrue(ast.contains("init"), ast)
        assertTrue(ast.contains("var health = Int(20)") || ast.contains("assign Id(health) = Int(20)"), ast)
    }

    @Test
    fun `no errors for valid declarations`() {
        val text = """
            package com.example
            import java.util.UUID

            data User {
                id:Int
                name:String
            }

            service Database {
                connect() {
                }
            }

            fun main() {
                u = User(1, "Steve")
                print u.toString
            }
        """.trimIndent()
        assertFalse(ParseTestSupport.parse(text).hasErrors)
    }

    @Test
    fun `class member function and property`() {
        val ast = dump("Player {\n  fun send(msg:String) {\n  }\n  health:Int\n  fun check() {\n    x = send(\"hi\")\n  }\n}")
        assertTrue(ast.contains("fun send(msg: String)"), ast)
        assertTrue(ast.contains("property health: Int"), ast)
        assertTrue(ast.contains("fun check()"), ast)
    }
}
