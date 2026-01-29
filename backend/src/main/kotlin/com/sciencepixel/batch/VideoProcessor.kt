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
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val systemSettingRepository: SystemSettingRepository,
    private val geminiService: GeminiService
) : ItemProcessor<NewsItem, VideoHistory> {

    override fun process(item: NewsItem): VideoHistory? {
        // 0. Final Buffer Check: Ensure we don't exceed the limit
        val limit = systemSettingRepository.findById("MAX_GENERATION_LIMIT")
            .map { it.value.toIntOrNull() ?: 10 }
            .orElse(10)
        
        val currentActive = videoHistoryRepository.findAll().filter { it.status != "UPLOADED" }.size
        if (currentActive >= limit) {
            println("🛑 Mid-Batch Check: Buffer Full ($currentActive >= $limit). Skipping production for: ${item.title}")
            return null
        }

        // 1. 중복 체크 (Link 기준)
        if (videoHistoryRepository.findByLink(item.link) != null) {
            println("⏭️ Skipping duplicate: ${item.title}")
            return null
        }

        // 2. Semantic Deduplication (AI)
        // Fetch last 20 videos to compare context
        val recentVideos = videoHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).take(20)
        
        if (geminiService.checkSimilarity(item.title, item.summary, recentVideos)) {
             println("⏭️ Semantic Deduplication: Video skipped due to high similarity with recent content. Title: ${item.title}")
             return null
        }

        // 3. Safety & Sensitivity Filter (Politics/Religion/Ideology)
        if (!geminiService.checkSensitivity(item.title, item.summary)) {
             println("⛔ Safety Filter: Skipped sensitive topic. Title: ${item.title}")
             return null
        }

        return try {
            // 4. Create Initial Record (PROCESSING) to reflect buffer count immediately
            val initialVideo = VideoHistory(
                title = item.title,
                summary = item.summary,
                link = item.link,
                status = "PROCESSING"
            )
            val savedVideo = videoHistoryRepository.save(initialVideo)
            println("▶️ Starting processing for: ${item.title} (ID: ${savedVideo.id})")

            val result = try {
                productionService.produceVideo(item)
            } catch (e: Exception) {
                println("⚠️ Production failed for: ${item.title} - ${e.message}")
                ProductionResult("", emptyList())
            }

            val videoPath = result.filePath
            
            if (videoPath.isNotBlank() && result.title.isNotBlank()) {
                savedVideo.copy(
                    title = result.title,
                    description = result.description,
                    filePath = videoPath,
                    status = "COMPLETED",
                    tags = result.tags,
                    sources = result.sources,
                    updatedAt = java.time.LocalDateTime.now()
                )
            } else {
                println("⚠️ Incomplete production for: ${item.title} (Path: $videoPath, Title: ${result.title})")
                savedVideo.copy(
                    status = "ERROR",
                    updatedAt = java.time.LocalDateTime.now()
                )
            }
        } catch (e: Exception) {
            println("⚠️ Failed to process: ${item.title} - ${e.message}")
            null // Let Batch handle it or it's already in DB as PROCESSING (will be cleaned by scheduler later)
        }
    }
}
