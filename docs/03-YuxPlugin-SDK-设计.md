# YuxPlugin SDK 设计

> 前置：`01-Yux-语法规范.md` §9（语言扩展点）、`02-Yux-编译器架构.md` §10（编译器插件 API）。
> 定位：不是语言本体，而是类似 Spring Boot / Android SDK 的领域开发套件（Design.md §19）。
> 目标：用最少的 Yux 代码写出生产级 Paper 插件。

## 目录

1. 双层面架构
2. 项目结构
3. 语言扩展层（yux-compiler-minecraft）
4. 运行时层（yux-minecraft-runtime）
5. 语法展开规则（事件/命令/配置）
6. 序列化与 NBT
7. Paper API 包装
8. 权限 / 占位符 / 国际化
9. 生命周期与调度
10. 模块 API 参考
11. 完整映射表

---

# 1. 双层面架构

YuxPlugin SDK = **语言扩展层**（编译器插件）+ **运行时层**（JVM 库）。

```
┌─────────────────────────────────────────────────────┐
│  yux-compiler-minecraft（编译期）                     │
│  注册关键字：plugin / event / command / config        │
│  语法下沉 → 生成 plugin.yml / Main.class 骨架 / 注册码 │
└──────────────────────────┬──────────────────────────┘
                           │ 生成普通 Yux AST + .class
                           ▼
┌─────────────────────────────────────────────────────┐
│  yux-minecraft-runtime（运行期）                      │
│  PluginBootstrap / EventBus / CommandRegistry /     │
│  ConfigManager / NBT 序列化 / Paper 包装             │
└─────────────────────────────────────────────────────┘
```

| 层面 | 依赖 | 职责 |
|---|---|---|
| 语言扩展层 | `yux-compiler-plugin-api` + Paper API（编译期类型） | 注入领域语法、生成注册代码与 `plugin.yml` |
| 运行时层 | Paper API + `yux.core` | 运行期注册、序列化、调度 |

两者版本同步发布（SDK 版本 = Yux 语言版本 + Paper 版本矩阵）。

---

# 2. 项目结构

```
MyServer/
├── build.yml               # plugins.minecraft 启用扩展
├── src/
│   ├── main.yux            # plugin 入口 + 事件 + 命令
│   ├── HomeCommand.yux
│   └── config.yux          # config Server { ... }
├── resources/
└── tests/
```

```yaml
# build.yml
name: MyServer
version: 1.0
language:
  yux: 1.0
target:
  jvm: 21
dependencies:
  minecraft:
    paper: 1.21
plugins:
  minecraft: true
```

构建产物：

```
target/MyServer-1.0.jar
    ├── plugin.yml          # 自动生成
    ├── com/example/MyServer.class
    ├── ... 其他 .class
    └── lib/                # 依赖（或 shade）
```

---

# 3. 语言扩展层（yux-compiler-minecraft）

## 3.1 注册的扩展关键字

```
plugin   event   command   config   permission  task
```

（基础关键字仍为 30 个；以上为扩展注入，§01-9。）

## 3.2 plugin 声明

```yux
plugin MyServer {
    name = "MyServer"          // 覆盖 build.yml 名称
    description = "服务器核心"
    authors = ["Youzi"]

    onEnable {                  // 钩子：等价 JavaPlugin.onEnable()
        print "MyServer 已启用"
    }
    onDisable { }
}
```

**展开规则（SDK-M1）：**

- `plugin MyServer {}` → 类 `MyServer extends JavaPlugin`。
- 实例化生命周期钩子 `onEnable`/`onDisable` → 覆盖对应方法。
- 自动注册该插件内全部 `event`/`command`/`config`。

生成（示意 Java 等价物）：

```java
public class MyServer extends JavaPlugin {
    @Override public void onEnable() { /* 注册全部事件/命令/配置 */ }
}
```

## 3.3 event 声明

```yux
event PlayerJoin(player) {
    player.send "欢迎 ${player.name} 加入服务器"
}

event BlockBreak(block, player) {
    if player.hasPermission("mine.blockbreak") {
        block.setType(AIR)
    }
}
```

**展开规则（SDK-M2）：**

`event <EventName>(<params>) { body }` → 生成内部类 `Listener` 实现 + `@EventHandler` 方法：

```java
public class MyServer$Events implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        player.sendMessage("欢迎 " + player.getName());
    }
}
```

- 事件名解析为 Paper 事件类（`PlayerJoinEvent` 等）。
- 首个参数绑定事件对象；后续参数为事件对象的属性（`player` → `e.getPlayer()`），按名匹配。
- 自动执行 `getServer().getPluginManager().registerEvents(listener, plugin)`。
- 支持注解参数：`@EventHandler(priority = HIGH)`（§01-8.2）。

## 3.4 command 声明

```yux
command "/home" {
    permission = "myserver.home"
    description = "传送到家"
    aliases = ["h"]

    execute(sender, args) {
        player = sender as Player
        home = getHome(player.uuid)
        if home != null {
            player.teleport(home)
            player.send "已传送"
        } else {
            player.send "还没有家，用 /sethome 设置"
        }
    }

    tab(sender, args) {
        return ["home", "set", "del"]
    }
}
```

**展开规则（SDK-M3）：**

- `command "/home"` → 注册 `CommandExecutor`（`execute`）+ `TabCompleter`（`tab`）。
- `permission`/`aliases`/`description` 写入 `plugin.yml` 或运行时注册。
- `execute(sender, args)` 参数约定：
  - `sender`：`CommandSender`；
  - `args`：`List String`（已按空格切分）。
- 返回 `true` 表示已处理，未匹配子命令返回提示。

## 3.5 config 声明

```yux
config Server {
    motd = "Welcome!"
    maxPlayers = 100
    spawn = Location(0, 64, 0)
}
```

**展开规则（SDK-M4）：**

- `config Server {}` → 生成 `config.yml`（默认值）与访问器类 `Server`。
- 字段默认值即配置文件默认项；启动时读取并覆盖。
- 支持嵌套：`config Bungee { hosts:List String = [...] }`。
- 属性类型支持：`Int/Long/Double/String/Boolean/List/Map/枚举/自定义 data 类`。

## 3.6 permission / task 声明

```yux
permission "myserver.admin" {
    description = "管理员"
    default = OP
    children = ["myserver.home", "myserver.bypass"]
}
```

- 写入 `plugin.yml` 的 `permissions` 节。

```yux
task repeat(interval:20) {       // 每 20 tick 执行
    for player in onlinePlayers() {
        player.updateScoreboard()
    }
}

task delay(100) { }              // 延迟 100 tick 执行一次
```

- 编译为 `BukkitScheduler` 调度调用；`async task` 走异步线程。

---

# 4. 运行时层（yux-minecraft-runtime）

## 4.1 模块

```
yux.minecraft
├── bootstrap/       PluginBootstrap（入口点）
├── event/           EventBus, ListenerAdapter
├── command/         CommandRegistry, TabRegistry
├── config/          ConfigManager, ConfigLoader(YAML)
├── serialize/       NBT 序列化器, JsonNbtAdapter
├── schedule/        TaskScheduler
├── economy/         （可选）经济/计分板扩展
└── util/            PlayerUtil, LocationUtil, ItemBuilder
```

## 4.2 入口（PluginBootstrap）

- SDK 生成 `plugin.yml` 的 `main` 指向生成的 `Main` 类（继承 `PluginBootstrap` → `JavaPlugin`）。
- `PluginBootstrap.onEnable()`：
  1. 收集插件类中带 `@YuxEvent`/`@YuxCommand`/`@YuxConfig` 标记的成员；
  2. 注册事件监听、命令、加载配置、启动调度任务。

> 注：语言扩展层负责把 `event/command/config` 语法下沉为「带 SDK 注解的普通 Yux 类」；运行时层按注解注册。二者通过注解契约解耦。

## 4.3 注解契约

```java
@YuxEvent   (target = 事件类)            // 方法级
@YuxCommand (path = "/home", permission=..., aliases=...)
@YuxConfig  (name = "Server", path = "config.yml")
@YuxTask    (delay = 100, interval = 20, async = false)
```

- 语言扩展层下沉时生成这些注解；运行时层扫描执行。

---

# 5. 语法展开规则（形式化）

对 §3 的每个领域语法，给出「输入 → 展开后普通 Yux AST」的示意。

### event 展开（SDK-M2 形式化）

```
输入: event PlayerJoin(player) { BODY }
输出:
    @YuxEvent(target = PlayerJoinEvent)
    class PlayerJoinEvent_Handler {
        fun handle(e:PlayerJoinEvent) {
            player = e.player          // 参数绑定
            BODY[player→player]
        }
    }
    // 运行时：eventBus.register(该实例)
```

### command 展开（SDK-M3 形式化）

```
输入: command "/home" { permission=...; execute(s,a){B1} tab(s,a){B2} }
输出:
    @YuxCommand(path="/home", permission=..., aliases=...)
    class HomeCommand {
        fun execute(sender:CommandSender, args:List String):Boolean { B1 }
        fun tab(sender:CommandSender, args:List String):List String { B2 }
    }
```

### config 展开（SDK-M4 形式化）

```
输入: config Server { motd="Welcome!"; maxPlayers=100 }
输出:
    @YuxConfig(name="Server", path="config.yml")
    data Server {
        motd:String = "Welcome!"
        maxPlayers:Int = 100
    }
    // 运行时：加载时用配置文件值覆盖默认值
```

---

# 6. 序列化与 NBT

## 6.1 三层序列化（§01-8.3）

| 格式 | 场景 |
|---|---|
| JSON | 网络/HTTP API |
| YAML | 配置文件 |
| NBT | 物品/实体/存档（Minecraft 原生） |

## 6.2 NBT 序列化规则

- `data` 类自动生成 ↔ NBT 复合标签映射：

| Yux 类型 | NBT |
|---|---|
| Int / Byte / Long / Float / Double | TAG_Int / TAG_Byte / TAG_Long / TAG_Float / TAG_Double |
| String | TAG_String |
| Boolean | TAG_Byte(0/1) |
| List T | TAG_List |
| data 类 | TAG_Compound |
| Map String T | TAG_Compound |

- API：

```yux
import yux.minecraft.nbt.*

nbt = serializeNbt(PlayerData("Steve", 10))
item.setTag(nbt)

back = deserializeNbt(item.tag, PlayerData)
```

## 6.3 玩家数据持久化

```yux
saveHome(player) {
    data = HomeData(player.name, player.location)
    writeText("plugins/MyServer/homes/${player.uuid}.json", serialize(data))
}

loadHome(uuid) {
    path = "plugins/MyServer/homes/${uuid}.json"
    if !exists(path) { return null }
    return deserialize(readText(path), HomeData)
}
```

---

# 7. Paper API 包装

## 7.1 包装规则

- JavaBean 属性自动映射（§01-8.5.2）：`player.name` = `player.getName()`。
- 常用链式调用用属性/方法简写：

```yux
player.inventory.add item          // getInventory().addItem(item)
player.health = 20                 // setHealth(20)
player.location                    // getLocation()
loc = Location(world, x, y, z)
```

## 7.2 常用对象

```yux
Bukkit.broadcast("服务器公告")
Bukkit.getServer().onlinePlayers()   // Set Player
server.worlds()                      // List World
player.hasPermission("x.y")
player.teleport(location)
item = Material.DIAMOND_SWORD.create()
```

## 7.3 ItemBuilder（运行时 util）

```yux
sword = ItemBuilder(Material.DIAMOND_SWORD)
    .name("§6传说之刃")
    .lore(["上古遗物"])
    .enchant(Enchantment.SHARPNESS, 5)
    .build()

player.inventory.add sword
```

---

# 8. 权限 / 占位符 / 国际化

## 8.1 权限

- `command`/`permission` 声明自动生成 `plugin.yml` 权限节点。
- 运行时检查：`player.hasPermission("myserver.home")`。
- 与 LuckPerms 等权限插件兼容（走 Bukkit 权限 API）。

## 8.2 占位符

- `command` 参数支持占位符：`command "/msg <player> <message>"`，`args` 按声明切分。
- 可选集成 PlaceholderAPI（`papi:player_name`）。

## 8.3 国际化（i18n）

```
resources/lang/zh_CN.yml   # 消息表
resources/lang/en_US.yml
```

```yux
config Lang { welcome = "欢迎 $name" }
msg = t("welcome", name=player.name)     // 运行时查表 + 插值
```

- v0.1 提供 `t(key, ...)` 简化函数；正式 i18n 框架后续版本。

---

# 9. 生命周期与调度

## 9.1 生命周期钩子

| Yux | Paper 等价 |
|---|---|
| `onEnable {}` | `JavaPlugin.onEnable()` |
| `onDisable {}` | `JavaPlugin.onDisable()` |
| `onLoad {}` | `JavaPlugin.onLoad()` |

## 9.2 调度（运行时封装）

```yux
// 同步延迟/循环（主线程）
task delay(100) { }                    // runTaskLater
task repeat(20, interval:20) { }       // runTaskTimer

// 异步
async task delay(50) { }               // runTaskAsynchronously
async {
    data = await fetch("https://api.example.com")
    scheduleSync { applyToWorld(data) }
}
```

- 线程规则：主线程操作世界对象，异步操作 IO；SDK 提供 `scheduleSync {}` 切回主线程。

---

# 10. 模块 API 参考

```yux
// bootstrap
PluginBootstrap.getPlugin():MyServer
plugin.description / dataFolder / config

// event
eventBus.register(listener) / unregister(listener)

// command
CommandRegistry.register(path, permission, aliases, executor)

// config
ConfigManager.load<T>(data):T   / save<T>(data) / reload()

// serialize
serialize(x:Any):String            // JSON
deserialize<T>(text:String, type:T):T
serializeNbt(x:Any):NbtCompound
deserializeNbt(tag:NbtCompound, type:T):T

// schedule
fun scheduleSync(block:()->Unit)
task delay(ticks:Long) / repeat(interval:Long)

// util
ItemBuilder(material) / PlayerUtil.player(uuid):Player? / LocationUtil
```

---

# 11. 完整映射表

| Yux 语法 | 生成的 JVM 产物 | 运行时行为 |
|---|---|---|
| `plugin X {}` | `X extends PluginBootstrap` | 入口类，装载 |
| `event E(...)` | `@YuxEvent` + Listener 实现 | 注册监听 |
| `command "..." {...}` | `@YuxCommand` + Executor/Tab | 注册命令与补全 |
| `config C {...}` | `@YuxConfig` + data 类 | 加载/保存 YAML |
| `permission "..."` | plugin.yml permissions | 权限系统 |
| `task ...` | `@YuxTask` + 调度调用 | BukkitScheduler |
| `data` 类 | POJO + serializer | 序列化（JSON/YAML/NBT） |
| `service` | `@YuxService` | DI 容器 |

---

# 附录：与其他文档的关联

- 语法扩展点定义：`01` §9；编译器插件 SPI：`02` §10。
- 完整可用示例：`04-Paper插件示例.md`。
- 与 Kotlin/Java 混用：`05-混合项目示例.md`。
