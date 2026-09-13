# Build context is the repository root (docker-compose.yml sets it) so the Gradle wrapper is available.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts ./
COPY gradle gradle
COPY backend backend
RUN sh ./gradlew :backend:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
