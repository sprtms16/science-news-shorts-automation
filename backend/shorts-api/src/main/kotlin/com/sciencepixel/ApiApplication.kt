package com.sciencepixel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

@SpringBootApplication(scanBasePackages = ["com.sciencepixel"])
@EnableAsync
@EnableScheduling
class ApiApplication

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    println("🕒 API Application TimeZone set to Asia/Seoul: ${java.time.LocalDateTime.now()}")
    runApplication<ApiApplication>(*args)
}
