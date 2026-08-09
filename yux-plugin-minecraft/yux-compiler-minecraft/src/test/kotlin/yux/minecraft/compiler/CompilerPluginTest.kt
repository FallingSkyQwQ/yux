package yux.minecraft.compiler

import org.junit.jupiter.api.Test
import yux.compiler.Compiler
import yux.compiler.ast.YxBoolLiteral
import yux.compiler.ast.YxClass
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExtensionDecl
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxFunctionBody
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIntLiteral
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNamedType
import yux.compiler.ast.YxStringLiteral
import yux.compiler.ast.YxVarDecl
import yux.compiler.diag.DiagnosticSink
import yux.compiler.parser.AstPrinter
import yux.compiler.plugin.PluginManager
import yux.compiler.plugin.YuxCompilerPlugin
import yux.compiler.source.SourceFile
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8 语言扩展层测试（T-M8-2/3/4/5/6/12）：纯解析 + 下沉断言。
 *
 * 不引用 `yux-minecraft-runtime` 任何类型（集成属 M9）；验证每个扩展关键字
 * 解析 → 下沉为普通 AST 的形状、共享收集器（plugin.yml）的填充与渲染文本。
 */
class CompilerPluginTest {

    private data class ParseResult(
        val decls: List<YxDecl>,
        val diagnostics: DiagnosticSink,
        val plugin: MinecraftCompilerPlugin,
    )

    private fun parse(text: String): ParseResult {
        val manager = PluginManager()
        val plugin = MinecraftCompilerPlugin()
        manager.register(plugin)
        assertFalse(manager.diagnostics.hasErrors, "插件注册诊断: ${manager.diagnostics.diagnostics}")
        val diagnostics = DiagnosticSink()
        val decls = Compiler(diagnostics, manager).parseToDecls(SourceFile("main.yux", text))
        return ParseResult(decls, diagnostics, plugin)
    }

    private fun assertNoExtensionLeftover(result: ParseResult) {
        assertTrue(
            result.decls.none { it is YxExtensionDecl },
            "下沉后不应残留扩展节点: ${AstPrinter.dump(result.decls)}",
        )
    }

    private fun yxClass(decls: List<YxDecl>, name: String): YxClass =
        decls.filterIsInstance<YxClass>().single { it.name == name }

    // ── 插件标识与注册 ─────────────────────────────────────────────────────────

    @Test
    fun `插件 id 与版本`() {
        val plugin = MinecraftCompilerPlugin()
        assertEquals("yux-minecraft", plugin.id)
        assertEquals("0.1.0", plugin.version)
    }

    @Test
    fun `ServiceLoader 可发现插件`() {
        val plugins = ServiceLoader.load(YuxCompilerPlugin::class.java).toList()
        assertTrue(plugins.any { it.id == "yux-minecraft" }, "类路径应发现 yux-minecraft 插件")
    }

    @Test
    fun `META-INF services 文件存在且指向 CompilerPlugin`() {
        val resource = CompilerPluginTest::class.java.classLoader
            .getResource("META-INF/services/yux.compiler.plugin.YuxCompilerPlugin")
        assertNotNull(resource)
        assertTrue(resource.readText().contains("yux.minecraft.compiler.MinecraftCompilerPlugin"))
    }

    // ── plugin（T-M8-2）────────────────────────────────────────────────────────

    @Test
    fun `plugin 下沉为 PluginBootstrap 子类并覆盖生命周期钩子`() {
        val result = parse(
            """
            plugin MyServer {
                name = "MyServer"
                description = "服务器核心"
                authors = ["Youzi"]
                onEnable { print "启用" }
                onDisable { print "停用" }
            }
            """.trimIndent(),
        )
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        val cls = yxClass(result.decls, "MyServer")
        assertEquals(listOf("yux", "minecraft", "bootstrap", "PluginBootstrap"), (cls.superType as YxNamedType).segments)
        val enable = cls.members.filterIsInstance<YxFunction>().single { it.name == "onYuxEnable" }
        assertTrue(enable.isOverride)
        assertEquals(1, (enable.body as YxFunctionBody.YxBlockBody).block.statements.size)
        val disable = cls.members.filterIsInstance<YxFunction>().single { it.name == "onYuxDisable" }
        assertTrue(disable.isOverride)
        assertEquals("MyServer", result.plugin.collector.pluginClassName)
    }

    @Test
    fun `无 plugin 声明时收集器回退 main 为 Main`() {
        val result = parse("event PlayerJoin(player) { player.send \"hi\" }")
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertEquals("Main", result.plugin.collector.pluginClassName)
        assertEquals("main: Main", renderPluginYml(result.plugin.collector))
    }

    // ── event（T-M8-3）─────────────────────────────────────────────────────────

    @Test
    fun `event 下沉为 Listener 处理器并绑定事件对象属性`() {
        val result = parse(
            """
            event PlayerJoin(player) {
                player.send "欢迎"
            }
            """.trimIndent(),
        )
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        val cls = yxClass(result.decls, "PlayerJoinEvent_Handler")
        assertEquals(listOf("org", "bukkit", "event", "Listener"), (cls.interfaces.single() as YxNamedType).segments)
        val yuxEvent = cls.annotations.single()
        assertEquals(listOf("yux", "minecraft", "annotations", "YuxEvent"), yuxEvent.qualifiedName)
        val target = yuxEvent.args.single()
        assertEquals("target", target.name)
        assertEquals("\"PlayerJoinEvent\"", assertIs<YxStringLiteral>(target.value).text)
        val handle = cls.members.filterIsInstance<YxFunction>().single { it.name == "handle" }
        assertEquals(listOf("EventHandler"), handle.annotations.single().qualifiedName)
        assertEquals(listOf("PlayerJoinEvent"), (handle.params.single().type as YxNamedType).segments)
        val stmts = (handle.body as YxFunctionBody.YxBlockBody).block.statements
        assertEquals(2, stmts.size)
        val binding = assertIs<YxVarDecl>(stmts.first())
        assertEquals("player", binding.name)
        assertNull(binding.type)
        val member = assertIs<YxMemberAccess>(binding.initializer)
        assertEquals("player", member.name)
        assertEquals("e", assertIs<YxIdentifier>(member.receiver).name)
    }

    @Test
    fun `event 名已以 Event 结尾时不重复追加`() {
        val result = parse("event PlayerQuitEvent(p) { }")
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        val cls = yxClass(result.decls, "PlayerQuitEvent_Handler")
        assertEquals(
            "\"PlayerQuitEvent\"",
            assertIs<YxStringLiteral>(cls.annotations.single().args.single().value).text,
        )
        assertEquals(1, result.decls.filterIsInstance<YxClass>().size)
    }

    // ── command（T-M8-4）───────────────────────────────────────────────────────

    @Test
    fun `command 下沉为 YuxCommand 类含 execute 与 tab`() {
        val result = parse(
            """
            command "/home" {
                permission = "myserver.home"
                description = "传送到家"
                aliases = ["h"]
                execute(sender, args) { player.send "hi" }
                tab(sender, args) { return "home" }
            }
            """.trimIndent(),
        )
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        val cls = yxClass(result.decls, "HomeCommand")
        val ann = cls.annotations.single()
        assertEquals(listOf("yux", "minecraft", "annotations", "YuxCommand"), ann.qualifiedName)
        assertEquals("\"/home\"", assertIs<YxStringLiteral>(ann.args.first { it.name == "path" }.value).text)
        assertEquals("\"myserver.home\"", assertIs<YxStringLiteral>(ann.args.first { it.name == "permission" }.value).text)
        assertEquals("\"h\"", assertIs<YxStringLiteral>(ann.args.first { it.name == "aliases" }.value).text)
        val execute = cls.members.filterIsInstance<YxFunction>().single { it.name == "execute" }
        assertEquals(listOf("org", "bukkit", "command", "CommandSender"), (execute.params[0].type as YxNamedType).segments)
        val argsType = execute.params[1].type as YxNamedType
        assertEquals(listOf("List"), argsType.segments)
        assertEquals(listOf("String"), (argsType.typeArgs.single() as YxNamedType).segments)
        assertEquals(listOf("Boolean"), (execute.returnType as YxNamedType).segments)
        val tab = cls.members.filterIsInstance<YxFunction>().single { it.name == "tab" }
        val tabRet = tab.returnType as YxNamedType
        assertEquals(listOf("List"), tabRet.segments)
        assertEquals(listOf("String"), (tabRet.typeArgs.single() as YxNamedType).segments)
        assertEquals(1, result.plugin.collector.commandPermissions.size)
        assertEquals("myserver.home", result.plugin.collector.commandPermissions.single().permission)
        assertEquals("/home", result.plugin.collector.commandPermissions.single().commandPath)
    }

    @Test
    fun `command 路径首字母大写映射类名`() {
        val result = parse(
            """
            command "/home" { execute(sender, args) { } }
            command "/hello" { execute(sender, args) { } }
            command "/msg" { execute(sender, args) { } }
            """.trimIndent(),
        )
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        val names = result.decls.filterIsInstance<YxClass>().map { it.name }.toSet()
        assertEquals(setOf("HomeCommand", "HelloCommand", "MsgCommand"), names)
    }

    @Test
    fun `command 无 tab 时不生成 tab 成员`() {
        val result = parse("command \"/home\" { execute(sender, args) { } }")
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        val cls = yxClass(result.decls, "HomeCommand")
        val names = cls.members.filterIsInstance<YxFunction>().map { it.name }
        assertEquals(listOf("execute"), names)
        assertEquals(0, result.plugin.collector.commandPermissions.size)
    }

    // ── config（T-M8-5）────────────────────────────────────────────────────────

    @Test
    fun `config 下沉为 YuxConfig data 类并推导标量类型`() {
        val result = parse(
            """
            config Server {
                motd = "Welcome!"
                maxPlayers = 100
                timeout = 30L
                ratio = 1.5
                enabled = true
            }
            """.trimIndent(),
        )
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        val data = result.decls.filterIsInstance<YxDataClass>().single()
        assertEquals("Server", data.name)
        val ann = data.annotations.single()
        assertEquals(listOf("yux", "minecraft", "annotations", "YuxConfig"), ann.qualifiedName)
        assertEquals("\"Server\"", assertIs<YxStringLiteral>(ann.args.first { it.name == "name" }.value).text)
        assertEquals("\"config.yml\"", assertIs<YxStringLiteral>(ann.args.first { it.name == "path" }.value).text)
        assertEquals(
            "String",
            (data.properties.first { it.name == "motd" }.type as YxNamedType).segments.single(),
        )
        assertEquals(
            "Int",
            (data.properties.first { it.name == "maxPlayers" }.type as YxNamedType).segments.single(),
        )
        assertEquals(
            "Long",
            (data.properties.first { it.name == "timeout" }.type as YxNamedType).segments.single(),
        )
        assertEquals(
            "Double",
            (data.properties.first { it.name == "ratio" }.type as YxNamedType).segments.single(),
        )
        assertEquals(
            "Boolean",
            (data.properties.first { it.name == "enabled" }.type as YxNamedType).segments.single(),
        )
        val dump = AstPrinter.dump(result.decls)
        assertTrue(dump.contains("property motd: String = Str"), dump)
        assertTrue(dump.contains("property maxPlayers: Int = Int(100)"), dump)
        assertTrue(dump.contains("property timeout: Long = Int(30L)"), dump)
        assertTrue(dump.contains("property ratio: Double = Float(1.5)"), dump)
        assertTrue(dump.contains("property enabled: Boolean = Bool(true)"), dump)
    }

    @Test
    fun `config List 默认值报诊断且不崩溃`() {
        val result = parse("config Server { tags = [\"a\", \"b\"] }")
        assertTrue(result.diagnostics.hasErrors, "List 默认值应产生诊断")
        assertTrue(
            result.diagnostics.diagnostics.any { it.message.contains("List") },
            "诊断信息应说明 List 不支持: ${result.diagnostics.diagnostics}",
        )
        assertNoExtensionLeftover(result)
        // 字段被跳过，但仍产出空字段的 data 类
        val data = result.decls.filterIsInstance<YxDataClass>().single()
        assertTrue(data.properties.isEmpty())
    }

    // ── permission（T-M8-6）────────────────────────────────────────────────────

    @Test
    fun `permission 不产出类并收集权限元数据`() {
        val result = parse(
            """
            permission "myserver.admin" {
                description = "管理员"
                default = OP
                children = ["myserver.home", "myserver.bypass"]
            }
            """.trimIndent(),
        )
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertTrue(result.decls.isEmpty(), "permission 不应产出类")
        val perm = result.plugin.collector.permissions.single()
        assertEquals("myserver.admin", perm.name)
        assertEquals("管理员", perm.description)
        assertEquals("op", perm.default)
        assertEquals(listOf("myserver.home", "myserver.bypass"), perm.children)
    }

    // ── task（T-M8-6）──────────────────────────────────────────────────────────

    @Test
    fun `task repeat 下沉为 YuxTask 循环任务`() {
        val result = parse("task repeat(interval:20) { print \"tick\" }")
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        val cls = yxClass(result.decls, "Task_0_20")
        val ann = cls.annotations.single()
        assertEquals(listOf("yux", "minecraft", "annotations", "YuxTask"), ann.qualifiedName)
        assertEquals("0", assertIs<YxIntLiteral>(ann.args.first { it.name == "delay" }.value).text)
        assertEquals("20", assertIs<YxIntLiteral>(ann.args.first { it.name == "interval" }.value).text)
        assertFalse(assertIs<YxBoolLiteral>(ann.args.first { it.name == "async" }.value).value)
        assertEquals("run", cls.members.filterIsInstance<YxFunction>().single().name)
    }

    @Test
    fun `task delay 与 async 修饰符`() {
        val delay = parse("task delay(100) { }")
        assertFalse(delay.diagnostics.hasErrors, delay.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(delay)
        val delayCls = yxClass(delay.decls, "Task_100_0")
        assertEquals(
            "100",
            assertIs<YxIntLiteral>(delayCls.annotations.single().args.first { it.name == "delay" }.value).text,
        )
        assertEquals(
            "0",
            assertIs<YxIntLiteral>(delayCls.annotations.single().args.first { it.name == "interval" }.value).text,
        )
        val async = parse("task async delay(50) { }")
        assertFalse(async.diagnostics.hasErrors, async.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(async)
        val asyncCls = yxClass(async.decls, "Task_50_0_A")
        assertTrue(assertIs<YxBoolLiteral>(asyncCls.annotations.single().args.first { it.name == "async" }.value).value)
        assertEquals("Task_50_0_A", asyncCls.name)
    }

    // ── plugin.yml（T-M8-12）───────────────────────────────────────────────────

    @Test
    fun `pluginYml 渲染包含 main 权限 children default 与命令权限`() {
        val collector = PluginYmlCollector().apply {
            pluginClassName = "MyServer"
            permissions += PermissionData("myserver.admin", "管理员", "op", listOf("myserver.home", "myserver.bypass"))
            commandPermissions += CommandPermissionData("myserver.home", "/home")
        }
        assertEquals(
            """
            main: MyServer
            permissions:
              myserver.admin:
                description: 管理员
                default: op
                children:
                  myserver.home: true
                  myserver.bypass: true
              myserver.home:
                description: "command /home"
            """.trimIndent(),
            renderPluginYml(collector),
        )
    }

    @Test
    fun `pluginYml 渲染仅输出非空可选节`() {
        val collector = PluginYmlCollector().apply {
            permissions += PermissionData("a", null, null, emptyList())
            permissions += PermissionData("b", "描述", null, listOf("c"))
        }
        assertEquals(
            """
            main: Main
            permissions:
              a:
              b:
                description: 描述
                children:
                  c: true
            """.trimIndent(),
            renderPluginYml(collector),
        )
    }

    @Test
    fun `pluginYml 渲染重复权限名先到先得`() {
        val collector = PluginYmlCollector().apply {
            permissions += PermissionData("dup", "first", null, emptyList())
            permissions += PermissionData("dup", "second", null, emptyList())
        }
        assertEquals(
            """
            main: Main
            permissions:
              dup:
                description: first
            """.trimIndent(),
            renderPluginYml(collector),
        )
    }

    // ── 全量端到端 ─────────────────────────────────────────────────────────────

    @Test
    fun `全量源码解析后收集器填充且无扩展节点残留`() {
        val result = parse(
            """
            plugin MyServer {
                onEnable { print "ok" }
            }
            event PlayerJoin(player) { player.send "hi" }
            command "/home" {
                permission = "myserver.home"
                execute(sender, args) { sender.send "hi" }
            }
            config Server {
                motd = "Welcome!"
                maxPlayers = 100
            }
            permission "myserver.admin" {
                default = OP
                children = ["myserver.home"]
            }
            task async delay(50) { print "async" }
            task repeat(interval:20) { print "tick" }
            """.trimIndent(),
        )
        assertFalse(result.diagnostics.hasErrors, result.diagnostics.diagnostics.toString())
        assertNoExtensionLeftover(result)
        assertEquals(
            setOf("MyServer", "PlayerJoinEvent_Handler", "HomeCommand", "Task_50_0_A", "Task_0_20"),
            result.decls.filterIsInstance<YxClass>().map { it.name }.toSet(),
        )
        assertEquals("Server", result.decls.filterIsInstance<YxDataClass>().single().name)
        assertEquals("MyServer", result.plugin.collector.pluginClassName)
        assertEquals("myserver.admin", result.plugin.collector.permissions.single().name)
        assertEquals("op", result.plugin.collector.permissions.single().default)
        assertEquals(listOf("myserver.home"), result.plugin.collector.permissions.single().children)
        assertEquals("myserver.home", result.plugin.collector.commandPermissions.single().permission)
        assertEquals("/home", result.plugin.collector.commandPermissions.single().commandPath)
    }
}
