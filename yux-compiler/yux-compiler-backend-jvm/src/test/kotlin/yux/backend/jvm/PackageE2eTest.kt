package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * package 声明 → 真实 JVM 包：文件类/类按 `com.example.X` 命名发射，
 * 同包跨文件引用与跨包 import 可解析，默认包行为与包无关回归不变。
 */
class PackageE2eTest {

    @Test
    fun `packaged file emits qualified classes and main runs`() {
        val classes = BackendTestSupport.compile(
            """
            package com.example
            data Player {
                name: String
            }
            fun main() {
                p = Player("Steve")
                print p.name
            }
            """.trimIndent(),
        )
        assertTrue(classes.containsKey("com.example.Main"), "文件类应带包限定: ${classes.keys}")
        assertTrue(classes.containsKey("com.example.Player"), "类应带包限定: ${classes.keys}")
        assertEquals("Steve", BackendTestSupport.run(classes, "com.example.Main"))
    }

    @Test
    fun `same package cross file type access works`() {
        val classes = BackendTestSupport.compileMany(
            mapOf(
                "Player.yux" to
                    """
                    package com.example
                    data Player {
                        name: String
                    }
                    fun greet(p: Player): String = "hi ${'$'}{p.name}"
                    """.trimIndent(),
                "main.yux" to
                    """
                    package com.example
                    fun main() {
                        p = Player("Alex")
                        print greet(p)
                    }
                    """.trimIndent(),
            ),
        )
        assertTrue(classes.containsKey("com.example.Player"))
        assertTrue(classes.containsKey("com.example.Main"))
        assertEquals("hi Alex", BackendTestSupport.run(classes, "com.example.Main"))
    }

    @Test
    fun `packaged class importable from another package`() {
        val classes = BackendTestSupport.compileMany(
            mapOf(
                "Player.yux" to
                    """
                    package com.example
                    data Player {
                        name: String
                    }
                    """.trimIndent(),
                "main.yux" to
                    """
                    import com.example.Player
                    fun main() {
                        p = Player("Bob")
                        print p.name
                    }
                    """.trimIndent(),
            ),
        )
        assertTrue(classes.containsKey("com.example.Player"))
        assertTrue(classes.containsKey("Main"), "默认包文件类保持简单名: ${classes.keys}")
        assertEquals("Bob", BackendTestSupport.run(classes, "Main"))
    }

    @Test
    fun `packaged class importable across packages via star import`() {
        val classes = BackendTestSupport.compileMany(
            mapOf(
                "Player.yux" to
                    """
                    package com.example
                    data Player {
                        name: String
                    }
                    """.trimIndent(),
                "main.yux" to
                    """
                    package com.other
                    import com.example.*
                    fun main() {
                        p = Player("Cara")
                        print p.name
                    }
                    """.trimIndent(),
            ),
        )
        assertEquals("Cara", BackendTestSupport.run(classes, "com.other.Main"))
    }

    @Test
    fun `default package stays byte identical with simple names`() {
        val classes = BackendTestSupport.compile(
            """
            data Player {
                name: String
            }
            fun main() {
                p = Player("Dev")
                print p.name
            }
            """.trimIndent(),
        )
        assertTrue(classes.containsKey("Main"), "默认包文件类名为 Main: ${classes.keys}")
        assertTrue(classes.containsKey("Player"), "默认包类名为 Player: ${classes.keys}")
        assertEquals("Dev", BackendTestSupport.run(classes, "Main"))
    }

    @Test
    fun `packaged data class toString uses simple name`() {
        val classes = BackendTestSupport.compileMany(
            mapOf(
                "Player.yux" to
                    """
                    package com.example
                    data Player {
                        id: Int
                        name: String
                    }
                    """.trimIndent(),
                "main.yux" to
                    """
                    package com.example
                    fun main() {
                        p = Player(7, "Steve")
                        print p.toString
                    }
                    """.trimIndent(),
            ),
        )
        assertEquals("Player(id=7, name=Steve)", BackendTestSupport.run(classes, "com.example.Main"))
    }
}
