# Build Stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# Copy root build files
COPY build.gradle.kts settings.gradle.kts ./

# Copy all modules
COPY shorts-core ./shorts-core
COPY shorts-api ./shorts-api

# Build the API module
RUN gradle :shorts-api:clean :shorts-api:build -x test --no-daemon

# Runtime Stage
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Install FFmpeg and Korean Fonts
RUN apt-get update && apt-get install -y ffmpeg fonts-nanum && rm -rf /var/lib/apt/lists/*

# Copy the built jar
COPY --from=build /app/shorts-api/build/libs/*.jar /app/app.jar

# Run the application with channel-specific secret support
# Priority: 1) Channel-specific B64 env var, 2) Generic B64 env var, 3) File mount
ENTRYPOINT ["sh", "-c", "\
    echo '🔍 Checking Secret Source for Channel: '${SHORTS_CHANNEL_ID:-science}'...'; \
    CHANNEL_SECRET=''; \
    case ${SHORTS_CHANNEL_ID:-science} in \
    science) CHANNEL_SECRET=${SECRET_SCIENCE_B64:-} ;; \
    horror) CHANNEL_SECRET=${SECRET_HORROR_B64:-} ;; \
    stocks) CHANNEL_SECRET=${SECRET_STOCKS_B64:-} ;; \
    history) CHANNEL_SECRET=${SECRET_HISTORY_B64:-} ;; \
    esac; \
    if [ -n \"$CHANNEL_SECRET\" ]; then \
    echo \"✅ Using Channel-Specific Secret (${SHORTS_CHANNEL_ID}_B64)\"; \
    echo \"$CHANNEL_SECRET\" | base64 -d > /app/client_secret.json; \
    elif [ -n \"$YOUTUBE_CLIENT_SECRET_JSON_B64\" ]; then \
    echo '⚠️ Using Generic Secret (YOUTUBE_CLIENT_SECRET_JSON_B64)'; \
    echo \"$YOUTUBE_CLIENT_SECRET_JSON_B64\" | base64 -d > /app/client_secret.json; \
    elif [ -f /app/client_secret/${SHORTS_CHANNEL_ID:-science}/client_secret.json ]; then \
    echo '✅ File Mount Found at /app/client_secret'; \
    elif [ -f /app/secrets/client_secret.json ]; then \
    echo '✅ Legacy File Mount Found'; \
    cp /app/secrets/client_secret.json /app/client_secret.json; \
    else \
    echo '⚠️ No client_secret found - YouTube features may fail'; \
    fi && \
    echo '🚀 Starting App...' && exec java -jar /app/app.jar"]
