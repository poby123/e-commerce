# Image for any Spring Boot module. Build the jar first (./gradlew bootJar), then:
#   docker build -f docker/Dockerfile --build-arg MODULE=services/user-service .
FROM eclipse-temurin:21-jre AS extract
ARG MODULE
WORKDIR /build
COPY ${MODULE}/build/libs/app.jar app.jar
# Split into layers so a code change rebuilds only the small "application" layer, not the dependencies.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
