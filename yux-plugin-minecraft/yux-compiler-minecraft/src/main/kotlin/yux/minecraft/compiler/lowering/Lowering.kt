package yux.minecraft.compiler.lowering

import yux.compiler.ast.SourceSpan
import yux.compiler.ast.YxAnnotation
import yux.compiler.ast.YxAnnotationArg
import yux.compiler.ast.YxBlock
import yux.compiler.ast.YxBoolLiteral
import yux.compiler.ast.YxClass
import yux.compiler.ast.YxClassMember
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExtensionDecl
import yux.compiler.ast.YxFloatLiteral
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxFunctionBody
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIntLiteral
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNamedType
import yux.compiler.ast.YxParameter
import yux.compiler.ast.YxProperty
import yux.compiler.ast.YxReturn
import yux.compiler.ast.YxStringLiteral
import yux.compiler.ast.YxType
import yux.compiler.ast.YxVarDecl
import yux.compiler.ast.YxVisibility
import yux.compiler.parser.CstBlock
import yux.compiler.parser.CstToAst
import yux.compiler.plugin.SyntaxTransform
import yux.minecraft.compiler.CommandEntryData
import yux.minecraft.compiler.CommandPermissionData
import yux.minecraft.compiler.PermissionData
import yux.minecraft.compiler.PluginYmlCollector
import yux.minecraft.compiler.extension.CommandBlock
import yux.minecraft.compiler.extension.CommandPayload
import yux.minecraft.compiler.extension.ConfigPayload
import yux.minecraft.compiler.extension.ConfigValue
import yux.minecraft.compiler.extension.EventPayload
import yux.minecraft.compiler.extension.PermissionPayload
import yux.minecraft.compiler.extension.PluginPayload
import yux.minecraft.compiler.extension.TaskKind
import yux.minecraft.compiler.extension.TaskPayload

/**
 * M8 语法下沉（T-M8-3/4/5/6 / 02-§10.2.2）：把 [YxExtensionDecl] 变换为普通 AST。
 *
 * 与运行时层的解耦契约（03-§4.2）：本层只生成带 SDK 注解
 * （`@YuxEvent`/`@YuxCommand`/`@YuxConfig`/`@YuxTask`）与 `PluginBootstrap` 继承的
 * 普通 Yux 类，运行时层按注解契约注册；本模块不依赖运行时模块（编译期类型由语义
 * 分析期经 classpath 提供，属 M9 集成范畴）。
 *
 * 语义规则（SemanticRule）刻意不注册：领域类型约束（事件类存在性、字段可赋值性等）
 * 由 M3 类型检查器在**下沉后的普通 AST** 上完成，领域专属规则留待 M9 集成期补充。
 */
// allow: SIZE_OK — 单一职责的下沉变换类（六个扩展关键字共享 collector/计数器状态，
// 拆分会引入跨类管线；既有规模 338 行，本修复 +16 行）。
class MinecraftLowering(
    private val collector: PluginYmlCollector,
    private val cstToAst: CstToAst = CstToAst(),
) : SyntaxTransform {

    /** 本编译单元已占用的类名：碰撞时追加 `_<n>` 后缀，保证同编译单元类名唯一。 */
    private val usedClassNames = mutableSetOf<String>()

    /** 已下沉的 config 类数量：首个保持 "config.yml"（兼容既有插件），后续派生独立路径。 */
    private var configCount = 0

    /** 下沉分发：不匹配的关键字返回 null（留给其他插件，02-§10.4）。 */
    override fun transform(node: YxExtensionDecl): List<YxDecl>? = when (node.keyword) {
        "plugin" -> lowerPlugin(node)
        "event" -> lowerEvent(node)
        "command" -> lowerCommand(node)
        "config" -> lowerConfig(node)
        "permission" -> {
            lowerPermission(node)
            emptyList()
        }
        "task" -> lowerTask(node)
        else -> null
    }

    // ── plugin（T-M8-2）：`class <Name> extends PluginBootstrap` ───────────────

    private fun lowerPlugin(node: YxExtensionDecl): List<YxDecl>? {
        val payload = node.payload as? PluginPayload ?: return null
        val span = node.span
        collector.pluginClassName = payload.name
        val members = mutableListOf<YxClassMember>()
        payload.onEnable?.let { block ->
            members += lifecycleFunction("onYuxEnable", block, span)
        }
        payload.onDisable?.let { block ->
            members += lifecycleFunction("onYuxDisable", block, span)
        }
        return listOf(
            YxClass(
                name = payload.name,
                typeParams = emptyList(),
                superType = namedType(listOf("yux", "minecraft", "bootstrap", "PluginBootstrap"), span),
                interfaces = emptyList(),
                members = members,
                visibility = YxVisibility.PUBLIC,
                annotations = emptyList(),
                span = span,
            ),
        )
    }

    private fun lifecycleFunction(name: String, block: CstBlock, span: SourceSpan): YxFunction =
        YxFunction(
            name = name,
            typeParams = emptyList(),
            params = emptyList(),
            returnType = null,
            body = blockBody(block, span),
            isAsync = false,
            isOverride = true,
            visibility = YxVisibility.PUBLIC,
            annotations = emptyList(),
            span = span,
        )

    // ── event（T-M8-3）：`@YuxEvent` + Listener 实现 + 参数绑定 ───────────────

    private fun lowerEvent(node: YxExtensionDecl): List<YxDecl>? {
        val payload = node.payload as? EventPayload ?: return null
        val span = node.span
        val eventClass = if (payload.eventName.endsWith("Event")) payload.eventName else payload.eventName + "Event"
        val handlerClass = "${eventClass}_Handler"
        val bindings = payload.params.map { param ->
            YxVarDecl(
                name = param,
                type = null,
                initializer = YxMemberAccess(YxIdentifier("e", span), param, span),
                span = span,
            )
        }
        val body = cstToAst.convertBlock(payload.body)
        val handle = YxFunction(
            name = "handle",
            typeParams = emptyList(),
            params = listOf(
                YxParameter(name = "e", type = namedType(listOf(eventClass), span), defaultValue = null, annotations = emptyList(), span = span),
            ),
            returnType = null,
            body = YxFunctionBody.YxBlockBody(YxBlock(bindings + body.statements, span), span),
            isAsync = false,
            isOverride = false,
            visibility = YxVisibility.PUBLIC,
            annotations = listOf(
                // 必须用 Bukkit 全限定名：SimplePluginManager 按 `Lorg/bukkit/event/EventHandler;`
                // 扫描监听器；单段名会发射为默认包描述符 `LEventHandler;` → 事件永不触发。
                YxAnnotation(qualifiedName = listOf("org", "bukkit", "event", "EventHandler"), args = emptyList(), span = span),
            ),
            span = span,
        )
        return listOf(
            YxClass(
                name = handlerClass,
                typeParams = emptyList(),
                superType = null,
                interfaces = listOf(namedType(listOf("org", "bukkit", "event", "Listener"), span)),
                members = listOf(handle),
                visibility = YxVisibility.PUBLIC,
                annotations = listOf(
                    YxAnnotation(
                        qualifiedName = listOf("yux", "minecraft", "annotations", "YuxEvent"),
                        args = listOf(YxAnnotationArg("target", stringLiteral(eventClass, span), span)),
                        span = span,
                    ),
                ),
                span = span,
            ),
        )
    }

    // ── command（T-M8-4）：`@YuxCommand` + execute/tab 函数 ───────────────────

    private fun lowerCommand(node: YxExtensionDecl): List<YxDecl>? {
        val payload = node.payload as? CommandPayload ?: return null
        val span = node.span
        payload.permission?.let { collector.commandPermissions += CommandPermissionData(it, payload.path) }
        collector.commands += CommandEntryData(payload.path, payload.description, payload.permission, payload.aliases)
        val annotationArgs = buildList {
            add(YxAnnotationArg("path", stringLiteral(payload.path, span), span))
            payload.permission?.let { add(YxAnnotationArg("permission", stringLiteral(it, span), span)) }
            if (payload.aliases.isNotEmpty()) {
                add(YxAnnotationArg("aliases", stringLiteral(payload.aliases.joinToString(","), span), span))
            }
        }
        val members = mutableListOf<YxClassMember>()
        payload.execute?.let { members += executorFunction(it, span) }
        payload.tab?.let { members += tabFunction(it, span) }
        return listOf(
            YxClass(
                name = uniqueClassName(commandClassName(payload.path)),
                typeParams = emptyList(),
                superType = null,
                interfaces = emptyList(),
                members = members,
                visibility = YxVisibility.PUBLIC,
                annotations = listOf(
                    YxAnnotation(qualifiedName = listOf("yux", "minecraft", "annotations", "YuxCommand"), args = annotationArgs, span = span),
                ),
                span = span,
            ),
        )
    }

    private fun executorFunction(block: CommandBlock, span: SourceSpan): YxFunction =
        YxFunction(
            name = "execute",
            typeParams = emptyList(),
            params = senderArgsParams(block, span),
            returnType = namedType(listOf("Boolean"), span),
            body = executorBody(block, span),
            isAsync = false,
            isOverride = false,
            visibility = YxVisibility.PUBLIC,
            annotations = emptyList(),
            span = span,
        )

    /** execute 主体追加 `return true`（03-§3.4：返回 true 表示已处理）。 */
    private fun executorBody(block: CommandBlock, span: SourceSpan): YxFunctionBody {
        val converted = cstToAst.convertBlock(block.body)
        val statements = converted.statements + YxReturn(YxBoolLiteral(true, span), span)
        return YxFunctionBody.YxBlockBody(YxBlock(statements, span), span)
    }

    private fun tabFunction(block: CommandBlock, span: SourceSpan): YxFunction =
        YxFunction(
            name = "tab",
            typeParams = emptyList(),
            params = senderArgsParams(block, span),
            returnType = listStringType(span),
            body = blockBody(block.body, span),
            isAsync = false,
            isOverride = false,
            visibility = YxVisibility.PUBLIC,
            annotations = emptyList(),
            span = span,
        )

    private fun senderArgsParams(block: CommandBlock, span: SourceSpan): List<YxParameter> = listOf(
        YxParameter(block.senderName, namedType(listOf("org", "bukkit", "command", "CommandSender"), span), null, emptyList(), span),
        YxParameter(block.argsName, listStringType(span), null, emptyList(), span),
    )

    // ── config（T-M8-5）：`@YuxConfig` + data 类（字段默认值即字面量） ────────

    private fun lowerConfig(node: YxExtensionDecl): List<YxDecl>? {
        val payload = node.payload as? ConfigPayload ?: return null
        val span = node.span
        val properties = payload.fields.map { field ->
            val (type, initializer) = when (val value = field.value) {
                is ConfigValue.Str -> namedType(listOf("String"), span) to YxStringLiteral(quote(value.text), span)
                is ConfigValue.IntValue -> namedType(listOf("Int"), span) to YxIntLiteral(value.text, span)
                is ConfigValue.LongValue -> namedType(listOf("Long"), span) to YxIntLiteral(value.text, span)
                is ConfigValue.DoubleValue -> namedType(listOf("Double"), span) to YxFloatLiteral(value.text, span)
                is ConfigValue.BoolValue -> namedType(listOf("Boolean"), span) to YxBoolLiteral(value.value, span)
            }
            YxProperty(
                name = field.name,
                type = type,
                initializer = initializer,
                accessors = emptyList(),
                annotations = emptyList(),
                isTopLevel = false,
                span = span,
            )
        }
        return listOf(
            YxDataClass(
                name = payload.name,
                typeParams = emptyList(),
                superType = null,
                interfaces = emptyList(),
                members = properties,
                visibility = YxVisibility.PUBLIC,
                annotations = listOf(
                    YxAnnotation(
                        qualifiedName = listOf("yux", "minecraft", "annotations", "YuxConfig"),
                        args = listOf(
                            YxAnnotationArg("name", stringLiteral(payload.name, span), span),
                            YxAnnotationArg("path", stringLiteral(configPath(payload.name), span), span),
                        ),
                        span = span,
                    ),
                ),
                span = span,
            ),
        )
    }

    // ── permission（T-M8-6）：不产出类，仅收集元数据 ─────────────────────────

    private fun lowerPermission(node: YxExtensionDecl) {
        val payload = node.payload as? PermissionPayload ?: return
        collector.permissions += PermissionData(payload.name, payload.description, payload.default, payload.children)
    }

    // ── task（T-M8-6）：`@YuxTask` + `fun run()` ──────────────────────────────

    private fun lowerTask(node: YxExtensionDecl): List<YxDecl>? {
        val payload = node.payload as? TaskPayload ?: return null
        val span = node.span
        val interval = if (payload.kind == TaskKind.REPEAT) payload.tick else "0"
        val delay = if (payload.kind == TaskKind.DELAY) payload.tick else "0"
        val suffix = if (payload.async) "_A" else ""
        return listOf(
            YxClass(
                name = uniqueClassName("Task_${delay}_$interval$suffix"),
                typeParams = emptyList(),
                superType = null,
                interfaces = emptyList(),
                members = listOf(
                    YxFunction(
                        name = "run",
                        typeParams = emptyList(),
                        params = emptyList(),
                        returnType = null,
                        body = blockBody(payload.body, span),
                        isAsync = false,
                        isOverride = false,
                        visibility = YxVisibility.PUBLIC,
                        annotations = emptyList(),
                        span = span,
                    ),
                ),
                visibility = YxVisibility.PUBLIC,
                annotations = listOf(
                    YxAnnotation(
                        qualifiedName = listOf("yux", "minecraft", "annotations", "YuxTask"),
                        args = listOf(
                            YxAnnotationArg("delay", YxIntLiteral(delay, span), span),
                            YxAnnotationArg("interval", YxIntLiteral(interval, span), span),
                            YxAnnotationArg("async", YxBoolLiteral(payload.async, span), span),
                        ),
                        span = span,
                    ),
                ),
                span = span,
            ),
        )
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun blockBody(block: CstBlock, span: SourceSpan): YxFunctionBody =
        YxFunctionBody.YxBlockBody(cstToAst.convertBlock(block), span)

    private fun namedType(segments: List<String>, span: SourceSpan): YxNamedType =
        YxNamedType(segments, emptyList(), nullable = false, span = span)

    private fun listStringType(span: SourceSpan): YxNamedType =
        YxNamedType(listOf("List"), listOf<YxType>(namedType(listOf("String"), span)), nullable = false, span = span)

    private fun stringLiteral(value: String, span: SourceSpan): YxStringLiteral =
        YxStringLiteral(quote(value), span)

    /** 命令类名：路径 '/' 后段首字母大写 + `Command`（`/home` → HomeCommand）。 */
    private fun commandClassName(path: String): String {
        val segment = path.substringAfterLast('/').ifEmpty { path }
        return segment.replaceFirstChar { it.uppercase() } + "Command"
    }

    /** 配置文件名：首个 config 保持 "config.yml"（兼容既有插件），后续按类名派生避免互污染。 */
    private fun configPath(name: String): String {
        configCount += 1
        return if (configCount == 1) "config.yml" else "config_$name.yml"
    }

    /** 编译单元内唯一类名：首次用原名，碰撞追加 `_<n>` 自增序号。 */
    private fun uniqueClassName(base: String): String {
        if (usedClassNames.add(base)) return base
        var n = 1
        while (!usedClassNames.add("${base}_$n")) n++
        return "${base}_$n"
    }

    /** 值重新加引号为 Yux 字符串字面量（转义 `\`/`"`/换行/`$`，词法器以 `$` 起插值）。 */
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> append(c)
            }
        }
        append('"')
    }
}
