package com.sciencepixel.batch

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.repository.SystemSettingRepository
import com.sciencepixel.service.ProductionService
import com.sciencepixel.service.GeminiService
import org.springframework.batch.item.ItemProcessor
import org.springframework.stereotype.Component
import org.springframework.data.domain.Sort

@Component
class VideoProcessor(
    private val videoHistoryRepository: VideoHistoryRepository,
    private val systemSettingRepository: SystemSettingRepository,
    private val geminiService: GeminiService,
    private val kafkaEventPublisher: com.sciencepixel.event.KafkaEventPublisher
) : ItemProcessor<NewsItem, VideoHistory> {

    override fun process(item: NewsItem): VideoHistory? {
        // 0. Final Buffer Check
        val limit = systemSettingRepository.findById("MAX_GENERATION_LIMIT")
            .map { it.value.toIntOrNull() ?: 10 }
            .orElse(10)
        
        val currentActive = videoHistoryRepository.findAll().filter { it.status != "UPLOADED" && it.status != "FAILED" && it.status != "ERROR" }.size
        if (currentActive >= limit) {
            println("🛑 Mid-Batch Check: Buffer limit reached ($currentActive >= $limit). Skipping: ${item.title}")
            return null
        }

        // 1. Duplicate Check
        if (videoHistoryRepository.findByLink(item.link) != null) {
            return null
        }

        // 2. Semantic Deduplication (AI)
        val recentVideos = videoHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).take(20)
        if (geminiService.checkSimilarity(item.title, item.summary, recentVideos)) {
             println("⏭️ Skipped (High Semantic Similarity): ${item.title}")
             return null
        }

        // 3. Safety Filter
        if (!geminiService.checkSensitivity(item.title, item.summary)) {
             println("⛔ Skipped (Sensitive Content): ${item.title}")
             return null
        }

        try {
            // 4. Create Record (QUEUED) -> Reserves the slot immediately
            val initialVideo = VideoHistory(
                title = item.title,
                summary = item.summary,
                link = item.link,
                status = "QUEUED",
                createdAt = java.time.LocalDateTime.now(),
                updatedAt = java.time.LocalDateTime.now()
            )
            videoHistoryRepository.save(initialVideo)
            
            // 5. Fire & Forget (SAGA Start)
            kafkaEventPublisher.publishRssNewItem(com.sciencepixel.event.RssNewItemEvent(
                url = item.link,
                title = item.title,
                category = "general"
            ))
            
            println("🚀 [Batch] Event Published: ${item.title} (Status: QUEUED)")
            
            // Return null because we handled persistence and event publishing manually.
            // This prevents the ItemWriter from trying to save it again or doing duplicate work.
            return null

        } catch (e: Exception) {
            println("⚠️ Failed to trigger process: ${item.title} - ${e.message}")
            return null
        }
    }
}
