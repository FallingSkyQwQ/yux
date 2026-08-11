package yux.cli

/**
 * ANSI 颜色支持（M16，T-M16-4）：tty 自动检测 + `NO_COLOR`/`TERM=dumb` 禁用 + `--color` 覆盖。
 * 被管道重定向（测试/CI）时默认关闭，保证机器可读输出稳定。
 */
object Ansi {
    /** `--color` 选项取值。 */
    enum class ColorMode {
        AUTO,
        ALWAYS,
        NEVER,
    }

    /** 自动检测：仅交互终端且未显式禁用时启用。 */
    private fun autoEnabled(): Boolean {
        if (System.getenv("NO_COLOR") != null) return false
        if (System.getenv("TERM") == "dumb") return false
        return System.console() != null
    }

    /** 按模式构造颜色渲染器；null（未指定）按 AUTO 处理。 */
    fun colors(mode: ColorMode?): Colors =
        when (mode ?: ColorMode.AUTO) {
            ColorMode.ALWAYS -> Colors(true)
            ColorMode.NEVER -> Colors(false)
            ColorMode.AUTO -> Colors(autoEnabled())
        }
}

/** 颜色渲染器（enabled=false 时输出原样文本）。 */
class Colors(
    private val on: Boolean,
) {
    fun red(s: String): String = if (on) "\u001b[31m$s\u001b[0m" else s

    fun green(s: String): String = if (on) "\u001b[32m$s\u001b[0m" else s

    fun yellow(s: String): String = if (on) "\u001b[33m$s\u001b[0m" else s

    fun cyan(s: String): String = if (on) "\u001b[36m$s\u001b[0m" else s

    fun bold(s: String): String = if (on) "\u001b[1m$s\u001b[0m" else s
}
