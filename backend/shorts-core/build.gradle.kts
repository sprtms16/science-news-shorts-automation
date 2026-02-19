plugins {
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.0")
    }
}

dependencies {
    // Common
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")
    
    // Kotlin Coroutines (비동기 처리)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    // MongoDB
    api("org.springframework.boot:spring-boot-starter-data-mongodb")
    
    // Kafka (Common DTOs and Config)
    api("org.springframework.kafka:spring-kafka")
    
    // YouTube API
    api("com.google.apis:google-api-services-youtube:v3-rev20231011-2.0.0")
    api("com.google.api-client:google-api-client:2.2.0")
    api("com.google.auth:google-auth-library-oauth2-http:1.19.0")
    api("com.google.http-client:google-http-client-jackson2:1.44.1")
    
    // HTTP Client
    api("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON
    api("org.json:json:20231013")

    // RSS Parsing
    api("com.rometools:rome:2.1.0")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
}

// This is a library module, not a bootable app
tasks.getByName<Jar>("jar") {
    enabled = true
}
