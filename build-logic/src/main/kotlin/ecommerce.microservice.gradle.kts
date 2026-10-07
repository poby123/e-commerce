// A service that takes its configuration from config-server and registers itself in Eureka.
plugins {
    id("ecommerce.spring-boot-app")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(project(":common"))
    "implementation"("org.springframework.cloud:spring-cloud-starter-config")
    "implementation"("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    // Traces: Micrometer Observation -> OpenTelemetry bridge -> Zipkin. Logs: pushed to Loki (profile "loki").
    "implementation"("io.micrometer:micrometer-tracing-bridge-otel")
    "implementation"("io.opentelemetry:opentelemetry-exporter-zipkin")
    "runtimeOnly"(libs.findLibrary("loki-logback-appender").get())
}
