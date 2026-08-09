# Yux 编译基准（M11 T-M11-5）

M11 里程碑编译耗时 / 输出 jar 体积基线。复跑：`./bench/run-bench.sh --out bench/RESULTS.md`（先跑一次 warm-up 再复测；前置要求工作树可编译——`./gradlew :yux-compiler:yux-compiler-cli:installDist` 会连带构建 yux-stdlib）。波动说明：并发机器上冷编译受 Gradle daemon 与系统负载影响较大，单次测量可偏离中位数 30%+（本表内首次冷编译 #1 即含 daemon 启动与 Kotlin 守护预热）；增量编译反映缓存命中时的真实成本，相对稳定。

- 日期: 2026-08-10 00:42:43
- git: `e4affd1`（feature/m11-stdlib）
- JDK: openjdk version "21.0.12" 2026-07-21（JAVA_HOME=/usr/lib/jvm/java-21-openjdk）
- Gradle: 9.6.1
- 计时: /usr/bin/time -f %e（缺失时 date +%s.%N / $SECONDS）

## 结果

| 工程 | 命令 | 第1次(s) | 第2次(s) | 第3次(s) | 中位数(s) | 体积(B) |
|---|---|---|---|---|---|---|
| helloworld | 冷编译（build --clean） | 0.879 | 0.802 | 0.788 | 0.802 | 667 |
| helloworld | 增量编译（build，缓存命中） | 0.195 | 0.195 | 0.192 | 0.195 | 667 |
| mixed | 冷编译（build --clean） | 3.649 | 1.892 | 1.921 | 1.921 | 4498 |
| mixed | 增量编译（build，缓存命中） | 1.394 | 1.379 | 1.319 | 1.379 | 4498 |
| samples/hello.yux | run（纯编译 + 运行） | 0.176 | 0.159 | 0.180 | 0.176 | N/A（无 jar） |

注：体积为 `build/libs/*.jar` 产物字节数；hello.yux 为纯编译样例，无 jar 产物。
