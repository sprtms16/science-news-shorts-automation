package com.sciencepixel.batch

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
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
    private val youtubeService: com.sciencepixel.service.YoutubeService,
    private val kafkaEventPublisher: com.sciencepixel.event.KafkaEventPublisher
) : ItemProcessor<NewsItem, VideoHistory> {

    override fun process(item: NewsItem): VideoHistory? {
        // 0. Final Buffer Check
        val limit = systemSettingRepository.findById("MAX_GENERATION_LIMIT")
            .map { it.value.toIntOrNull() ?: 10 }
            .orElse(10)
        
        val currentActive = videoHistoryRepository.findByStatusNotIn(listOf(
            VideoStatus.UPLOADED, 
            VideoStatus.REGEN_FAILED, 
            VideoStatus.ERROR,
            VideoStatus.PERMANENTLY_FAILED
        )).size
        if (currentActive >= limit) {
            println("🛑 Mid-Batch Check: Buffer limit reached ($currentActive >= $limit). Skipping: ${item.title}")
            return null
        }

        // 1. Duplicate Check (Link & Title)
        val normalizedLink = try {
            val url = java.net.URL(item.link)
            "${url.protocol}://${url.host}${url.path}" // Remove query parameters & fragments
        } catch (e: Exception) {
            item.link
        }

        if (videoHistoryRepository.findByLink(item.link) != null || videoHistoryRepository.findByLink(normalizedLink) != null) {
            println("⏭️ Skipped (Link Duplicate): $normalizedLink")
            return null
        }

        // Local DB Title Duplicate Check
        if (videoHistoryRepository.findByTitle(item.title).isNotEmpty()) {
            println("⏭️ Skipped (Local DB Title Duplicate): ${item.title}")
            return null
        }

        // Exact Title Match on YouTube Channel
        if (youtubeService.isTitleDuplicateOnChannel(item.title)) {
            println("⏭️ Skipped (YouTube Channel Title Duplicate): ${item.title}")
            return null
        }

        // 2. Semantic Deduplication (AI)
        val recentVideos = videoHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).take(15) // Slightly reduced from 20 for efficiency
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
                status = VideoStatus.QUEUED,
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
