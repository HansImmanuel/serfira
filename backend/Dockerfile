# Stage 1 — build the bootJar with a pinned Gradle + JDK 21 toolchain.
FROM gradle:9.7.1-jdk21 AS build
WORKDIR /workspace

# Cache dependency resolution independently of source changes.
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon

COPY src ./src
RUN gradle bootJar --no-daemon

# Stage 2 — slim runtime image, non-root.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
