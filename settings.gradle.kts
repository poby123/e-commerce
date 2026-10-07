pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK declared by the toolchain (Java 21) if it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "e-commerce"

include(
    "common",
    "infra:discovery-server",
    "infra:config-server",
    "infra:gateway",
    "services:user-service",
    "services:product-service",
    "services:inventory-service",
    "services:order-service",
)
