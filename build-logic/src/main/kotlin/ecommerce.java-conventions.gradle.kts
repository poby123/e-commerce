// Base for every module: Java 21 toolchain, Spring BOMs as Gradle platforms, JUnit 5.
plugins {
    java
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.ecommerce"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

dependencies {
    "implementation"(platform(libs.findLibrary("spring-boot-dependencies").get()))
    "implementation"(platform(libs.findLibrary("spring-cloud-dependencies").get()))

    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
