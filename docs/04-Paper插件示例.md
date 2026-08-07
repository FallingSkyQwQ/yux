# 用 Yux 重写一个 Paper 插件（Home 综合示例）

> 前置：`01`（语法）、`03`（YuxPlugin SDK）。
> 主题：Home 传送插件。覆盖事件、命令、配置、数据存储、定时任务、互操作六大能力，是 YuxPlugin SDK 的完整用法样例。

## 1. 功能需求

- `/sethome [name]`：把当前位置存为家（每人多组，可命名）。
- `/home [name]`：传送到家。
- `/delhome [name]`：删除家。
- `/homes`：列出所有家。
- 玩家加入时欢迎消息。
- 玩家死亡时不掉落家数据（数据存文件）。
- 冷却时间（config 控制）。
- 定期保存数据（定时任务）。

## 2. 项目结构

```
HomePlugin/
├── build.yml
├── src/
│   ├── main.yux            # plugin 入口 + 事件
│   ├── HomeCommand.yux     # 命令
│   └── HomeData.yux        # 数据模型
└── resources/
    └── lang.yml            # 消息
```

## 3. build.yml

```yaml
name: HomePlugin
version: 1.0.0

language:
  yux: 1.0

target:
  jvm: 21

dependencies:
  minecraft:
    paper: 1.21
  maven:
    - group: net.kyori
      artifact: adventure-api
      version: 4.17.0

plugins:
  minecraft: true
  serialization: true
```

## 4. 配置（HomeConfig.yux）

```yux
config HomeConfig {
    welcomeMessage = "欢迎 $name 回来！"
    homeCooldown = 30                    // 秒
    maxHomes = 5
    teleportSound = "entity.enderman.teleport"
}
```

自动生成 `config.yml`：

```yaml
welcomeMessage: "欢迎 $name 回来！"
homeCooldown: 30
maxHomes: 5
teleportSound: "entity.enderman.teleport"
```

## 5. 数据模型（HomeData.yux）

```yux
data LocationData {
    world: String
    x: Double
    y: Double
    z: Double
    yaw: Float
    pitch: Float
}

data HomeData {
    player: String
    homes: Map String LocationData
}
```

- `LocationData` ↔ NBT/YAML 自动序列化（§03-6）。
- 从 Paper `Location` 互转（见 §8 工具函数）。

## 6. 存储与工具（Storage.yux）

```yux
import yux.io.*
import yux.minecraft.nbt.*
import org.bukkit.Location
import org.bukkit.World

fun HomeData.save(playerName:String) {
    path = "plugins/HomePlugin/data/$playerName.json"
    writeText(path, serialize(this))
}

fun loadHomeData(playerName:String):HomeData {
    path = "plugins/HomePlugin/data/$playerName.json"
    if !exists(path) { return HomeData(playerName, Map String LocationData()) }
    return deserialize(readText(path), HomeData)
}

fun LocationData.toLocation():Location {
    world = Bukkit.getWorld(this.world)
    return Location(world, x, y, z, yaw, pitch)
}

fun Location.toData():LocationData {
    return LocationData(loc.world.name, x, y, z, yaw, pitch)
}
```

## 7. 命令（HomeCommand.yux）

```yux
command "/sethome" {
    permission = "homeplugin.set"
    usage = "/sethome [名称]"

    execute(sender, args) {
        player = sender as Player
        name = if args.isEmpty() then "default" else args[0]
        if name == "default" && player.hasHome("default") {
            player.send "覆盖默认家"
        }
        data = loadHomeData(player.name)
        if data.homes.size >= HomeConfig.maxHomes && !data.homes.contains(name) {
            player.send "已达最大家数 ${HomeConfig.maxHomes}"
            return true
        }
        homes = data.homes
        homes[name] = player.location.toData()
        HomeData(player.name, homes).save(player.name)
        player.send "已设置家：$name"
        return true
    }
}

command "/home" {
    permission = "homeplugin.home"
    usage = "/home [名称]"

    execute(sender, args) {
        player = sender as Player
        name = if args.isEmpty() then "default" else args[0]
        data = loadHomeData(player.name)
        loc = data.homes.get(name)
        if loc == null {
            player.send "没有名为 $name 的家"
            return true
        }
        if cooldownActive(player) {
            player.send "冷却中"
            return true
        }
        player.teleport(loc.toLocation())
        player.send "已传送"
        return true
    }

    tab(sender, args) {
        if sender !is Player { return [] }
        data = loadHomeData((sender as Player).name)
        return data.homes.keys
    }
}

command "/delhome" {
    permission = "homeplugin.set"
    execute(sender, args) {
        player = sender as Player
        name = if args.isEmpty() then "default" else args[0]
        data = loadHomeData(player.name)
        if !data.homes.contains(name) {
            player.send "没有名为 $name 的家"
            return true
        }
        homes = data.homes
        homes.remove(name)
        HomeData(player.name, homes).save(player.name)
        player.send "已删除家：$name"
        return true
    }
}

command "/homes" {
    permission = "homeplugin.home"
    execute(sender, args) {
        player = sender as Player
        data = loadHomeData(player.name)
        player.send "你的家：${data.homes.keys.join(", ")}"
        return true
    }
}
```

> 注：`cooldownActive(player)`、`player.hasHome(name)` 为扩展辅助（§9），保持示例聚焦。

## 8. 事件（main.yux）

```yux
plugin HomePlugin {
    onEnable {
        print "HomePlugin v1.0.0 已启用"
        scheduleTask()
    }
    onDisable {
        print "HomePlugin 已卸载"
    }
}

event PlayerJoin(player) {
    msg = HomeConfig.welcomeMessage.replace("\$name", player.name)
    player.send msg
    player.playSound(player.location, HomeConfig.teleportSound, 1.0, 1.0)
}

event PlayerQuit(player) {
    // 自动落盘（数据实时写文件，此处仅日志）
    print "${player.name} 离开"
}

event PlayerDeath(player) {
    // 死亡保留家数据，不删除
    player.send "你的家数据已保留"
}
```

## 9. 调度与辅助（Schedule.yux）

```yux
import yux.minecraft.schedule.*
import org.bukkit.plugin.java.JavaPlugin

// 每 5 分钟落盘一次所有在线玩家数据（幂等，防止异常中断丢失）
task repeat(20, interval:6000) {
    for player in Bukkit.onlinePlayers() {
        data = loadHomeData(player.name)
        HomeData(player.name, data.homes).save(player.name)
    }
}

// 冷却时间记录（内存表）
cooldowns = Map String Int

fun cooldownActive(player:Player):Boolean {
    now = System.currentTimeMillis()
    last = cooldowns.get(player.name) ?: 0
    if now - last < HomeConfig.homeCooldown * 1000 {
        return true
    }
    cooldowns[player.name] = now
    return false
}

fun Player.hasHome(name:String):Boolean {
    return loadHomeData(this.name).homes.contains(name)
}
```

## 10. 生成的产物（编译器自动生成）

```
target/HomePlugin-1.0.0.jar
├── plugin.yml                 # main=com.example.HomePlugin, permissions 自动写入
├── com/example/
│   ├── HomePlugin.class       # extends PluginBootstrap
│   ├── HomeCommand.class      # @YuxCommand
│   ├── HomeConfig.class       # @YuxConfig
│   ├── HomeData.class         # data 类 + 序列化器
│   └── HomePlugin$Events*.class
└── lang.yml
```

```yaml
# 自动生成的 plugin.yml（示意）
name: HomePlugin
version: 1.0.0
main: com.example.HomePlugin
api-version: "1.21"
permissions:
  homeplugin.home:
    description: 使用 /home
    default: true
  homeplugin.set:
    description: 设置/删除家
    default: true
```

## 11. 与 Java 版对比（同等功能）

| 维度 | Java 版 | Yux 版 |
|---|---|---|
| 事件 | 手写 Listener 类 + `@EventHandler` + 手动注册 | `event PlayerJoin(player) { }` |
| 命令 | CommandExecutor + TabCompleter + plugin.yml 手写 | `command "/home" { execute/tab }` |
| 配置 | 手写 YAML 读写 | `config HomeConfig { }` |
| 数据 | JSON/Gson 手写映射 | `data` + 自动序列化 |
| 定时 | BukkitScheduler 手动注册 | `task repeat(...)` |
| 估计行数 | 300~500 | ≈120 |

## 12. 测试（yux.test）

```yux
@Test
fun testSerializationRoundTrip() {
    loc = LocationData("world", 1.0, 64.0, 2.0, 0.0, 0.0)
    json = serialize(loc)
    back = deserialize(json, LocationData)
    assertEquals(loc, back)
}

@Test
fun testCooldownLogic() {
    assertTrue(cooldownActiveSimulated(0, HomeConfig.homeCooldown))
    assertTrue(!cooldownActiveSimulated(HomeConfig.homeCooldown * 1000 + 1, HomeConfig.homeCooldown))
}
```

```
yuxc test
```

---

# 附录：功能清单对照

| 能力 | 章节 | 覆盖 |
|---|---|---|
| 事件 | §8 | PlayerJoin / Quit / Death |
| 命令 | §7 | sethome / home / delhome / homes + tab 补全 |
| 配置 | §4 | 消息、冷却、上限、音效 |
| 数据存储 | §5-6 | data + JSON 落盘 + Location 互转 |
| 定时任务 | §9 | 定期保存 |
| 权限 | §7 | permission 节点自动生成 |
| 测试 | §12 | yux.test 单测 |
