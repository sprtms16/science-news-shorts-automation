# Build Stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# Copy root build files
COPY build.gradle.kts settings.gradle.kts ./

# Copy all modules
COPY shorts-core ./shorts-core
COPY shorts-worker ./shorts-worker

# Build the Worker module
RUN gradle :shorts-worker:clean :shorts-worker:build -x test --no-daemon

# Runtime Stage
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Install FFmpeg and Korean Fonts
RUN apt-get update && apt-get install -y ffmpeg fonts-nanum && rm -rf /var/lib/apt/lists/*

# Copy the built jar
COPY --from=build /app/shorts-worker/build/libs/*.jar /app/app.jar

# Run the application
ENTRYPOINT ["sh", "-c", "if [ -n \"$YOUTUBE_CLIENT_SECRET_JSON_B64\" ]; then echo \"$YOUTUBE_CLIENT_SECRET_JSON_B64\" | base64 -d > /app/client_secret.json; elif [ -n \"$YOUTUBE_CLIENT_SECRET_JSON\" ]; then echo \"$YOUTUBE_CLIENT_SECRET_JSON\" > /app/client_secret.json; fi && exec java -jar /app/app.jar"]
