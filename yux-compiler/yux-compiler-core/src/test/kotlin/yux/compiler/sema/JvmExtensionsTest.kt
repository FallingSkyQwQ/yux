package yux.compiler.sema

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T-M11-3 JVM 扩展注册表：接收者精确/接口链匹配、未知成员与不可加载类的安全回退。
 */
class JvmExtensionsTest {

    @Test
    fun `exact hit on interface java util List map`() {
        assertEquals("yux.collection.Colls" to "map", JvmExtensions.find("java.util.List", "map"))
    }

    @Test
    fun `interface chain hit on java util ArrayList map`() {
        // ArrayList 未注册，应沿接口链（List）命中
        assertEquals("yux.collection.Colls" to "map", JvmExtensions.find("java.util.ArrayList", "map"))
    }

    @Test
    fun `superclass chain hit on linked list forEach`() {
        // LinkedList → AbstractSequentialList → AbstractList → AbstractCollection → List
        assertEquals("yux.collection.Colls" to "forEach", JvmExtensions.find("java.util.LinkedList", "forEach"))
    }

    @Test
    fun `all collection members resolve for java util List`() {
        for (name in listOf("map", "filter", "forEach", "sort", "sum", "max", "first", "reduce")) {
            assertEquals("yux.collection.Colls", JvmExtensions.find("java.util.List", name)?.first, name)
        }
    }

    @Test
    fun `string member resolves to yux core Text`() {
        assertEquals("yux.core.Text" to "uppercase", JvmExtensions.find("java.lang.String", "uppercase"))
        assertEquals("yux.core.Text" to "lowercase", JvmExtensions.find("java.lang.String", "lowercase"))
        assertEquals("yux.core.Text" to "split", JvmExtensions.find("java.lang.String", "split"))
        assertEquals("yux.core.Text" to "format", JvmExtensions.find("java.lang.String", "format"))
    }

    @Test
    fun `unknown member returns null`() {
        assertNull(JvmExtensions.find("java.util.List", "bogus"))
        assertNull(JvmExtensions.find("java.lang.String", "bogus"))
    }

    @Test
    fun `class not found returns null safely`() {
        assertNull(JvmExtensions.find("no.such.Class", "map"))
        assertNull(JvmExtensions.find("com.example.Main", "uppercase"))
    }

    @Test
    fun `object class returns null`() {
        assertNull(JvmExtensions.find("java.lang.Object", "map"))
    }

    @Test
    fun `non iterable jvm class returns null`() {
        assertNull(JvmExtensions.find("java.lang.Integer", "map"))
    }
}
