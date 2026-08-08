pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "yux"

// ── yux-compiler ──────────────────────────────────────────────────────────────
include(":yux-compiler:yux-compiler-core")
include(":yux-compiler:yux-compiler-backend-jvm")
include(":yux-compiler:yux-compiler-plugin-api")
include(":yux-compiler:yux-compiler-cli")
include(":yux-compiler:yux-test-support")

// ── yux-plugin-minecraft ──────────────────────────────────────────────────────
include(":yux-plugin-minecraft:yux-compiler-minecraft")
include(":yux-plugin-minecraft:yux-minecraft-runtime")

// ── 其他 ──────────────────────────────────────────────────────────────────────
include(":yux-stdlib")
include(":build-tool")

// ── samples ───────────────────────────────────────────────────────────────────
include(":samples:extension")
