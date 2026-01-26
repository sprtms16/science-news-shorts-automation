plugins {
    kotlin("jvm") version "1.9.20"
    application
}

group = "com.example.shorts"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    implementation("com.rometools:rome:2.1.0") // RSS Parsing
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ShortsMakerKt")
}

tasks.test {
    useJUnitPlatform()
}
