// A service that takes its configuration from config-server and registers itself in Eureka.
plugins {
    id("ecommerce.spring-boot-app")
}

dependencies {
    "implementation"(project(":common"))
    "implementation"("org.springframework.cloud:spring-cloud-starter-config")
    "implementation"("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
}
