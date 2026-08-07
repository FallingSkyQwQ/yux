# Yux Language Specification v0.1（设计稿）

## Simple · Handy · Powerful

> **Yux 是一个 JVM First 的现代通用语言。**
>
> 它隐藏复杂性，让普通开发者拥有极高生产力；
> 它保留 JVM 生态，让高级开发者拥有无限可能。

目标：

* 初学者：几天掌握
* Java/Kotlin：无痛迁移
* 企业：可进入生产
* Minecraft：一等开发体验
* KMP：未来跨平台基础
* JVM：完整兼容
* 注意: Minecraft相关内容并非直接语言内置，而是需要单独的 minecraft plugin.
---

# 1. 总体架构

## 编译链

不是：

```
Yux
 ↓
Java
 ↓
Bytecode
```

而是：

```
             Yux Source
                  |
              Lexer
                  |
              Parser
                  |
             Yux Compiler IR
                  |
     ------------------------------
     |              |             |
 JVM Backend   Native Backend   WASM
     |
 JVM Bytecode
```

第一阶段：

```
Yux
 ↓
Yux IR
 ↓
JVM Bytecode
```

未来：

```
Yux
 ↓
Yux IR
 ↓
LLVM
 ↓
Native
```

---

# 2. 项目结构

Yux 不使用传统 Maven/Gradle 配置。

项目：

```
MyProject/

    build.yml

    src/

       main.yux

    resources/

    tests/

```

## build.yml

替代：

* pom.xml
* build.gradle

例如：

```yaml
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

  serialization: true
  injection: true
```

编译器内部生成：

```
build.gradle.kts
settings.gradle
```

然后调用 Gradle。

---

# 3. 基础语法

## Hello World

```yux
fun main(){

    print "Hello Yux"

}
```

---

# 4. 变量

Yux 默认推断。

```yux
name = "Steve"

age = 18
```

等价：

```yux
name:String

age:Int
```

显式：

```yux
name:String = "Steve"
```

---

# 5. 类型系统

## 基础类型

内置：

```
Int
Long
Float
Double
Boolean
Char
String
Byte
```

---

集合：

不用泛型尖括号。

Kotlin:

```kotlin
List<String>
```

Yux:

```yux
List String
```

例如：

```yux
names:List String
```

---

# 6. 类系统

## 最大特色：

> 不写 class。

所有顶级对象声明默认就是类。

例如：

```yux
Player {

    name:String

    health:Int

}
```

自动生成：

Java:

```java
public class Player {

}
```

---

## 构造

自动生成：

```yux
player = Player(
    "Steve",
    20
)
```

---

# 7. 数据类

关键字：

```
data
```

例如：

```yux
data User {

    id:Int

    name:String

}
```

自动生成：

* constructor
* equals
* hashCode
* toString
* serializer

---

# 8. Getter / Setter

默认自动生成。

Yux：

```yux
Player{

    health:Int

}
```

Java:

```java
player.getHealth();

player.setHealth(20);
```

---

高级：

自定义：

```yux
Player{

    health:Int{


        get:

            return value


        set:

            value = clamp(set,0,20)

    }

}
```

---

# 9. 函数设计

极简。

普通：

```yux
fun add(a,b){

    return a+b

}
```

自动推断：

```yux
fun add(a,b)=a+b
```

类型：

```yux
fun add(a:Int,b:Int):Int
```

---

# 10. 空安全

采用：

> 编译提醒 + 运行保护

声明：

```yux
name:String?
```

允许：

```yux
print name.length
```

编译器生成：

```java
if(name!=null)
```

避免 Kotlin 过度严格。

---

# 11. 内存模型

## 默认：

GC

```yux
player=Player()
```

自动管理。

---

高级模式：

```yux
stack Player()
```

用于：

* 游戏
* 高频计算
* Server Tick

---

# 12. 异常系统

保留 JVM Exception。

普通：

```yux
try{

}
catch e{

}
```

简化：

```yux
try loadConfig()
```

自动展开。

---

# 13. 注解系统

保留 JVM 最大优势。

例如：

```yux
@Serializable

data User{

}
```

生成：

Java Annotation。

兼容：

* Spring
* Jackson
* JPA
* Minecraft API

---

# 14. 自动序列化系统

核心库：

```
yux.serializer
```

例如：

```yux
data PlayerData{

    name:String

    level:Int

}
```

自动支持：

JSON:

```json
{
"name":"Steve",
"level":10
}
```

YAML：

```yaml
name: Steve
level:10
```

Minecraft：

NBT：

自动支持。

---

# 15. 自动并发

目标：

开发者不用管理线程。

## 普通：

```yux
async loadPlayer(){

}
```

编译：

JVM Coroutine。

---

并行：

```yux
parallel{

    download()

    calculate()

}
```

自动：

ForkJoinPool。

---

# 16. 自动依赖注入

内置：

```
yux.di
```

声明：

```yux
service Database{


}


service UserService{


    database:Database


}
```

自动：

```
Database
    |
UserService
```

不需要：

Spring。

---

# 17. Java 互操作

必须 100%。

## 调Java

例如：

```yux
player = Bukkit.getPlayer(uuid)
```

自动优化：

JavaBean：

```java
getX()
setX()
```

转换：

```yux
player.name
```

---

# 18. Kotlin 互操作

Kotlin:

```kotlin
val user:YuxUser
```

Yux：

直接使用。

---

# 19. Minecraft YuxPlugin

单独项目：

```
YuxPlugin SDK
```

不是语言本体。

类似：

```
Spring Boot
Android SDK
```

---

## 创建插件

build.yml:

```yaml
plugins:

 minecraft:
    platform:paper
    version:1.21
```

生成：

```
plugin.yml
Main.class
```

---

# Minecraft语法扩展

## Plugin

```yux
plugin MyServer{


}
```

生成：

```java
JavaPlugin
```

---

# Event

Paper:

```java
@EventHandler
```

Yux:

```yux
event PlayerJoin(player){


    player.send "Welcome"

}
```

自动生成监听。

---

# Command

```yux
command "/home"{


    teleport player home


}
```

自动：

* CommandExecutor
* TabCompleter
* Permission

---

# Config

```yux
config Server{


    motd:String

    maxPlayer:Int

}
```

自动：

生成：

```
config.yml
```

---

# Minecraft API包装

原：

```java
player.getInventory()
       .addItem(item)
```

Yux:

```yux
player.inventory.add item
```

---

# 20. KMP路线

短期：

Yux JVM:

```
Android JVM
Desktop
Server
Minecraft
```

调用 Kotlin:

100%。

---

长期：

Yux Native:

```
Yux
 |
Yux IR
 |
Native
 |
Android/iOS
```

---

# 21. 标准库设计

```
内置库:
yux.core

yux.collection

yux.io

yux.net

yux.async

yux.json

yux.di

yux.test

plugin实现库:

yux.minecraft
```

---

# 22. 关键词数量

目标：

Java：

60+

Kotlin：

80+

Yux：

约 30。

核心：

```
fun
data
service
event
plugin
async
parallel
try
catch
if
else
when
for
while
return
import
package
new
unsafe
native
stack
```

---

# 23. Yux 的最大卖点

## 对新人：

```yux
Player{

 name:String

}


event Join(player){

 player.send "Hi"

}
```

就能写 Minecraft。

---

## 对 Java：

直接调用：

```yux
Spring
Bukkit
Fabric
Android
```

---

## 对高手：

拥有：

```
IR
Native
Unsafe
Annotation
JVM
```

---

# 24. 最终定位

Yux 不是 Kotlin 2.0。

它更像：

```
Python 的易用
+
Go 的简单
+
Kotlin 的生态
+
Rust 的安全理念
+
Java 的兼容
+
Minecraft 专业支持
```

---

待继续设计：

1. **Yux完整语法规范（类似Java Language Specification）**
2. **Yux编译器架构（Lexer/Parser/IR/Backend）**
3. **YuxPlugin完整SDK设计**
4. **用Yux重写一个Paper插件示例**
5. **Yux与Kotlin/Java混合项目示例**

