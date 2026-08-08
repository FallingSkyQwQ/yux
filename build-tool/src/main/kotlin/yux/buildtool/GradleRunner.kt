package yux.buildtool

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Gradle 调用结果：退出码 + 合并的 stdout/stderr 输出。 */
data class GradleResult(val exitCode: Int, val output: String)

/**
 * 托管 Gradle 调用器（T-M7-4）：定位 gradle 可执行文件，以 `-p <workDir>` 运行指定任务。
 *
 * 支持 `--offline`（禁止访问网络仓库）与 `--no-daemon`（不驻留守护进程）开关；
 * stdout/stderr 合并为单一输出串。输出通过读取线程边执行边消费，避免大输出撑满管道导致死锁。
 */
class GradleRunner(
    /** 显式指定 gradle 可执行文件；null → [locateGradle] 自动定位。 */
    private val gradleBinary: String? = null,
    /** 以 `--offline` 运行（禁止访问网络仓库）。 */
    private val offline: Boolean = false,
    /** 以 `--no-daemon` 运行（不驻留守护进程）。 */
    private val noDaemon: Boolean = false,
) {

    /** 在 [workDir] 下运行 Gradle [tasks]；合并 stdout/stderr 为单一输出。 */
    fun run(workDir: Path, vararg tasks: String): GradleResult {
        val command = buildList {
            add(gradleBinary ?: locateGradle(workDir))
            add("-p")
            add(workDir.toString())
            if (offline) add("--offline")
            if (noDaemon) add("--no-daemon")
            addAll(tasks)
        }
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return GradleResult(-1, "无法启动 Gradle: ${e.message}")
        }
        // 读取线程：与进程并发消费输出，避免管道写满阻塞进程。
        val output = ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input -> input.copyTo(output) }
        }
        reader.start()
        val exitCode = process.waitFor()
        reader.join()
        return GradleResult(exitCode, output.toString(Charsets.UTF_8))
    }

    companion object {
        /** 定位顺序：YUX_GRADLE 环境变量 → GRADLE_HOME/bin/gradle → 项目树向上找 gradlew → PATH 上的 gradle。 */
        fun locateGradle(projectDir: Path): String {
            val env = System.getenv("YUX_GRADLE")
            if (!env.isNullOrBlank()) return env
            val gradleHome = System.getenv("GRADLE_HOME")
            if (!gradleHome.isNullOrBlank()) {
                val candidate = Path.of(gradleHome, "bin", "gradle")
                if (Files.isRegularFile(candidate)) return candidate.toString()
            }
            var current: Path? = projectDir.toAbsolutePath()
            while (current != null) {
                val script = if (isWindows()) current.resolve("gradlew.bat") else current.resolve("gradlew")
                if (Files.isRegularFile(script)) return script.toString()
                current = current.parent
            }
            return "gradle"
        }

        /** 当前平台是否 Windows（决定 gradlew 脚本后缀）。 */
        private fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("windows")
    }
}
