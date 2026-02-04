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
    private val kafkaEventPublisher: com.sciencepixel.event.KafkaEventPublisher,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) : ItemProcessor<NewsItem, VideoHistory> {

    override fun process(item: NewsItem): VideoHistory? {
        // 0. Final Buffer Check
        val limit = systemSettingRepository.findByChannelIdAndKey(channelId, "MAX_GENERATION_LIMIT")
            ?.value?.toIntOrNull() ?: 10
        
        val currentActive = videoHistoryRepository.findByChannelIdAndStatusNotIn(channelId, listOf(
            VideoStatus.UPLOADED, 
            VideoStatus.FAILED
        )).size
        
        if (currentActive >= limit) {
            println("🛑 [$channelId] Mid-Batch Check: Buffer limit reached ($currentActive >= $limit). Skipping: ${item.title}")
            return null
        }

        // 1. Duplicate Check (Link & Title)
        val normalizedLink = try {
            val url = java.net.URL(item.link)
            "${url.protocol}://${url.host}${url.path}" 
        } catch (e: Exception) {
            item.link
        }

        if (videoHistoryRepository.findByChannelIdAndLink(channelId, item.link) != null || 
            videoHistoryRepository.findByChannelIdAndLink(channelId, normalizedLink) != null) {
            println("⏭️ [$channelId] Skipped (Link Duplicate): $normalizedLink")
            return null
        }

        // Local DB Title Duplicate Check
        if (videoHistoryRepository.findByChannelIdAndTitle(channelId, item.title).isNotEmpty() || 
            videoHistoryRepository.findByChannelIdAndRssTitle(channelId, item.title).isNotEmpty()) {
            println("⏭️ [$channelId] Skipped (Local DB Title/RssTitle Duplicate): ${item.title}")
            return null
        }

        // Exact Title Match on YouTube Channel
        if (youtubeService.isTitleDuplicateOnChannel(item.title)) {
            println("⏭️ [$channelId] Skipped (YouTube Channel Title Duplicate): ${item.title}")
            return null
        }

        // 2. Semantic Deduplication (AI)
        val recentVideos = videoHistoryRepository.findAllByChannelIdOrderByCreatedAtDesc(
            channelId, 
            org.springframework.data.domain.PageRequest.of(0, 30)
        ).content
        
        if (geminiService.checkSimilarity(item.title, item.summary, recentVideos)) {
             println("⏭️ [$channelId] Skipped (High Semantic Similarity): ${item.title}")
             return null
        }

        // 3. Safety Filter
        if (!geminiService.checkSensitivity(item.title, item.summary, channelId)) {
             println("⛔ [$channelId] Skipped (Sensitive Content): ${item.title}")
             return null
        }

        try {
            // 4. Create Record (QUEUED) 
            val initialVideo = VideoHistory(
                channelId = channelId,
                title = item.title,
                summary = item.summary,
                link = item.link,
                status = VideoStatus.QUEUED,
                rssTitle = item.title,
                createdAt = java.time.LocalDateTime.now(),
                updatedAt = java.time.LocalDateTime.now()
            )
            videoHistoryRepository.save(initialVideo)
            
            // 5. Fire & Forget (SAGA Start)
            kafkaEventPublisher.publishRssNewItem(com.sciencepixel.event.RssNewItemEvent(
                channelId = channelId,
                url = item.link,
                title = item.title,
                category = "general"
            ))
            
            println("🚀 [$channelId] Event Published: ${item.title} (Status: QUEUED)")
            
            return null
        } catch (e: Exception) {
            println("⚠️ [$channelId] Failed to trigger process: ${item.title} - ${e.message}")
            return null
        }
    }
}
