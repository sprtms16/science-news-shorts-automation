package com.sciencepixel.controller

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.service.ProductionService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ManualRequest(
    val title: String,
    val summary: String
)

@RestController
@RequestMapping("/manual")
class ManualGenerationController(
    private val productionService: ProductionService
) {

    @PostMapping("/create")
    fun createVideo(@RequestBody request: ManualRequest): String {
        println("🛠️ Manual Video Generation Requested: ${request.title}")
        
        val news = NewsItem(
            title = request.title,
            summary = request.summary,
            link = "manual-trigger"
        )
        
        val filePath = productionService.produceVideo(news)
        
        return if (filePath.isNotEmpty()) {
            "✅ Video created successfully: $filePath"
        } else {
            "❌ Failed to create video."
        }
    }
}
