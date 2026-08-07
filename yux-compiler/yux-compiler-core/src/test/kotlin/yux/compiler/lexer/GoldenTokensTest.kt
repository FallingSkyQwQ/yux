package yux.compiler.lexer

import org.junit.jupiter.api.Test
import yux.testsupport.GoldenFile
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

class GoldenTokensTest {

    @Test
    fun `lexer samples match golden tokens`() {
        val samplesDir = Path.of("src/test/resources/samples")
        assertTrue(Files.isDirectory(samplesDir), "samples dir not found: $samplesDir")

        val golden = GoldenFile(GoldenFile.DEFAULT_ROOT.resolve("tokens"))
        val sampleFiles = Files.list(samplesDir).use { stream ->
            stream.filter { it.toString().endsWith(".yux") }.sorted().toList()
        }
        assertTrue(sampleFiles.isNotEmpty(), "no sample files found")

        for (file in sampleFiles) {
            val source = SourceFile(file.fileName.toString(), file.readText())
            val tokens = Lexer(source).tokenize()
            golden.assertMatches("${file.fileName}.tokens", TokenPrinter.print(tokens))
        }
    }
}
