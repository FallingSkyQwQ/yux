package yux.minecraft.compiler.extension

import yux.compiler.parser.CstBlock

/**
 * M8 载荷数据类（T-M8-2 / 03-§3）：扩展关键字 [ExtensionParser] 的解析产出。
 *
 * 载荷是「不透明」的：解析阶段只收集领域信息，领域块体以 [CstBlock]（`parseBlock()`
 * 的原始产物）形式保存，下沉阶段（[yux.minecraft.compiler.lowering.MinecraftLowering]）
 * 用 `CstToAst.convertBlock` 转换为普通 AST（02-§10.2.1）。
 *
 * 字符串字段均为**未引号**的源码值（v0.1：仅剥离外层引号，值内转义不做解码）；
 * 下沉生成注解实参时再按 Yux 字符串字面量规则重新加引号。
 */

/** `plugin <Name> { ... }` 声明载荷。 */
class PluginPayload(
    /** `plugin <Name>` 中的类名（IDENT）。 */
    val name: String,
    /** `name = "..."` 属性（覆盖 build.yml 名称的元数据，v0.1 不参与下沉）。 */
    val pluginName: String?,
    /** `description = "..."`。 */
    val description: String?,
    /** `authors = ["a", "b"]`。 */
    val authors: List<String>,
    /** `onEnable { BODY }` 生命周期钩子（可选）。 */
    val onEnable: CstBlock?,
    /** `onDisable { BODY }` 生命周期钩子（可选）。 */
    val onDisable: CstBlock?,
)

/** `event <EventName> ( param, ... ) { BODY }` 声明载荷。 */
class EventPayload(
    /** 事件名（IDENT，如 `PlayerJoin`）。 */
    val eventName: String,
    /** 参数列表：裸 IDENT，按名绑定事件对象属性（`e.<param>`）。 */
    val params: List<String>,
    /** 处理器体。 */
    val body: CstBlock,
)

/** `command <"路径"> { ... }` 声明载荷。 */
class CommandPayload(
    /** 命令路径（未引号，如 `/home`）。 */
    val path: String,
    /** `permission = "..."`（可选）。 */
    val permission: String?,
    /** `description = "..."`（可选）。 */
    val description: String?,
    /** `aliases = ["h"]`。 */
    val aliases: List<String>,
    /** `execute ( sender , args ) { BODY }`（必填）。 */
    val execute: CommandBlock?,
    /** `tab ( sender , args ) { BODY }`（可选）。 */
    val tab: CommandBlock?,
)

/** command 的 `execute`/`tab` 函数体（参数名固定为 sender/args）。 */
class CommandBlock(
    /** 发送者参数名（源码书写名）。 */
    val senderName: String,
    /** 参数列表名（源码书写名）。 */
    val argsName: String,
    val body: CstBlock,
)

/** `config <Name> { field = <literal> ; ... }` 声明载荷。 */
class ConfigPayload(
    /** config 名（同时是生成的 data 类名）。 */
    val name: String,
    val fields: List<ConfigField>,
)

/** config 单个字段。 */
class ConfigField(
    val name: String,
    val value: ConfigValue,
)

/**
 * config 字段字面量值（v0.1 仅支持标量；List 默认值在解析期报诊断并跳过该字段，
 * 见 [yux.minecraft.compiler.CompilerPlugin] 的 config 解析器 KDoc）。
 */
sealed interface ConfigValue {
    class Str(val text: String) : ConfigValue
    class IntValue(val text: String) : ConfigValue
    class LongValue(val text: String) : ConfigValue
    class DoubleValue(val text: String) : ConfigValue
    class BoolValue(val value: Boolean) : ConfigValue
}

/** `permission <"名称"> { ... }` 声明载荷（不产出类，仅收集元数据）。 */
class PermissionPayload(
    /** 权限名（未引号）。 */
    val name: String,
    /** `description = "..."`（可选）。 */
    val description: String?,
    /** `default = OP|NOT_OP|TRUE|FALSE` 归一化为小写（`op`/`not_op`/`true`/`false`）。 */
    val default: String?,
    /** `children = ["a", "b"]`。 */
    val children: List<String>,
)

/** task 类型：`repeat(interval: Int)` 循环 / `delay(Int)` 延迟。 */
enum class TaskKind {
    REPEAT,
    DELAY,
}

/**
 * `task [async] ( repeat ( interval : <Int> ) | delay ( <Int> ) ) { BODY }` 载荷。
 *
 * v0.1 偏差（KDoc 记录，设计稿 03-§9.2 为 `async task delay(50)`）：`async` 修饰符
 * 放在 `task` 关键字**之后**（`task async delay(50)`），下沉为 `@YuxTask(async = true)`。
 */
class TaskPayload(
    val async: Boolean,
    val kind: TaskKind,
    /** tick 原始文本（INT_LITERAL 的 token 文本，如 `20`/`100`）。 */
    val tick: String,
    val body: CstBlock,
)
