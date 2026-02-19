plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib") // Jib 컨테이너화
}

dependencies {
    implementation(project(":shorts-core"))
    
    // Spring Web
    implementation("org.springframework.boot:spring-boot-starter-web")
    
    // Spring Batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2")
    
    // Kafka Producer
    implementation("org.springframework.kafka:spring-kafka")
    
    // RSS Parsing
    implementation("com.rometools:rome:2.1.0")
    implementation("org.jsoup:jsoup:1.17.2")
    
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Jib 컨테이너 설정
jib {
    from {
        image = "amazoncorretto:21-alpine"
    }
    to {
        image = "shorts-api"
        tags = setOf("latest")
    }
    container {
        jvmFlags = listOf("-Xms256m", "-Xmx512m")
        ports = listOf("8080")
        mainClass = "com.sciencepixel.ShortsApiApplicationKt"
    }
}
