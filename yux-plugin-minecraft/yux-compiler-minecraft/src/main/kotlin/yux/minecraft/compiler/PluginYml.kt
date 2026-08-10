package yux.minecraft.compiler

import yux.compiler.plugin.CodegenHook
import yux.compiler.plugin.PluginArtifact

/**
 * plugin.yml 元数据收集器（T-M8-12 / 03-§8.1、03-§11）。
 *
 * 由下沉阶段填充（plugin/command/permission 三类声明），由 [renderPluginYml] 渲染为
 * `plugin.yml` 文本（纯文本渲染，不依赖 YAML 库）。
 */
class PluginYmlCollector {
    /**
     * plugin.yml 的 `main` 主类：来自 `plugin <Name>` 声明；模块中无 plugin 声明时
     * 回退为 `Main`（与 03-§4.2 的运行时入口约定一致，文档化回退）。
     */
    var pluginClassName: String = "Main"

    /** 显式 `permission "..."` 声明（源码顺序）。 */
    val permissions: MutableList<PermissionData> = mutableListOf()

    /** 带 `permission` 属性的 command 声明（源码顺序）。 */
    val commandPermissions: MutableList<CommandPermissionData> = mutableListOf()

    /** `command "..."` 声明（源码顺序）：渲染为 plugin.yml 的 `commands:` 节（Bukkit 注册前提）。 */
    val commands: MutableList<CommandEntryData> = mutableListOf()
}

/** 单个权限节点元数据（`permission "..."` 声明）。 */
class PermissionData(
    /** 权限名。 */
    val name: String,
    /** `description`（可选，仅非空时输出）。 */
    val description: String? = null,
    /** `default`（可选，已小写归一化，仅非空时输出）。 */
    val default: String? = null,
    /** `children` 子权限列表（可选，仅非空时输出）。 */
    val children: List<String> = emptyList(),
)

/** 带权限的 command 节点元数据。 */
class CommandPermissionData(
    /** 命令权限名。 */
    val permission: String,
    /** 命令路径（用于 `description: "command <path>"`）。 */
    val commandPath: String,
)

/** `commands:` 节单条命令元数据（`command "..."` 声明）。 */
data class CommandEntryData(
    /** 命令路径（未引号，如 `/sethome`；渲染时取末段去 `/` 作命令名）。 */
    val path: String,
    /** `description`（可选，仅非空时输出）。 */
    val description: String? = null,
    /** `permission`（可选，仅非空时输出）。 */
    val permission: String? = null,
    /** `aliases`（可选，仅非空时输出为内联列表）。 */
    val aliases: List<String> = emptyList(),
)

/**
 * 渲染 plugin.yml 文本（T-M8-12）：2 空格缩进、键保持源码顺序、仅输出非空可选节。
 *
 * ```
 * main: MyServer
 * permissions:
 *   myserver.admin:
 *     description: 管理员
 *     default: op
 *     children:
 *       myserver.home: true
 *   myserver.home:
 *     description: "command /home"
 * commands:
 *   sethome:
 *     description: 设置家
 *     permission: myserver.home
 *     aliases: [sh]
 * ```
 *
 * 重复权限名「先到先得」（仅首个节点输出）；`permission` 与 `command` 权限共用一个
 * 已输出集合去重；`commands:` 按命令名（路径末段）先到先得去重。
 */
fun renderPluginYml(collector: PluginYmlCollector): String {
    val lines = mutableListOf("main: ${collector.pluginClassName}")
    if (collector.permissions.isNotEmpty() || collector.commandPermissions.isNotEmpty()) {
        lines += "permissions:"
        val emitted = linkedSetOf<String>()
        for (permission in collector.permissions) {
            if (!emitted.add(permission.name)) continue
            lines += "  ${permission.name}:"
            permission.description?.let { lines += "    description: $it" }
            permission.default?.let { lines += "    default: $it" }
            if (permission.children.isNotEmpty()) {
                lines += "    children:"
                permission.children.forEach { child -> lines += "      $child: true" }
            }
        }
        for (command in collector.commandPermissions) {
            if (!emitted.add(command.permission)) continue
            lines += "  ${command.permission}:"
            lines += "    description: \"command ${command.commandPath}\""
        }
    }
    if (collector.commands.isNotEmpty()) {
        lines += "commands:"
        val emitted = linkedSetOf<String>()
        for (command in collector.commands) {
            val name = commandName(command.path)
            if (!emitted.add(name)) continue
            lines += "  $name:"
            command.description?.let { lines += "    description: $it" }
            command.permission?.let { lines += "    permission: $it" }
            if (command.aliases.isNotEmpty()) {
                lines += "    aliases: [${command.aliases.joinToString(", ")}]"
            }
        }
    }
    return lines.joinToString("\n")
}

/** 命令名：路径末段（`/sethome` → `sethome`；`admin/sethome` → `sethome`）。 */
private fun commandName(path: String): String = path.substringAfterLast('/').ifEmpty { path }

/** 代码生成钩子（T-M8-12）：把收集器渲染结果作为 `plugin.yml` 产物附加。 */
fun pluginYmlCodegenHook(collector: PluginYmlCollector): CodegenHook = CodegenHook { _, _ ->
    listOf(PluginArtifact("plugin.yml", renderPluginYml(collector).encodeToByteArray()))
}
