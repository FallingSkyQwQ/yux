# Changelog

本文件记录各里程碑的显著变更（含 breaking changes，见 06-§3）。

## [Unreleased] - 2026-08-11 — 入门到精通教程 + 参数默认值修复

### 修复

- **参数默认值（S-5.5.5）**：默认值表达式此前从未被语义分析（类型不匹配被静默接受），且 IRGen 从不合成缺失实参（运行期 VerifyError）。现在：sema 在函数作用域内类型检查 `= 表达式`（类型不匹配报 E0004）；IRGen 在调用点 `padDefaultArgs` 补齐缺失实参——已提供实参物化为以形参名命名的局部，默认值可引用更早形参（`fun f(a:Int, b:Int = a * 2)`）；接入普通调用/成员调用（含扩展函数 receiver 前置）/async 挂起调用/语句位置调用。
- **MainTest samples e2e 真空 bug**：原先 `Path.of("samples")` 相对测试 CWD 解析，目录不存在而静默 `return`，CI 从未真正验证样例；改为向上定位仓库根并断言样例数 > 0。
- **教程五缺陷修复**（随 golden 示例同步验证，均有回归测试）：
  - when 语句 + is 分支内 `return`（密封 subject、无 else）的 JVM VerifyError（`goto` 指向代码末尾悬空 Label）——后端按返回类型补发兜底返回；
  - 访问器 `value`/`set` 绑定（S-5.2.3）IRGen 报"局部变量未登记"——访问器体物化 `value`（backing field）、`set` 别名 setter 参数；仅引用时物化、setter 赋值时写回；
  - 块 lambda 后直接链式调用（`xs.map { }.first()`）的 `it` 解析失败——块 lambda 实参是完整单元，尾随 `.`/`(` 归调用结果（PrattParser）；
  - 基类方法先调用后子类 `super` 调用 StackOverflow——`BasicOpt.foldExpr` 重建 Invoke 时丢失 `isSuper`，super 调用退回 invokevirtual 派发到子类覆盖方法；现保留 `isSuper` 恒发 INVOKESPECIAL；
  - 基本类型（`String`/`Int`）用户扩展函数解析不到——`checkInstanceCall` Basic 分支补查 `lookupExtensionCall`。

### 新增

- **入门到精通语言圣经** `docs/tutorial/`（6 篇：00-总览 / 01-入门 / 02-进阶 / 03-精通 / 04-生态门户 / 05-附录，锁定 v0.1.0-m15，每章尾速查 + 规范指引）。
- **golden 示例集** `samples/tutorial/`：74 例（62 正例 `.stdout` + 12 反例 `.err`），覆盖类型/控制流/函数/默认值/扩展函数/继承/访问器/data 类/泛型/集合/密封/when 表达式/空安全/异常/闭包/高阶/顶层属性/导入/注解/service/async 协程/Tasks/parallel/序列化/DI/IO/注册表成员/错误反例；新 harness `TutorialSamplesTest` 扫描并逐字节比对快照。
- **`setupyux.sh` 一键安装**：curl|bash；Linux/macOS/WSL；JDK 21+ 检测（缺失给指引）；优先 Releases 免编译产物、否则源码编译；安装到 `~/.yux/bin` + 幂等 PATH；`--ref` 锁版。
- **CI**：`tutorial-docs` job（文档↔示例一致性 `scripts/check-tutorial-docs.sh` + setupyux 语法/帮助/JDK 守卫）；`setupyux-install` job（main 推送时全链路安装冒烟 + 幂等性）。
- **编译器测试**：IRGen 默认值合成 5 例（缺参补齐/引用更早形参/成员方法/表达式位置/齐全旁路）+ sema 默认值类型检查 2 例。

## [v0.1.0-m15] - 2026-08-11 — 完整 SSA 优化器

### 新增

- **完整 SSA 优化框架**（02-§8.4 的「完整 SSA/优化框架」落地，v0.1 后置项转正；`yux.compiler.optimizer.ssa`）：
  - **SSA 构造**：方法体 → CFG（Label/Goto/Branch 基本块，结构化 Try 为不透明节点）；Cooper-Harvey-Kennedy 支配树 + 支配边界；Cytron φ 插入 + 支配树先序重命名（每赋值产生新版本局部，独占槽位）。
  - **结构化 Try 数据流**（不透明节点模型）：Try 内被读/写的局部在进入前发射 **flush 拷贝**（把当前版本复制进原寄存器）；Try 后由 Try 定义的局部恢复为原局部作为当前版本——跨 Try 边界（含循环/join）的值流经 φ 正确合并。
  - **SCCP**（稀疏条件常量传播，不动点）：全局常量传播跨越块/φ/循环；常量分支折叠为 Goto 并移除不可达边；数值赋值按目标类型归一（`fl:Float = 1` → 常量 `1.0f`，替代读取不改变类型语义）；折叠语义与 BasicOpt 一致（除零不折叠、Long 原类型比较、String 拼接）。
  - **拷贝传播 + 平凡 φ 消除**：`x = y` 用 `y` 替换 `x` 的使用（SSA 下安全）；被 Try 写入的原局部不可作传播源（其寄存器可被 flush/内层代码改写，防止 φ 自引用丢值）。
  - **激进 DCE**：不可达块删除；以副作用/可抛异常/终结语句/资源拷贝为根，沿 SSA 使用链向后传播活性（φ 活跃则其入边版本活跃）。
  - **φ 销毁**：φ 落地为前驱块末尾拷贝（真 SSA 版本独立槽位，拷贝均有效）；Branch 前驱需 **边分裂**（单语句分裂块 + 分支重定向）；并行拷贝环用两阶段临时防护；线性化回退为后端可用的 Label/Goto/Branch。
- 管线接入：`Compiler.generate` = CPS 降级 → **SsaOpt** → BasicOpt（收尾）；`GoldenIrTest`/`GoldenCpsTest`/`BackendTestSupport` 同步跑完整管线。
- **保守回退**（保持正确性，交回 BasicOpt）：方法体含 `Await`（CPS 后不应出现，防御）；Try 子列表向闭包外层逃逸（break/continue 跨 try）。
- 测试：`SsaOptTest` 12 例（跨块常量传播、常量分支折叠+死块移除、φ 落地、边分裂、拷贝传播、跨块 DCE、循环 φ、Try flush、跨 try 回退、副作用/除零保留）；`SsaE2eTest` 6 例后端端到端（Try 定义后读取、catch 条件定义、循环内 Try、跨 try break 回退、类型保留的常量传播、运行时 when 全路径）；覆盖率核心模块 82.0%（门禁 ≥70%）。

### 说明

- 设计偏差（详见 06-§M15）：SSA 版本局部分配独立槽位（不做 coalescing，φ 拷贝保留为真实赋值，正确性优先）；Try 内层代码不参与 SSA（BasicOpt 语句级折叠收尾）；含跨 try 逃逸控制流的方法整体回退 BasicOpt。
- 已知限制：Try 内局部不做全局常量传播/拷贝传播（不透明节点模型）；类级增量编译仍为后置项（06-§8.1）。

## [v0.1.0-m14] - 2026-08-11 — async 完整 CPS 状态机协程

### 新增

- **语言层协程（R3 转正，M14）**：`async fun` / `async { }` 编译为完整 CPS 状态机（自研运行时，不依赖 kotlinx-coroutines）。
  - 双 ABI：async 上下文内调用为**挂起调用**（直接续延传递，返回声明类型）；同步上下文调用返回 `Task`。
  - `await` 软关键字（async 上下文内；前缀 `await x` 与调用形 `await(x)` 兼容）：操作数须为 `Task`/`CompletableFuture`/async fun 调用，返回 `Any?`。
  - 同步门面急切内联执行到首挂起点；异常（含前置）经门面 `Task.completeExceptionally` 交付。
  - **await 挂起可取消**：被 await 的 future 取消 → 续延以 `CancellationException` 恢复，且该异常不被 `catch` 吞掉（Kotlin 式自动重抛）。
  - **try/catch/finally 跨挂起点完整支持**（catch 分派状态、finally 按正常/异常/返回三路径展开）。
  - 循环内可挂起（状态 label 回边）；`async{}` 块编译为合成 async fun，经 `Continuations.launch` 池上驱动，体内可 await；`parallel{}` 保持 ForkJoinPool 且禁止 await。
- 编译器：`YxAwait` AST + `IrStmt.Await` 挂起点；`AsyncCpsLowering`/`StateMachineGenerator`（状态划分、局部→字段、`invokeSuspend` 分派、resume 迭代驱动）；`Compiler.generate` 接入降级 pass；后端移除 async REMIND。
- yux-stdlib `yux.async`（T-M14-1）：`Continuation`（resume/resumeWithException）、`Suspendable`（invokeSuspend）、`Continuations`（SUSPENDED 哨兵、tryAwait、FutureCompletion、launch、newFuture/wrap/unwrap）；`Task`/`CompletableFuture` 加入内置简单名。
- 测试：CPS golden 套件（`GoldenCpsTest`，`cps-samples/`→`golden/cps/`）；`AsyncFunE2eTest` 15 例（门面+await、挂起调用、非阻塞、取消传播、自动重抛、try/catch/finally 跨挂起、深循环 20000 栈安全、递归链 1000、async{} 内 await）；stdlib `ContinuationTest` 9 例。

### 说明

- 设计偏差（详见 06-§M14）：`async` 是硬关键字，顶层函数命名为 `Tasks.launch`（既有设计偏差保持）；`await` 为非 async 上下文保留标识符语义；`CancellationException` 自动重抛为简化语义（不区分协程是否在取消中）；门面取消仅阻止结果交付（无结构化取消）；状态机类名 `Owner$name$Sm`；局部变量统一提升为字段（含 boxing）。
- 已知限制：跨续延 resume 链（async 递归）深度受 JVM 栈限制（单续延内迭代驱动栈安全）；`async fun` 覆写/接口默认协程（双 ABI 多态）待后续里程碑；`async{}` 门面返回的 Task 为 fire-and-forget（无结构化 join）。

## [v0.1.0-m11] - 2026-08-10 — 标准库完善与泛化测试

### 新增

- yux-stdlib 标准库四包（T-M11-1/2/3，01-§10，全部 Kotlin object + `@JvmStatic`，JDK 原生零新依赖）：
  - `yux.io.IO`（T-M11-1）：`readText` / `writeText`（自动建父目录）/ `readLines` / `exists`，UTF-8；IOException 包装为带路径的 `RuntimeException`；9 例单测（@TempDir round-trip、多行/空文件、中文）。
  - `yux.net.Http`（T-M11-1）：`get` / `post`（text/plain）/ `fetch`（§10.4 的 async fetch 在 v0.1 同步实现，KDoc 注明），JDK `HttpClient` 10s 超时，非 2xx 抛 `yux.net: HTTP <code> <url>`；5 例单测（JDK `HttpServer` mock：方法/路径/响应体/404/连接拒绝）。
  - `yux.async`（T-M11-2）：`Task`（内部包装 `CompletableFuture<Any?>`，`await`/`cancel`/`isDone`）+ `Tasks`（`launch`/`parallel`/`sleep`/`await`，JDK 原生并发——ForkJoinPool 隐式 join、Thread.sleep，S-8.4）；7 例并发时序单测（双任务并行开始差 <50ms、await 阻塞至副作用生效、parallel 阻塞至完成且 ≥80ms、sleep ≥90ms、ExecutionException 解包、cancel 后块不执行）。
  - `yux.collection.Colls`（T-M11-3）：`map`/`filter`/`forEach`/`sort`/`sum`/`max`/`first`/`reduce`（函数式：返回新 ArrayList，不修改入参）；`yux.core.Text`：`uppercase`/`lowercase`/`split`/`format`；27 例单测。
- 编译器集合/字符串成员调用降级（T-M11-3，02-§9.1 扩展）：
  - `JvmExtensions` 注册表：Yux 成员调用 `xs.map {}` / `s.uppercase()` 降级为 `yux.collection.Colls` / `yux.core.Text` 静态调用（receiver 前置为第 0 实参；按 JVM 接口/父类链匹配——`java.util.ArrayList` → `java.util.List` 键）。
  - FunctionN↔SemaType.Function 桥接：lambda 实参可传给 `yux.core.function.Function0..3` 参数（`TypeAssignability` Function→FunctionN 规则 + `typeOfLambda`/`typeOfBlockLambda` 对 FunctionN 期望类型的推导）；注册表 lambda 参数用接收者元素类型（`List Int` 上 `map { it * 10 }` 的 `it` 推导为 `Int`）。
  - 双路径接入：sema `checkInstanceCall`（JvmClassSymbol 与 Basic String 两分支）+ IRGen `ExprGen.resolveMemberExpr` / `StmtGen.resolveMemberCall`（语句位置）；`Text`/`Colls`/`Tasks` 注册为内置简单名（`SymbolTable.Builtins`，无需 `import` 即可 `Text.uppercase(...)`）。
  - **修复 Unit 返回 Lambda**：FunctionN 目标 lambda 的合成方法返回类型由 void 改为 Object（SAM 擦除 `invoke()Ljava/lang/Object;`），体以 `Return(Const(null))` 收尾（`launch { print "async!" }`、`forEach { print it }` 此前 VerifyError）；后端 `emitReturn` 补 void→null 防御。
- 泛化测试：
  - **fuzz**（T-M11-4）：`LexerParserFuzzTest` 固定种子（20260810）5000 例随机输入（覆盖全运算符/引号/中文注释/空白，长度 0~80），词法→语法→CstToAst 三阶段 0 panic，约 0.7s。
  - **基准**（T-M11-5）：`bench/run-bench.sh`（installDist yuxc + 冷/增量编译×3 中位数 + jar 体积，`--out` 落盘）+ `bench/RESULTS.md` 基线。
  - **覆盖率**（T-M11-6）：`yux-compiler-core` 行覆盖 **84.3%**，新增模块专项门禁 LINE ≥ 0.70（06-§7.2「M11 起」；其余模块保持全局 0.60）。
- `samples/stdlib.yux` + `.stdout`：标准库端到端样例（map/filter/sum/max/first/reduce/uppercase/lowercase/split/format/launch/await），纳入 `AsmBackendTest.samples directory e2e` 自动比对。

### 说明

- 设计偏差（详见 06-§M11）：§10.6 顶层 `async(block)` 因 `async` 为关键字以 `Tasks.launch` 替代（`Tasks.async` 不可用）；`fetch` 为同步实现（语言层协程仍 R3 后置）；`sum()` 泛型擦除后统一返回 `Double`；`Text.format` 用 `List<Any?>` 参数替代 vararg；`Text.split` 用字面分隔符（非 Java 正则语义）；`sort` 返回新列表（函数式，不改入参）；`parallel` 阻塞至完成（S-8.4.3 块尾隐式 join）。
- 测试：yux-stdlib 48 例新增（Colls 27 / IO 9 / Http 5 / Async 7），编译器 core 新增 26 例（JvmExtensions 9 / JvmExtensionsSema 10 / TypeAssignability 6）+ fuzz 1，backend-jvm 新增 JvmExtensionsE2e 3 例；全仓 `./gradlew build lint` 通过。
- 基准基线：helloworld 冷编译 0.802s / 增量 0.195s（jar 667B），mixed 冷 1.921s / 增量 1.379s（jar 4498B），`hello.yux` run 0.176s（见 `bench/RESULTS.md`，复跑 `./bench/run-bench.sh --out bench/RESULTS.md`）。
- 已知限制：语言层 `async fun`/`async{}`/`parallel{}` 关键字仍同步降级（R3，REMIND 保留）；Yux `List Int` 变量传给 `List<Any?>` 参数需显式 `as List Any`（泛型擦除，M10 既有行为）；生成的构建 jar 运行时仍需 classpath 提供 yux-stdlib（M10 遗留，未 shade）。

## [v0.1.0-m10] - 2026-08-09 — 混合项目

### 新增

- `samples/mixed`（T-M10-1/3，05-§9）：余额系统三方协作示例——Yux（`data Account` + `deposit`/`balance`
  函数）调用 Kotlin object `Currency.format`（货币格式化）与 Java 单例 `Logger.log`（日志）；
  `yuxc run -p` 输出「Kotlin 格式化 + Java 日志 + Yux 输出」三方协作结果。
- 混合编译编排（T-M10-2，05-§4）：`YuxBuild.compileMixed` 按依赖方向自适应——
  - **Yux → Java/Kotlin**：Yux 编译因未解析类型失败且项目含 Java/Kotlin 源码时，先 `gradle classes`
    编译 Java/Kotlin，再以项目类加载器重试 Yux（samples/mixed 路径，05-§5.1/5.2）。
  - **Kotlin/Java → Yux**：Yux 不引用 Java/Kotlin 时先行编译，kotlinc/javac 经生成脚本
    `implementation(files(yux-classes))` 依赖可见 Yux 类型（05-§5.3/5.4，反向互操作集成测试覆盖）。
- 编译器核心：`Compiler`/`SemanticAnalyzer`/`IRGen`/`ClassPathSymbolProvider` 增加可注入 `classLoader`
  （默认行为不变），使 Yux 编译期可反射解析项目 Java/Kotlin 编译产物类型。
- CLI：`run -p` 混合项目用单一 `URLClassLoader` 覆盖 Yux/Java/Kotlin 三方产物目录（父子类加载器
  仅单向委托，分开加载会导致 `NoClassDefFoundError`）。
- `GradleGenerator` 生成脚本（M10 完善 05-§6）：
  - `dependencyResolutionManagement` 仓库声明（kotlin-stdlib 解析必需，修复混合项目
    「no repositories are defined」）；
  - 混合项目 `kotlin("jvm") version "2.3.21"` + `kotlin { jvmToolchain(targetJvm) }`
    （kotlinc 固定运行于目标 JDK，修复 daemon 为新版 JDK 时 `JavaVersion.parse` 崩溃）；
  - `implementation(files(yux-classes))`：Java/Kotlin 编译 classpath 可见 Yux 产物。
- `MixedProjectE2eTest`（T-M10-1/2/3）：samples/mixed build/run 断言 + Kotlin/Java→Yux 反向临时项目
  （Yux 先行 → kotlinc/javac 构造 Yux data 类、调用 Yux 顶层函数）端到端。

### 说明

- 设计偏差（详见 06-§M10）：余额系统改为函数式账本（data 类属性只读 S-5.3.1、service 构造器要求全参、
  JVM 引用类型默认可空）；反向互操作示例 Kotlin/Java 与 Yux 同处默认包（Kotlin 具名包不可见默认包类）；
  双向同时引用（Yux↔Kotlin 循环）需统一符号收集（05-§4 未来项，S-05.3 后置，v0.1 明确不支持）。
- 测试：`./gradlew build lint` 通过（新增 MixedProjectE2eTest 3 例）。
- 验收：`yuxc build -p samples/mixed && yuxc run -p samples/mixed` → jar 同时含三语言类 +
  输出「Kotlin 格式化 + Java 日志 + Yux 输出」。

## [v0.1.0-m9] - 2026-08-09 — Paper 示例插件（Home）

### 新增

- `samples/home-plugin`（T-M9-1，04 全篇）：完整 Home 传送插件——`/sethome` `/home` `/delhome`
  `/homes` 4 命令 + `/home` tab 补全、PlayerJoin/PlayerQuit/PlayerDeath 3 事件（欢迎/日志/保留数据）、
  `config HomeConfig`（欢迎语/冷却/上限/音效）、玩家数据 YAML 落盘（`HomeStore`）、冷却内存表、
  `task repeat(interval:6000)` 定期保存；`plugin.yml` 自动生成（main: HomePlugin + permissions）。
- `samples/java-equivalent`（T-M9-4）：04-§11 Java 对照版插件，行数对比（Yux 240 vs Java 234）
  记录于 README。
- `HomePluginE2eTest`（T-M9-3）：编译 Home 插件 → 自定义类加载器 → 反射断言数据层
  （loadHomeData/saveHomeData round-trip 落盘、config 默认值回退）+ 类集合完整性（无服务器，R4）。
- `verify.sh`（T-M9-2）：验收脚本——`yuxc build/test -p samples/home-plugin` + jar 类清单 + plugin.yml 校验。
- 编译器扩展（支撑 04 语法，全部回归）：
  - **扩展函数** `fun Receiver.name()`：声明（receiver 作为第 0 参数）+ 成员调用 `x.name(args)` 解析
    （跨文件同包查找）；receiver 可空时经空守卫调用。
  - **索引访问** `a[i]` / `a[i] = v`：List/Set→`get(i)`/`set(i,v)`、Map→`get(k)`/`put(k,v)`；
    读取返回可空元素类型。
  - **if 表达式** `if c then a else b`（`then` 软关键字）：两分支公共类型 + 临时变量汇合。
  - **顶层属性** 直接访问/赋值：文件类静态字段 + 静态 getter/setter；顶层属性先于函数类型检查。
  - **泛型构造** `Map X Y()` / `HashMap X Y()`：parser 预收集表补 JDK 集合实现类元数；
    接口集合构造降级 `HashMap`/`ArrayList`/`HashSet`。
  - **字符串拼接** `+`：任一侧为 String 时降级 `IrExpr.StringTemplate`（后端 StringBuilder）。
  - **JVM 互操作增强**：基础类型装箱类与 JVM 接口赋值兼容（`String`→`CharSequence` 等，
    `replace(CharSequence,CharSequence)` 重载可用）；擦除裸类型（`Map` 无实参）接受任意泛型实参；
    JVM 静态方法 JavaBean getter 映射（`Bukkit.onlinePlayers()`→`getOnlinePlayers()`）；
    实例/静态方法重载选择按实参类型（`Math.max(int,int)` 不落 long/double）；
    构造实参数值字面量按参数类型收窄（Double→Float）。
  - **后端**：可空原语装箱描述符（`Int?→Ljava/lang/Integer;` 引用语义）；Convert 引用↔基本
    box/unbox；空守卫支持静态调用（扩展函数 receiver 实参守卫）；文件类与顶层类同名时
    `_File` 后缀。
- `yux-minecraft-runtime`：`yux.minecraft.io.HomeStore`（@JvmStatic）YAML data 类序列化 +
  `loadMap`/`saveMap`（值类型由示例实例确定）+ 6 例单测；`ConfigManager.get` 增 `@JvmStatic`。
- build-tool：`YuxBuild` 规范化 projectDir 绝对路径（相对路径生成的 Gradle 脚本路径错位修复）；
  minecraft 项目打包时 **shade 运行时与第三方依赖**（`yux-minecraft-runtime` + `snakeyaml` +
  `kotlin-stdlib` 解压并入插件 jar，经严格 Paper 类加载模拟验证自包含——修复 M8 遗留的
  `NoClassDefFoundError`）；`GradleGenerator.jarSection` 支持多 shade jar。
- 编译器：方法调用实参数值字面量按参数类型收窄（Double→Float，修复事件处理器
  `playSound(..., 1.0, 1.0)` 的 VerifyError——此前仅构造实参收窄）。
- `verify.sh`：jar 类清单校验改用 `grep -c`（避免 pipefail + `grep -q` 对大 jar 输出
  SIGPIPE 提前退出导致的误报）；`HomePluginE2eTest` 增「jar 自包含可被 Paper 加载」用例
  （校验 plugin.yml + 编译类 + runtime + snakeyaml + kotlin 均在插件 jar）。

### 说明

- 测试：编译器 core 386 例（golden IR/sema 随新语法更新）、backend-jvm 22 例、CLI 29 例
  （含 HomePluginE2eTest 4 例）、runtime 32 例（含 HomeStore 6 例）；`./gradlew build lint` 通过。
- 验收：`yuxc build -p samples/home-plugin` → jar 含 18 个下沉类 + plugin.yml；`yuxc test -p` → 通过。
- 已知限制（设计偏差见 06-§M9）：数据存 YAML 非 JSON（无 yux.io）；`homes` 用 `Map String Any`
  （泛型擦除）；config 静态访问经 ConfigManager 桥接；`command usage` 属性不支持；集成测试无
  MockBukkit（Proxy 模拟）；行数对比 v0.1 需显式类型标注。

## [v0.1.0-m8] - 2026-08-09 — YuxPlugin SDK

### 新增

- `yux-minecraft-runtime` 运行时层（T-M8-1/7/8/9/10/11，03-§4/§6/§7/§9）：
  - 注解契约（T-M8-1，03-§4.3）：`@YuxEvent(target)`/`@YuxCommand(path/permission/aliases)`/`@YuxConfig(name/path)`/`@YuxTask(delay/interval/async)`（aliases 为逗号分隔字符串——注解实参仅支持标量）。
  - `PluginBootstrap`（T-M8-7，03-§4.2/§9.1）：`onEnable` 保留 `AnnotationScanner.registerAll` 注册逻辑后调用用户钩子 `onYuxEnable`/`onYuxDisable`（设计偏差：用户 `onEnable {}` 下沉为覆盖 onYuxEnable，避免覆盖父类注册逻辑）。
  - `AnnotationScanner`（T-M8-7）：扫描插件 jar/classes 目录（codeSource），实例化带 SDK 注解的类并分派注册（event→EventBus / command→CommandRegistry / config→ConfigManager / task→TaskScheduler）；扫描与分派拆为 `scanClasses`/`dispatch` 内部函数供无服务器单测。
  - `EventBus`：`registerEvents` 委托；`CommandRegistry`（T-M8-4 运行时侧）：反射适配 `execute(sender:CommandSender, args:List):Boolean` / `tab(sender,args):List` 为 CommandExecutor/TabCompleter（SAM + 方法缓存；设计偏差：paper-api 1.21 将 getCommand 从 Plugin 接口移除，内部安全向下转型 JavaPlugin）。
  - `ConfigManager`（T-M8-8，03-§3.5/§4.3）：`dataFolder/<path>` YAML 覆盖默认值、`load/save/reload/get`（java.lang.reflect + SnakeYAML，嵌套 data 类递归）。
  - `NbtIO`/`NbtTag`（T-M8-9，03-§6.2）：Int/Byte/Long/Float/Double/String/Boolean/List/Map/data 类 ↔ NBT 复合标签映射，round-trip 全类型。
  - `TaskScheduler`（T-M8-10，03-§9.2）：`@YuxTask` + `run()` 反射 → BukkitScheduler 同步/异步 × 延迟/定时四象限。
  - `ItemBuilder`/`PlayerUtil`/`LocationUtil`（T-M8-11，03-§7.3）：链式物品构建、UUID 玩家查询、坐标解析。
- `yux-compiler-minecraft` 语言扩展层（T-M8-2..6/12，03-§3/§5/§8/§11）：
  - `MinecraftCompilerPlugin`：注册 `plugin/event/command/config/permission/task` 六个扩展关键字（与内置关键字核对无冲突）；ExtensionParser 按 03-§3 语法解析，SyntaxTransform 下沉为普通 AST（经公开的 `CstToAst.convertBlock`）。
  - `plugin X {}`（T-M8-2）→ `class X extends PluginBootstrap`（onEnable/onDisable → onYuxEnable/onYuxDisable override）。
  - `event E(p) {…}`（T-M8-3）→ `@YuxEvent` + `E_Handler implements Listener` + `@EventHandler handle(e:E)`，参数按名绑定事件对象属性（`player = e.player`）；事件类名自动补 `Event` 后缀；SDK/固定包类型生成全限定名，事件类由源码 `import` 解析。
  - `command "…" {…}`（T-M8-4）→ `@YuxCommand` + `execute(sender:CommandSender, args:List String):Boolean`（隐式追加 `return true`，03-§3.4）+ 可选 `tab`。
  - `config C {…}`（T-M8-5）→ `@YuxConfig` + data 类（字段类型按字面量推导；v0.1 仅标量默认值，List 报诊断）。
  - `permission "…"`（T-M8-6）→ plugin.yml permissions 元数据；`task [async] delay/repeat(interval:…)`（T-M8-6）→ `@YuxTask` + `fun run()`（设计偏差：async 修饰符位于 task 之后，03-§9.2 为 `async task`）。
  - plugin.yml 生成（T-M8-12）：CodegenHook 产出 `plugin.yml` 产物（main + permissions + command 权限节点，纯文本渲染）；build-tool 以 YAML 合并——build.yml 派生（name/version/api-version）为底、插件产物逐键覆盖（main/permissions）。
- build-tool 配套（M8-12）：`CompiledProject.resourceArtifacts` 承载非 .class 钩子产物；`YuxBuild` 合并两份 plugin.yml。
- `samples/minecraft-hello`：全语法示例工程（plugin/event/command/config/permission/task），`yuxc build -p samples/minecraft-hello --plugin yux-compiler-minecraft.jar` → jar 含合并 plugin.yml 与六个下沉类。

### 说明

- 测试：runtime 26 例（ConfigManager 7 / NBT 6 / TaskScheduler 4 / CommandRegistry 4 / ItemBuilder+LocationUtil 3 / AnnotationScanner 2）+ compiler-minecraft 19 例（六关键字解析+下沉+plugin.yml），全仓累计 535 例全绿；`./gradlew build lint` 通过。
- 验收：`yuxc build -p samples/minecraft-hello --plugin …` → 构建成功，plugin.yml（main: MyServer + permissions）与 Main/MyServer/PlayerJoinEvent_Handler/HelloCommand/ServerConfig/Task_0_20 类齐全。
- 已知限制（R4）：`ItemBuilder.build()` 需运行中服务器（RegistryAccess），链式契约单测覆盖、build 产物由 M9 集成测试（MockBukkit）验证；`yuxc run/test` 对 minecraft 项目需真实服务器（M9）；`async task` 语法偏差；config List 默认值暂不支持；事件类需显式 `import`。
- 环境要点：paper-api 1.21-R0.1-SNAPSHOT 加入版本目录（CLI 携带以便 sema 解析 Paper 类型；runtime compileOnly+testImplementation）；测试用 java.lang.reflect.Proxy 模拟 Bukkit 对象、反射设置 `Bukkit.server` 满足静态初始化（`Bukkit.setServer` 在纯 API 环境不可用）。

## [v0.1.0-m7] - 2026-08-09 — M7 构建管线（build.yml → Gradle）

### 新增

- `build-tool` 模块（T-M7-1..7，02-§11/§12）：
  - `BuildConfig` 模型 + `BuildYmlParser`（T-M7-1）：SnakeYAML 解析 `build.yml` → `BuildConfig`（name/version/language/target/source/resources/tests/dependencies.maven/dependencies.minecraft.paper/plugins/main）；schema 校验缺字段/类型错误抛 `BuildConfigException`（中文说明）；未知顶层字段忽略（前向兼容）；文档示例的未加引号 YAML 数字版本（`yux: 1.0`/`paper: 1.21`/`version: 1.0`）转字符串接受，`name` 保持严格。
  - `GradleGenerator`（T-M7-2）：按 02-§11.3 / 05-§6 模板生成 `build.gradle.kts`/`settings.gradle`（plugins/java 工具链/application/dependencies/sourceSets 条件渲染）+ `plugin.yml`（`plugins.minecraft` 启用时，主类缺省回退项目名）；golden 快照（pure/mixed/minecraft 三场景）。
  - `BuildPlanner`（T-M7-3）：递归收集 `.yux/.java/.kt` 三类源码（按文件名排序）、编译顺序编排（`yux` 先行，其后 `java`/`kotlin`，05-§4）；无 Yux 源码报错。
  - `YuxSymbols`（T-M7-7）：IDE 映射 `yux-symbols.json`（文件 → 文件类名 + 顶层符号 function/class/data/service/property），确定性格式，写入 `build/yux-symbols.json`。
  - `YuxCache`（T-M7-6）：文件级增量缓存 `build/yux-cache/manifest.json`（源文件 SHA-256 清单 + 编译器版本）；源文件未变更时跳过 Yux 编译，且 jar 已存在时跳过 Gradle 调用。
  - `GradleRunner`（T-M7-4）：托管 Gradle 调用（`-p` 项目目录 + `--offline`/`--no-daemon`）；定位顺序 `YUX_GRADLE` 环境变量 → `GRADLE_HOME` → 项目树向上 `gradlew` → PATH。
  - `YuxBuild` 编排器（T-M7-3/5/7）：`yuxc build` 完整流水线（解析 → 计划 → 增量 → 进程内 Yux 编译 → 生成 Gradle 工程 → 托管打包 → 拷贝 jar 到 `build/libs/<name>-<version>.jar`）；`compile()` 供 `yuxc run -p` 进程内运行；`--clean` 删除全部产物后全量重建。
- `yuxc` CLI（T-M7-5，02-§14）：新增 `build [-p <dir>] [--clean] [--offline]`、`test [-p <dir>]` 命令与 `run -p <dir>` 项目模式；参数解析扩展 `-p/--project/--clean/--offline`（`--plugin` 兼容保留）；`--plugin` 可作用于项目命令。
- `samples/helloworld`：M7 验收示例工程（`build.yml` + `src/main.yux` + `resources/message.txt`），`yuxc build -p samples/helloworld` → `build/libs/helloworld-0.1.0.jar`（含 Main.class + 资源）。

### 说明

- 测试：build-tool 新增 35 例（解析器 14 / golden 生成器 3 / 规划器 5 / 符号 3 / 增量缓存 4 / GradleRunner 集成 1 / YuxBuild 集成 5）+ CLI 项目命令 6 例，累计 490+ 例全绿；`./gradlew build lint` 通过。
- 验收：`yuxc build -p samples/helloworld/` → 构建成功 + jar；`yuxc build --clean` → 全量重建通过；二次构建增量命中跳过编译（06-§M7）。
- 已知限制：Yux 编译 classpath = CLI 运行时（JDK + stdlib），`dependencies.maven` 尚未进入 yuxc 编译期 classpath（混合项目 M10 完善）；生成的 Gradle 脚本不含 05-§6 的 `compileYux` Exec 任务（Yux 编译由 yuxc 进程内先行）；`yuxc test` 为 v0.1 最小实现（构建成功即通过，yux.test 框架 M11 落地）。

## [v0.1.0-m6] - 2026-08-08 — M6 编译器插件 API

### 新增

- `yux-compiler-core` 插件 SPI（T-M6-1/2，02-§10）：
  - `yux.compiler.plugin` 包：`YuxCompilerPlugin`/`PluginContext`（registerKeyword/registerSyntaxTransform/registerSemanticRule/registerCodegenHook/addRuntimeDependency）/`ExtensionParser`/`SyntaxTransform`/`SemanticRule`/`CodegenHook`/`PluginArtifact`；
  - `PluginManager`（T-M6-2）：插件注册表 + 调用链记录；冲突/隔离校验（02-§10.4）：插件 id 重复 E0029、扩展关键字冲突 E0030、注册内置关键字 E0031；
  - `PluginLoader`（T-M6-3）：`ServiceLoader` 类路径加载 + `URLClassLoader` jar 加载（父加载器 = 编译核心，隔离插件实现类）；
  - `YxExtensionDecl : YxDecl` + `CstExtensionDecl : CstDecl` 占位节点（插件解析器产出，下沉前驻留 AST）。
- 扩展关键字接入（T-M6-4，02-§10.2.1）：`Parser` 构造接受 `PluginManager`，`parseTopLevelDecl` 在钩子处暂停并委托 `ExtensionParser`；`DeclarationsCollector` 预收集跳过扩展关键字；token 游标/`parseType`/`parseBlock`/`parsePrimaryExpr`/`parseStatementBody` 等公开为 ExtensionParser API。
- 下沉管线（T-M6-5，02-§10.4）：`lowering/PluginLowering` 在 CstToAst 之后、语义分析之前将全部 `YxExtensionDecl` 下沉为普通 AST；未匹配 SyntaxTransform 报 E0032；后端（sema/IRGen/ASM）不接触领域语法。
- 集中管线 `yux.compiler.Compiler`（parse→lower→sema→IRGen→opt），插件注入点唯一；`Runner.compile`/CLI 各命令统一接入。
- `--plugin <jar>` CLI 选项（T-M6-3）：`yuxc run/ast/check/ir --plugin x.jar file.yux`（选项可置于文件前后）；codegen hook 产物与后端产物合并进入类加载器。
- `yux-compiler-plugin-api` 以 typealias 再导出 SPI（`yux.plugin.api.*`），保持文档 import 路径；SPI 实现在 core（避免 core→plugin-api 依赖环，02-§10 接口签名不变）。
- `samples/extension`（T-M6-6）：hello-extension 示例插件——注册 `greet` 扩展关键字，`greet "World"` → 下沉为 `fun main() { print "Hello, World!" }` → 字节码运行；含 `META-INF/services` 与单测。

### 说明

- 测试：core 插件测试 14 例（注册通道/关键字解析/下沉无泄漏/冲突隔离/jar 加载）+ plugin-api 2 例 + samples/extension 3 例 + CLI e2e 6 例（`--plugin` 运行/ir/ast、缺 jar 报错），累计 430+ 例全绿。
- 验收：`yuxc run --plugin samples/extension/build/libs/*.jar samples/extension/greet.yux` → `Hello, Yux plugin!`（06-§M6 链路用例）。
- 设计偏差：SPI 接口定义于 core 的 `yux.compiler.plugin`（而非 plugin-api 模块），因 core 的 Parser/PluginLowering 须引用 SPI 类型而 plugin-api 依赖 core；plugin-api 以 typealias 再导出保持 `yux.plugin.api` 文档命名空间。

## [v0.1.0-m5] - 2026-08-08 — M5 JVM 后端

### 新增

- `yux-compiler-backend-jvm` 模块（T-M5-1..9，02-§9）：
  - `AsmBackend`（T-M5-1）：`IrModule → .class` 字节；`ClassWriter(COMPUTE_FRAMES)`；类/文件类命名（文件类 = 文件名大写化）；V21 目标；私有字段 + 访问器（T-M5-2，Boolean→`isX`）；
  - `AsmEmitter` 方法体发射（T-M5-3/4/7/8）：IrStmt/IrExpr → 字节码；局部槽位分配（this/宽类型 2 槽）；标签/分支/Goto；try/catch/finally（异常表 + finally 三路径）；字符串拼接与 StringTemplate → StringBuilder（02-§9.3）；空守卫 → ifnull + 类型默认值（02-§9.4）；
  - 调用：Yux 方法引用用 IR 描述符；JVM 互操作（T-M5-7）经 `JvmDescResolver` 反射解析**真实描述符**（`Iterator.next()` 真实 `()Ljava/lang/Object;`）+ `InvocationAdapter` 装箱/拆箱适配（`print 42` → `Integer.valueOf`）；静态/实例/接口调用分派；
  - Lambda（T-M5-4）：`invokedynamic` + `LambdaMetafactory`，合成方法捕获参数前置（匹配 metafactory `(captures..., invoked...)` 约定）；实例方法上下文捕获 `this`；`FnInvoke` → `FunctionN.invoke` + 装箱适配；
  - `AsmDataGen`（T-M5-5）：data 类自动 `toString`（`User(id=1, name=Steve)`）/`equals`（引用短路 + instanceof + 逐属性，引用用 `Objects.equals`）/`hashCode`（31 进制）；
  - 注解（T-M5-6）：类/方法注解 → JVM 注解字节码（RuntimeVisible + 常量实参）；service 自动 `@yux.di.YuxService`；
  - async 同步降级（T-M5-9，R3）：`async fun` 照常同步发射 + REMIND 诊断（真正协程 M11）。
- `yux-stdlib` 运行时：`yux.core.CoreLib`（print/println 静态方法）、`yux.core.Range`（闭区间迭代）、`yux.core.function.Function0..3`（函数类型 SAM 接口）、`yux.di.YuxService`/`yux.serializer.Serializable` 注解。
- `yuxc run <file>`（T-M5-10）：编译 → `MemoryClassLoader`（父加载器含 yux-stdlib）→ 文件类静态 `main()` 反射执行；运行时异常打印堆栈并返回 1。
- 端到端 `samples/`（T-M5-11）：hello/data/controlflow/nullable/interop/types/lambdas 七个样例 + `.stdout` 快照比对（`yuxc run samples/hello.yux` → `Hello Yux`；`yuxc run samples/data.yux` → `User(id=1, name=Steve)`）。
- 前端配套：用户类继承 Object 成员回退（`u.toString`/`equals`/`hashCode`，S-8.7.1，data 验收依赖）；JVM 重载选择实参类型精确匹配优先（`Math.max(1,2)` 不再落到 double 重载）；字符串拼接另一侧须为 String/数字（防 `print "a" + "b"` 解析歧义落到 Unit）；IR 携带 `IrAnnotation`；`IrField.owner`（静态字段归属）。

### 说明

- 已知限制：IR 无源码 span，方法体不产出 `LineNumberTable`（行号表后置）；泛型擦除但不产出 Signature 属性；`async` 同步降级（R3 既定）；`package` 命名空间未映射到 JVM 包（文件类在默认包）。
- 测试：新增 42 例（后端 30：端到端 11/类结构 2/访问器 1/data 2/注解 1/互操作 2/空守卫 1/async 1/加载 1/样例目录 1 + stdlib 12 + CLI 6），累计 411+ 例全绿；golden 新增 `annotations.yux.ir`（注解渲染）。

## [Unreleased]

### 变更

- 严格 Git Workflow（M5 起，06-§7.4）：开发统一在 `feature/m5-jvm-backend` 上进行，`main` 仅接受 PR 合入；新增 `AGENTS.md` 固化约定；清理本地与远端旧里程碑分支。

### 修复

- IRGen 高阶函数/闭包正确性（M5 前端准备，CodeRabbit 审查后回归）：
  - 函数类型局部/参数调用在 Lambda 体内可解析并捕获（sema 登记 `resolvedRefs[callee]`）；语句位置 `g(3)` 发 `Eval(FnInvoke)` 而非报错；
  - `IrExpr.isDiscardable()` 统一表达式语句门与优化器可丢弃判定（递归检查操作数，`a() == b()` 语句不再丢失调用副作用）；
  - 闭包捕获分析作用域化（`bound` 随块/循环/嵌套 Lambda 保存恢复），循环变量/内层参数不再压制同名外层变量捕获；
  - 块 Lambda 非 Unit 返回校验（S-7.4.3）：末条语句必须是表达式且类型匹配（sema E0015 + IRGen 防御性报错）；
  - 顶层属性自定义访问器生效（不再静默退化为字段读取）；捕获变量未登记时以诊断替代 NPE。

## [v0.1.0-m4] - 2026-08-08 — M4 IR

### 新增

- `yux.compiler.ir`（T-M4-1/2/3，02-§8）：
  - `IrType` 类型系统（T-M4-3）：JVM 无关（02-§13.1，不用 ClassDesc），Basic/Nullable/Generic/Function/Declared/TypeParam/Void/Nothing/Error，`render()`/`equivalent()`（符号按名等价）与 `defaultValue()`（空守卫默认值）；`SemaType → IrType` 桥接（`irgen/TypeBridge`）；
  - `IrModule` 层次（T-M4-1）：`IrModule → IrClass（含文件类，05-§5.3）→ IrField/IrProperty/IrMethod`；属性 = backing 字段 + getter/setter（Boolean→`isX`，01-§5.2）；`<init>` 构造器（实参 = 属性顺序）、`$clinit`（顶层属性初始化）；
  - `IrStmt`/`IrExpr`（T-M4-2）：02-§8.2 全集（LocalAssign/Call/New/FieldAccess/Branch/Goto/Return/Throw/Monitor/TryCatch；Const/LocalRead/FieldRead/Arith/Compare/Convert/Invoke/StringTemplate/NullGuard/Lambda）+ 必要补充（Label 定位点、表达式位置 New、Not/Neg、IsType）；`inferType()` 推导表达式类型；调用目标统一为 `IrCallable`（`IrMethodRef` Yux 方法 / `IrJvmCall` JVM 互操作）。
- `yux.compiler.irgen`（T-M4-4）：`IRGen(analysis).generate(declsByFile)` 两遍式（骨架遍 + 主体遍）；
  - 下沉：if→Branch/Goto、while→Branch、for→迭代器展开（`iterator()/hasNext()/next()`，Range 归属 `yux.core.Range`）、when→分支链（`is T` 用 IsType）、`&&`/`||` 短路、try→结构化 Try、async/parallel/unsafe→同步降级（R3）；
  - 空守卫：M3 插入点（guardPoints）→ `IrExpr.NullGuard` 包裹读取路径（02-§9.4）；
  - 互操作（`JvmCallResolver`）：镜像 TypeChecker 的 jvmForBasic/JavaBean 访问器/静态成员映射，`print/println/serialize` 内置函数 → `yux.core.CoreLib` 静态调用；
  - Lambda → 合成方法 `lambda$n`（箭头/块/`it`）；字符串插值 → StringTemplate；字面量解码（十六/二进制/`_`/后缀/转义/`\uXXXX`）。
- `yux.compiler.optimizer.BasicOpt`（T-M4-5，02-§8.4 默认开启）：常量折叠（数值提升/字符串拼接/比较/Not/Neg/Convert，除零不折叠）、死代码消除（终结语句后不可达 + 未读取纯赋值，副作用保留）、冗余跳转消除（Goto-紧随-Label、Branch 同目标）、空守卫折叠（接收者静态 null→默认值 / 静态非空→去守卫）；迭代至不动点（≤3 轮）。
- `yux.compiler.ir.IrPrinter`（T-M4-6）：稳定文本 dump（golden 逐字节锁定），`yuxc ir <file>` 子命令（IRGen + BasicOpt 后输出）。
- 测试：52 例新增（IrType 等价/桥接 8、IrStructure 结构 8、IRGen 下沉 19、BasicOpt 优化 14、GoldenIr 快照 1 + CLI 2 例），累计 369 例全绿；golden 快照 `golden/ir/*.ir`（5 个样例：hello/types/controlflow/nullable/interop）。

### 说明

- 文档 02-§8.2 的补充（M4 落地的必要具体化）：`IrStmt.Label`（Branch/Goto 需标签定位）、`IrExpr.New`（表达式位置构造）、`Not/Neg`（`!`/一元负）、`IsType`（`is T`）、`IrStmt.Call` 增加 receiver 槽位（实例方法语句位置调用）、`IrStmt.FieldAccess` 增加 value 槽位（写值表达）。后端（M5）按源/目标类型区分 `Convert`（CHECKCAST vs i2l）。
- `Result(true, 42)` 等泛型构造实参的类型实参推断（`Result Int`）为 M3 既有限制，IR 忠实反映（`Result`）。
- `..=` 复合赋值与函数类型变量调用在 M4 不展开（sema 后置清单）。

## [v0.1.0-m3] - 2026-08-08 — M3 语义分析

### 新增

- `yux.compiler.sema`：
  - `SemaType` 类型系统（T-M3-1）：Basic/Declared/TypeParam/Function/InferenceVar/Unit/Nothing/Error，可空性 + 赋值兼容（`Any` 顶层、`Nothing` 空底）；
  - `SymbolTable` + `FileScope`（T-M3-2）：声明收集遍（02-§6.2），包→类型索引、跨文件/导入/star 导入解析、冲突检测；
  - `ClassPathSymbolProvider`（T-M3-2）：JVM 类反射懒加载 + 缓存（占位原地填充保证 `===` 实例一致），JavaBean 属性/方法/静态成员映射，Java 类型视为可空（S-8.1）；
  - `TypeResolver`（T-M3-1/3）：AST 类型→SemaType，泛型实参数目校验（S-4.3.2）、类型参数作用域（S-4.3.3）、W0001 命名约定回退；
  - `Declarations`（T-M3-3）：两遍式成员解析、data/service/override/注解/访问器校验（E0010/E0012/E0019/E0026）；
  - `Inference`（T-M3-5）：局部双向推断（ADR-08），字面量按目标类型收窄（S-7.5.4）、函数返回推断（S-5.5.2）；
  - `TypeChecker`（T-M3-4）：S-xx 规则全量（条件 Boolean、实参/返回匹配、确定性赋值含分支合并、运算符、成员/静态/构造调用、Lambda、this/super、异常、循环）；
  - `SmartCast`（T-M3-6）：`is T`/空值判空分支智能转型，可变引用抑制 + R0002；
  - `Annotations`（T-M3-8/9）：注解目标校验（S-8.2）、service 注入图循环检测（S-5.4.1）；
- `yux.compiler.lowering.NullGuard`（T-M3-7）：可空接收者读路径守卫插入点 + R0001，写路径 E0023（S-8.1）；
- `yux.compiler.diag.ErrorCodes.md`（T-M3-10）：诊断码唯一事实源，`ErrorCodes.kt` 常量（E0001~E0028/W0001/R0001~R0002）；
- 内置函数 `print/println/serialize`（yux.core 最小集占位，标准库未实现前供示例通过）；
- `yuxc check` 子命令：输出类型推导 + 诊断；
- 测试：115 例（类型系统/符号表/类型检查/推断/智能转型/空守卫/注解 + **86 个编译期负向用例**，M3 验收要求 ≥80）+ `golden/sema/*.sema` 快照（5 个样例）。

### 说明

- 泛型实参数目：解析器层已拒绝过度实参（M2 消歧收集元数），sema 侧覆盖放行后仍不一致的情形（如 `Map String`）。
- 类属性默认可变（自动 get/set，S-5.2.1）；`data` 类属性默认只读（S-5.3.1），可自定义 setter 覆盖。
- `E0011/E0025/E0027` 为预留诊断码（data 非法成员/重复 override/歧义引用），由解析器或后续里程碑结构性保证。

## [v0.1.0-m2] - 2026-08-08 — M2 语法分析器

### 新增

- `yux.compiler.ast`：`YxNode` 层次（声明/类型/语句/表达式，节点携带 `SourceSpan`）——M3 语义分析的稳定输入。
- `yux.compiler.parser`：
  - `Cst`：全产生式 CST 节点（含括号/换行占位）；
  - `Parser`：手写递归下降，package/import/类/data/service/函数/属性/访问器/注解/语句；
  - `PrattParser`：01-§7.1 优先级表 + 后缀链 + 无括号单参调用（S-7.2.2，`f a + b`==`f(a)+b`）；
  - `LambdaParser`：箭头/多参括号/块 Lambda（隐式 `it`）；
  - `Disambiguation`：两遍式声明预收集（类型/函数/属性 + 泛型元数），`Type(args)` 构造 vs `func(args)` 调用 vs 静态成员访问（01-§7.7）；
  - `Recovery`：同步恢复（缺 `)`/`}`/坏 token 不抛异常，多错误收集）；
  - `CstToAst` + `AstPrinter`：规约 + 稳定 dump。
- `yux.compiler.lexer`：补上语法必需的 `throw` 关键字（01-§2.4 列表漏列，实际 31 个基础关键字）。
- golden 快照：`parser-samples/*.yux` → `golden/ast/*.ast`（8 个样例，覆盖声明/语句/表达式/消歧/Lambda/互操作）。
- `yuxc` CLI：`lex` / `ast` 子命令（`yux-compiler-cli`）。

### 说明

- 已知歧义（记录供后续处理）：单行 `if x y`（条件与单语句体均以 Primary 起始）在无括号调用语义下解析为 `if(x(y))`，单行体建议用块或 `return/break` 形式。

## [v0.1.0-m1] - 2026-08-07 — M1 词法分析器

### 新增

- `yux.compiler.lexer`：`Lexer`、`TokenKind`（与 01-§2.6 运算符表一一对应）、`Token`、`SourcePosition`、`Trivia`、`ScannerState`、`InterpolationScanner`、`TokenPrinter`。
- `yux.compiler.diag`：`Severity`（ERROR/WARNING/REMIND）、`Diagnostic`、`DiagnosticSink`（坏输入不抛异常）。
- `yux.compiler.source`：`SourceFile`。
- 词法能力：30 基础关键字 + 5 软关键字；十/十六/二进制与 `_`/后缀数字；浮点与指数；字符/字符串转义与 `\$`；`"""` 原始串；`$name`/`${expr}` 插值分词（括号平衡、嵌套字符串）；行/块（可嵌套）/文档注释；换行折叠为单个 `NEWLINE`。
- golden 快照：`samples/*.yux` → `golden/tokens/*.tokens`（7 个样例，覆盖关键字/数字/字符串/运算符/注释/插值/HelloWorld）。

## [v0.1.0-m0] - 2026-08-07 — M0 工程骨架 + CI

### 新增

- 初始化 Git 仓库与多模块 Gradle 工程（09 个模块，对应 02-§14 / 06-§9.1）：
  - `yux-compiler`：core / backend-jvm / plugin-api / cli / test-support
  - `yux-plugin-minecraft`：compiler-minecraft / minecraft-runtime（骨架）
  - `yux-stdlib`、`build-tool`
- 统一版本目录 `gradle/libs.versions.toml`（Kotlin 2.3.21、ASM、JUnit5、coroutines、Jackson、SnakeYAML）。
- 编码规范：detekt 2.0.0-alpha.6（兼容 Gradle 9.6.1 / JDK 25）+ `.editorconfig`；`./gradlew lint` 聚合全部模块。
- CI（`.github/workflows/ci.yml`）：PR 触发 `build + test + lint` + Jacoco 覆盖率门禁（行覆盖 ≥ 60%）。
- 测试基座 `yux-test-support`：`GoldenFile`（golden 快照，`UPDATE_GOLDEN=1` 逃生阀）与 `SourceFile`/`Sources`（内存源文件工具）。
- `README.md`、`LICENSE`（Apache-2.0）、`CHANGELOG.md`。

### 说明

- detekt 采用 `2.0.0-alpha.6`（`dev.detekt`）：稳定版 1.23.8 不支持 JDK 25，且 2.0 alpha 已官方声明针对 Gradle 9.6.1 / JDK 25 构建。
- 编译目标 JDK 21（`jvmToolchain(21)`）；本地需具备 JDK 21（或由 foojay resolver 自动下载）。
