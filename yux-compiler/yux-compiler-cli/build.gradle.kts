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

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

// M6 e2e：PluginE2eTest 需要 samples/extension 插件 jar（T-M6-6）
tasks.test {
    dependsOn(":samples:extension:jar")
}

application {
    mainClass.set("yux.cli.MainKt")
    applicationName = "yuxc"
}
