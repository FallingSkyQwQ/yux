plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "yuxc 命令行入口"

dependencies {
    implementation(project(":yux-compiler:yux-compiler-core"))
    implementation(project(":yux-compiler:yux-compiler-backend-jvm"))
    implementation(project(":yux-compiler:yux-compiler-plugin-api"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
