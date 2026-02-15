# Build Stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# Copy root build files
COPY build.gradle.kts settings.gradle.kts ./

# Copy module build files for dependency resolution (cached layer)
COPY shorts-core/build.gradle.kts ./shorts-core/build.gradle.kts
COPY shorts-worker/build.gradle.kts ./shorts-worker/build.gradle.kts

# Download dependencies (cached layer - only invalidated when dependencies change)
RUN gradle :shorts-worker:dependencies --parallel --no-daemon || true

# Copy all modules source code
COPY shorts-core ./shorts-core
COPY shorts-worker ./shorts-worker

# Build the Worker module (without clean for faster builds)
RUN gradle :shorts-worker:build -x test --parallel --no-daemon

# Runtime Stage
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Install FFmpeg and Korean Fonts
RUN apt-get update && apt-get install -y ffmpeg fonts-nanum && rm -rf /var/lib/apt/lists/*

# Copy the built jar
COPY --from=build /app/shorts-worker/build/libs/*.jar /app/app.jar

# Run the application
ENTRYPOINT ["sh", "-c", "exec java -jar /app/app.jar"]
