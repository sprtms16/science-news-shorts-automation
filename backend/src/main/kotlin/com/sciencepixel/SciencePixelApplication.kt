package com.sciencepixel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class SciencePixelApplication

fun main(args: Array<String>) {
    runApplication<SciencePixelApplication>(*args)
}
