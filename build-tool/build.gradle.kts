plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "build.yml → Gradle 生成器（BuildYmlParser / GradleGenerator / BuildPlanner）"

dependencies {
    implementation(libs.snakeyaml)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
