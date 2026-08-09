plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "YuxPlugin SDK 运行时层（bootstrap/event/command/config/NBT/调度/util）"

dependencies {
    // Paper API 由服务器提供（03-§2）：编译期 + 测试期引用，运行期 provided
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)

    implementation(libs.snakeyaml) // ConfigManager YAML（T-M8-8）

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
