plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
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
