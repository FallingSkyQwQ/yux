package yux.compiler.plugin

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * 插件加载（02-§10.3 / T-M6-3）：ServiceLoader 发现 [YuxCompilerPlugin]。
 *
 * - 类路径加载：`ServiceLoader.load`（测试/嵌入场景）；
 * - jar 加载：URLClassLoader（隔离加载器）包裹插件 jar，父加载器为编译核心。
 */
object PluginLoader {

    /** 从当前类路径发现插件（ServiceLoader）。 */
    fun loadFromClasspath(): List<YuxCompilerPlugin> =
        ServiceLoader.load(YuxCompilerPlugin::class.java).toList()

    /** 从单个插件 jar 发现插件；父加载器为编译核心，保证 SPI/核心类型共享。 */
    fun loadFromJar(jar: Path): List<YuxCompilerPlugin> {
        val loader = URLClassLoader(
            arrayOf(jar.toUri().toURL()),
            PluginLoader::class.java.classLoader,
        )
        return ServiceLoader.load(YuxCompilerPlugin::class.java, loader).toList()
    }

    /** 从多个插件 jar 加载并合并。 */
    fun loadFromJars(jars: List<Path>): List<YuxCompilerPlugin> =
        jars.flatMap { loadFromJar(it) }
}
