package com.sciencepixel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootApplication
@ComponentScan(
    basePackages = ["com.sciencepixel"],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [com.sciencepixel.service.YoutubeService::class]
        )
    ]
)
@EnableAsync
@EnableScheduling
class WorkerApplication

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    println("🕒 Worker Application TimeZone set to Asia/Seoul: ${java.time.LocalDateTime.now()}")
    runApplication<WorkerApplication>(*args)
}
