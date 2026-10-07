plugins {
    id("ecommerce.java-conventions")
    `java-library`
}

// Outbox writer, idempotent-consumer guard, Kafka error handling, and the message contracts shared by services.
dependencies {
    api("org.springframework.kafka:spring-kafka")
    api("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("io.micrometer:micrometer-tracing")

    testImplementation("org.springframework:spring-jdbc")
}
