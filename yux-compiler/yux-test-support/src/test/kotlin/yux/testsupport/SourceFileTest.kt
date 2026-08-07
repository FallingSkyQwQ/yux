package yux.testsupport

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SourceFileTest {

    @Test
    fun `default path is main dot yux`() {
        assertEquals("main.yux", Sources.of("fun main(){}").path)
    }

    @Test
    fun `fileName strips directories`() {
        val source = Sources.of("x", path = "src/HomeCommand.yux")
        assertEquals("HomeCommand.yux", source.fileName)
    }

    @Test
    fun `multiple collects sources preserving order`() {
        val a = Sources.of("a", path = "a.yux")
        val b = Sources.of("b", path = "b.yux")
        assertEquals(listOf(a, b), Sources.multiple(a, b))
    }

    @Test
    fun `fileName of root file is the path itself`() {
        assertEquals("main.yux", Sources.of("x").fileName)
    }
}
