plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "build.yml → Gradle 生成器（BuildYmlParser / GradleGenerator / BuildPlanner / GradleRunner / Incremental）"

dependencies {
    implementation(libs.snakeyaml)
    // M7（T-M7-3/5）：yuxc build 在进程内编排 Yux 编译（core）+ 字节码产物（backend-jvm）
    implementation(project(":yux-compiler:yux-compiler-core"))
    implementation(project(":yux-compiler:yux-compiler-backend-jvm"))

    testImplementation(project(":yux-compiler:yux-test-support"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
