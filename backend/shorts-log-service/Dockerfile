FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# Copy build files for dependency resolution (cached layer)
COPY build.gradle.kts settings.gradle.kts ./

# Download dependencies (cached layer - only invalidated when dependencies change)
RUN gradle dependencies --parallel --no-daemon || true

# Copy source code
COPY . .

# Build (without clean for faster builds)
RUN gradle build -x test --parallel --no-daemon

FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
