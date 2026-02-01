package com.sciencepixel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableAsync
class SciencePixelApplication {
    @jakarta.annotation.PostConstruct
    fun init() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        println("🕒 Application TimeZone set to Asia/Seoul: ${java.time.LocalDateTime.now()}")
    }
}

fun main(args: Array<String>) {
    runApplication<SciencePixelApplication>(*args)
}
