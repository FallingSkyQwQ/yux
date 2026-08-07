plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "YuxPlugin SDK 语言扩展层（编译期：event/command/config 等）"

dependencies {
    implementation(project(":yux-compiler:yux-compiler-plugin-api"))
    implementation(project(":yux-compiler:yux-compiler-core"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
