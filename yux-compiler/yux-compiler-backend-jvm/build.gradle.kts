plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "Yux JVM 后端：IR → .class（ASM）"

dependencies {
    implementation(project(":yux-compiler:yux-compiler-core"))
    implementation(libs.asm)
    implementation(libs.asm.commons)

    testImplementation(project(":yux-stdlib"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
