// A runnable Spring Boot application (infra servers and business services).
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("ecommerce.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "runtimeOnly"("io.micrometer:micrometer-registry-prometheus")
}

// Only the executable jar, under a fixed name so docker/Dockerfile can copy it.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "app.jar"
}

// Run from the repo root so relative paths like ./config-repo resolve the same way for every module.
tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
