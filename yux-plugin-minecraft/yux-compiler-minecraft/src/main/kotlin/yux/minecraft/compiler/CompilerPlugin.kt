package yux.minecraft.compiler

import yux.compiler.lexer.Keywords
import yux.compiler.lexer.TokenKind
import yux.compiler.parser.CstBlock
import yux.compiler.parser.Parser
import yux.compiler.plugin.PluginContext
import yux.compiler.plugin.YuxCompilerPlugin
import yux.minecraft.compiler.extension.CommandBlock
import yux.minecraft.compiler.extension.CommandPayload
import yux.minecraft.compiler.extension.ConfigField
import yux.minecraft.compiler.extension.ConfigPayload
import yux.minecraft.compiler.extension.ConfigValue
import yux.minecraft.compiler.extension.EventPayload
import yux.minecraft.compiler.extension.PermissionPayload
import yux.minecraft.compiler.extension.PluginPayload
import yux.minecraft.compiler.extension.TaskKind
import yux.minecraft.compiler.extension.TaskPayload
import yux.minecraft.compiler.lowering.MinecraftLowering

/**
 * YuxPlugin SDK 语言扩展层入口（T-M8-2 / 03-§3）：注册
 * `plugin`/`event`/`command`/`config`/`permission`/`task` 六个扩展关键字及其
 * 解析函数（[ExtensionParser]）、语法下沉（[MinecraftLowering]）与 plugin.yml
 * 生成钩子（[pluginYmlCodegenHook]）。
 *
 * 约束（02-§10.4）：扩展关键字不得与内置关键字冲突。`plugin/event/command/config/
 * permission/task` 已逐一核对不在 `Keywords.BASE`（30 个基础关键字）与
 * `Keywords.SOFT`（5 个软关键字）中；[configure] 仍保留防御性校验，若未来内置
 * 关键字扩展与之重叠会立即抛出（PluginManager 亦以 E0031 兜底，二者互不冲突）。
 */
class MinecraftCompilerPlugin : YuxCompilerPlugin {
    override val id = "yux-minecraft"
    override val version = "0.1.0"

    /** 共享收集器：下沉阶段填充 plugin.yml 元数据，代码生成钩子读取渲染（T-M8-12）。 */
    internal val collector = PluginYmlCollector()

    private val lowering = MinecraftLowering(collector)

    override fun configure(context: PluginContext) {
        val builtin = Keywords.BASE + Keywords.SOFT
        val conflict = EXTENSION_KEYWORDS.firstOrNull { it in builtin }
        require(conflict == null) {
            "扩展关键字 '$conflict' 与内置关键字冲突，无法注册（yux-minecraft 停止配置）"
        }
        context.registerKeyword("plugin") { parser, _ -> parsePlugin(parser) }
        context.registerKeyword("event") { parser, _ -> parseEvent(parser) }
        context.registerKeyword("command") { parser, _ -> parseCommand(parser) }
        context.registerKeyword("config") { parser, _ -> parseConfig(parser) }
        context.registerKeyword("permission") { parser, _ -> parsePermission(parser) }
        context.registerKeyword("task") { parser, _ -> parseTask(parser) }
        context.registerSyntaxTransform(lowering)
        context.registerCodegenHook(pluginYmlCodegenHook(collector))
    }
}

/** 本插件注册的全部扩展关键字。 */
private val EXTENSION_KEYWORDS: List<String> =
    listOf("plugin", "event", "command", "config", "permission", "task")

// ── plugin（03-§3.2）───────────────────────────────────────────────────────────

private fun parsePlugin(parser: Parser): PluginPayload {
    val name = parser.expectIdent("plugin 类名").text
    parser.expect(TokenKind.LBRACE, "'{'")
    var pluginName: String? = null
    var description: String? = null
    val authors = mutableListOf<String>()
    var onEnable: CstBlock? = null
    var onDisable: CstBlock? = null
    parser.skipNewlines()
    while (!parser.at(TokenKind.RBRACE) && !parser.at(TokenKind.EOF)) {
        val prop = parser.expectIdent("plugin 属性名").text
        when (prop) {
            "name" -> {
                parser.expect(TokenKind.ASSIGN, "'='")
                pluginName = stringValue(parser)
            }
            "description" -> {
                parser.expect(TokenKind.ASSIGN, "'='")
                description = stringValue(parser)
            }
            "authors" -> authors += stringList(parser)
            "onEnable" -> onEnable = parser.parseBlock()
            "onDisable" -> onDisable = parser.parseBlock()
            else -> {
                parser.error("未知 plugin 属性 '$prop'")
                skipToStatementEnd(parser)
            }
        }
        parser.skipNewlines()
    }
    parser.expect(TokenKind.RBRACE, "'}'")
    return PluginPayload(name, pluginName, description, authors, onEnable, onDisable)
}

// ── event（03-§3.3）───────────────────────────────────────────────────────────

private fun parseEvent(parser: Parser): EventPayload {
    val eventName = parser.expectIdent("事件名").text
    parser.expect(TokenKind.LPAREN, "'('")
    val params = mutableListOf<String>()
    parser.skipNewlines()
    while (!parser.at(TokenKind.RPAREN) && !parser.at(TokenKind.EOF)) {
        params += parser.expectIdent("事件参数名").text
        parser.skipNewlines()
        if (parser.at(TokenKind.COMMA)) {
            parser.advance()
            parser.skipNewlines()
        } else {
            break
        }
    }
    parser.expect(TokenKind.RPAREN, "')'")
    parser.skipNewlines()
    val body = parser.parseBlock()
    return EventPayload(eventName, params, body)
}

// ── command（03-§3.4）──────────────────────────────────────────────────────────

private fun parseCommand(parser: Parser): CommandPayload {
    val path = stringValue(parser)
    parser.expect(TokenKind.LBRACE, "'{'")
    var permission: String? = null
    var description: String? = null
    val aliases = mutableListOf<String>()
    var execute: CommandBlock? = null
    var tab: CommandBlock? = null
    parser.skipNewlines()
    while (!parser.at(TokenKind.RBRACE) && !parser.at(TokenKind.EOF)) {
        val prop = parser.expectIdent("command 属性名").text
        when (prop) {
            "permission" -> {
                parser.expect(TokenKind.ASSIGN, "'='")
                permission = stringValue(parser)
            }
            "description" -> {
                parser.expect(TokenKind.ASSIGN, "'='")
                description = stringValue(parser)
            }
            "aliases" -> aliases += stringList(parser)
            "execute" -> execute = commandBlock(parser)
            "tab" -> tab = commandBlock(parser)
            else -> {
                parser.error("未知 command 属性 '$prop'")
                skipToStatementEnd(parser)
            }
        }
        parser.skipNewlines()
    }
    parser.expect(TokenKind.RBRACE, "'}'")
    if (execute == null) parser.error("command '$path' 必须声明 execute")
    return CommandPayload(path, permission, description, aliases, execute, tab)
}

private fun commandBlock(parser: Parser): CommandBlock {
    parser.expect(TokenKind.LPAREN, "'('")
    parser.skipNewlines()
    val sender = parser.expectIdent("发送者参数名").text
    parser.expect(TokenKind.COMMA, "','")
    parser.skipNewlines()
    val args = parser.expectIdent("参数列表名").text
    parser.expect(TokenKind.RPAREN, "')'")
    parser.skipNewlines()
    val body = parser.parseBlock()
    return CommandBlock(sender, args, body)
}

// ── config（03-§3.5）───────────────────────────────────────────────────────────

private fun parseConfig(parser: Parser): ConfigPayload {
    val name = parser.expectIdent("config 名").text
    parser.expect(TokenKind.LBRACE, "'{'")
    val fields = mutableListOf<ConfigField>()
    parser.skipNewlines()
    while (!parser.at(TokenKind.RBRACE) && !parser.at(TokenKind.EOF)) {
        val fieldName = parser.expectIdent("config 字段名").text
        parser.expect(TokenKind.ASSIGN, "'='")
        val value = configValue(parser, fieldName)
        if (value != null) fields += ConfigField(fieldName, value)
        parser.skipNewlines()
    }
    parser.expect(TokenKind.RBRACE, "'}'")
    return ConfigPayload(name, fields)
}

private fun configValue(parser: Parser, fieldName: String): ConfigValue? {
    val token = parser.current
    return when (token.kind) {
        TokenKind.STRING_LITERAL -> {
            parser.advance()
            ConfigValue.Str(unquote(token.text))
        }
        TokenKind.INT_LITERAL -> {
            parser.advance()
            if (token.text.endsWith('L') || token.text.endsWith('l')) {
                ConfigValue.LongValue(token.text)
            } else {
                ConfigValue.IntValue(token.text)
            }
        }
        TokenKind.FLOAT_LITERAL -> {
            parser.advance()
            ConfigValue.DoubleValue(token.text)
        }
        TokenKind.KEYWORD -> when (token.text) {
            "true", "false" -> {
                parser.advance()
                ConfigValue.BoolValue(token.text == "true")
            }
            else -> {
                parser.error("config 字段 '$fieldName' 不支持关键字字面量 '${token.text}'")
                parser.advance()
                null
            }
        }
        TokenKind.LBRACKET -> {
            // v0.1 限制：List 默认值暂不支持（03-§3.5 支持嵌套/列表，留待后续）；
            // 报诊断并跳过该字段，避免生成无法初始化的 List 属性。
            parser.error(
                "config 字段 '$fieldName' 暂不支持 List 默认值（v0.1 仅支持 String/Int/Long/Double/Boolean 标量）",
            )
            skipBalancedBrackets(parser)
            null
        }
        else -> {
            parser.error("config 字段 '$fieldName' 期望字面量，实际是 '${token.text}'")
            parser.advance()
            null
        }
    }
}

// ── permission（03-§3.6）───────────────────────────────────────────────────────

private fun parsePermission(parser: Parser): PermissionPayload {
    val name = stringValue(parser)
    parser.expect(TokenKind.LBRACE, "'{'")
    var description: String? = null
    var default: String? = null
    val children = mutableListOf<String>()
    parser.skipNewlines()
    while (!parser.at(TokenKind.RBRACE) && !parser.at(TokenKind.EOF)) {
        val prop = parser.expectIdent("permission 属性名").text
        when (prop) {
            "description" -> {
                parser.expect(TokenKind.ASSIGN, "'='")
                description = stringValue(parser)
            }
            "default" -> {
                parser.expect(TokenKind.ASSIGN, "'='")
                val value = parser.expectIdent("默认值（OP/NOT_OP/TRUE/FALSE）").text
                default = value.lowercase()
            }
            "children" -> children += stringList(parser)
            else -> {
                parser.error("未知 permission 属性 '$prop'")
                skipToStatementEnd(parser)
            }
        }
        parser.skipNewlines()
    }
    parser.expect(TokenKind.RBRACE, "'}'")
    return PermissionPayload(name, description, default, children)
}

// ── task（03-§3.6 / §9.2）──────────────────────────────────────────────────────

private fun parseTask(parser: Parser): TaskPayload {
    // v0.1 偏差：`async` 修饰符位于 `task` 关键字**之后**（`task async delay(50)`），
    // 设计稿 03-§9.2 为 `async task delay(50)`；M8 采纳前者，见 TaskPayload KDoc。
    var async = false
    if (parser.atKeyword("async")) {
        async = true
        parser.advance()
    }
    val kindToken = parser.expectIdent("task 类型（repeat/delay）").text
    var kind = TaskKind.DELAY
    var tick = "0"
    when (kindToken) {
        "repeat" -> {
            parser.expect(TokenKind.LPAREN, "'('")
            parser.expectIdent("interval")
            parser.expect(TokenKind.COLON, "':'")
            tick = parser.expect(TokenKind.INT_LITERAL, "间隔 tick 数").text
            parser.expect(TokenKind.RPAREN, "')'")
            kind = TaskKind.REPEAT
        }
        "delay" -> {
            parser.expect(TokenKind.LPAREN, "'('")
            tick = parser.expect(TokenKind.INT_LITERAL, "延迟 tick 数").text
            parser.expect(TokenKind.RPAREN, "')'")
            kind = TaskKind.DELAY
        }
        else -> {
            parser.error("task 类型必须是 repeat 或 delay，实际是 '$kindToken'")
            // 错误恢复：跳过到块起始继续解析，避免语法级联
            while (!parser.at(TokenKind.LBRACE) && !parser.at(TokenKind.EOF)) parser.advance()
        }
    }
    parser.skipNewlines()
    val body = parser.parseBlock()
    return TaskPayload(async, kind, tick, body)
}

// ── 公共工具 ───────────────────────────────────────────────────────────────────

/** 读取一个字符串字面量并剥离外层引号（v0.1：不做转义解码）。 */
private fun stringValue(parser: Parser): String =
    unquote(parser.expect(TokenKind.STRING_LITERAL, "字符串字面量").text)

/** `[ "a", "b" ]` 字符串列表（先消费 `=`）。 */
private fun stringList(parser: Parser): List<String> {
    parser.expect(TokenKind.ASSIGN, "'='")
    parser.expect(TokenKind.LBRACKET, "'['")
    val items = mutableListOf<String>()
    parser.skipNewlines()
    while (!parser.at(TokenKind.RBRACKET) && !parser.at(TokenKind.EOF)) {
        items += stringValue(parser)
        parser.skipNewlines()
        if (parser.at(TokenKind.COMMA)) {
            parser.advance()
            parser.skipNewlines()
        } else {
            break
        }
    }
    parser.expect(TokenKind.RBRACKET, "']'")
    return items
}

/** 剥离字符串字面量外层引号（v0.1：值内转义原样保留，不做解码）。 */
private fun unquote(raw: String): String = raw.substring(1, raw.length - 1)

/** 跳过平衡方括号（config List 默认值错误恢复）。 */
private fun skipBalancedBrackets(parser: Parser) {
    var depth = 0
    while (!parser.at(TokenKind.EOF)) {
        when {
            parser.at(TokenKind.LBRACKET) -> {
                depth++
                parser.advance()
            }
            parser.at(TokenKind.RBRACKET) -> {
                parser.advance()
                depth--
                if (depth == 0) return
            }
            else -> parser.advance()
        }
    }
}

/** 跳过到语句结束（NEWLINE/`;`/`}`/EOF），未知属性错误恢复。 */
private fun skipToStatementEnd(parser: Parser) {
    while (!parser.at(TokenKind.NEWLINE) && !parser.at(TokenKind.SEMICOLON) &&
        !parser.at(TokenKind.RBRACE) && !parser.at(TokenKind.EOF)
    ) {
        parser.advance()
    }
}
