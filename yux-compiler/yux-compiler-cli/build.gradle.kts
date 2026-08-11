plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "yuxc 命令行入口"

dependencies {
    implementation(project(":yux-compiler:yux-compiler-core"))
    implementation(project(":yux-compiler:yux-compiler-backend-jvm"))
    implementation(project(":yux-compiler:yux-compiler-plugin-api"))
    implementation(project(":yux-stdlib"))
    implementation(project(":build-tool")) // M7（T-M7-5）：yuxc build/run/test -p 项目命令
    // M8：sema 解析 Paper 类型 + 下沉产物引用运行时 API（yuxc --plugin yux-compiler-minecraft）
    implementation(project(":yux-plugin-minecraft:yux-minecraft-runtime"))
    implementation(libs.paper.api)
    implementation(libs.clikt) // M16（T-M16-1）：Clikt 声明式子命令/help/usage/completion

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

// M16（T-M16-5）：`yuxc version` 的版本号单一事实源 = Gradle project.version，
// 构建时生成 `yuxc-version.properties` 进 resources，CLI 运行时读取。
val generateVersionProperties by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/resources/yuxc")
    inputs.property("version", project.version.toString())
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "yuxc-version.properties").writeText("version=${project.version}\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersionProperties)
}

tasks.processResources {
    dependsOn(generateVersionProperties)
    // Gradle 默认排除点文件（`.gitignore` 等）：模板资源需要它们，显式清空默认排除
    setExcludes(emptyList())
}

// M6 e2e：PluginE2eTest 需要 samples/extension 插件 jar（T-M6-6）
tasks.test {
    dependsOn(":samples:extension:jar")
}

application {
    mainClass.set("yux.cli.MainKt")
    applicationName = "yuxc"
}
