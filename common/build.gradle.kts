plugins {
    id("ecommerce.java-conventions")
    `java-library`
}

// Web classes are compileOnly: servlet services bring Spring MVC themselves, and the reactive
// gateway uses only the plain types (ErrorResponse, AuthHeaders) without pulling MVC in.
dependencies {
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
}
