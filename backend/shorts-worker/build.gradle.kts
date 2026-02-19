plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib") // Jib 컨테이너화
}

dependencies {
    implementation(project(":shorts-core"))
    
    // Spring Web (for health check endpoint)
    implementation("org.springframework.boot:spring-boot-starter-web")
    
    // Kafka Consumer
    implementation("org.springframework.kafka:spring-kafka")
    
    // RSS Parsing (for content extraction in ProductionService)
    implementation("com.rometools:rome:2.1.0")
    implementation("org.jsoup:jsoup:1.17.2")
}

// Jib 컨테이너 설정
jib {
    from {
        image = "amazoncorretto:21-alpine"
    }
    to {
        image = "shorts-worker"
        tags = setOf("latest")
    }
    container {
        jvmFlags = listOf("-Xms512m", "-Xmx1024m")
        ports = listOf("8081")
        mainClass = "com.sciencepixel.ShortsWorkerApplicationKt"
    }
}
