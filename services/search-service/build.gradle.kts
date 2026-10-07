plugins {
    id("ecommerce.microservice")
}

// No database: the read model lives in Elasticsearch, fed by product.events (CQRS, ADR-0011).
dependencies {
    implementation(project(":messaging"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("co.elastic.clients:elasticsearch-java")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:elasticsearch")
    testImplementation(libs.wiremock.standalone)
}
