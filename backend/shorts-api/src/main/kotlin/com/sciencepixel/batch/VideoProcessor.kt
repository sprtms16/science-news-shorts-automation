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
    private val channelBehavior: com.sciencepixel.config.ChannelBehavior,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) : ItemProcessor<List<NewsItem>, VideoHistory> {

    override fun process(bundle: List<NewsItem>): VideoHistory? {
        // 0. Final Buffer Check
        val limit = systemSettingRepository.findByChannelIdAndKey(channelId, "MAX_GENERATION_LIMIT")
            ?.value?.toIntOrNull() ?: channelBehavior.dailyLimit
        
        val currentActive = videoHistoryRepository.findByChannelIdAndStatusNotIn(channelId, listOf(
            VideoStatus.UPLOADED, 
            VideoStatus.FAILED
        )).size
        
        if (currentActive >= limit) {
            println("🛑 [$channelId] Mid-Batch Check: Buffer limit reached ($currentActive >= $limit). Skipping batch bundle.")
            return null
        }

        // 1. Iterate through candidates (Maximum 10-retry safety logic)
        bundle.forEachIndexed { index, item ->
            val attempt = index + 1
            println("🔍 [$channelId] Safety Check candidate $attempt/${bundle.size}: ${item.title}")

            // 1.1 Duplication Checks
            val normalizedLink = try {
                val url = java.net.URL(item.link)
                "${url.protocol}://${url.host}${url.path}" 
            } catch (e: Exception) {
                item.link
            }

            if (videoHistoryRepository.findByChannelIdAndLink(channelId, item.link) != null || 
                videoHistoryRepository.findByChannelIdAndLink(channelId, normalizedLink) != null) {
                println("  ⏭️ candidate $attempt skipped (Link Duplicate)")
                return@forEachIndexed
            }

            if (videoHistoryRepository.findByChannelIdAndTitle(channelId, item.title).isNotEmpty() || 
                videoHistoryRepository.findByChannelIdAndRssTitle(channelId, item.title).isNotEmpty()) {
                println("  ⏭️ candidate $attempt skipped (Local DB Title Duplicate)")
                return@forEachIndexed
            }

            if (youtubeService.isTitleDuplicateOnChannel(item.title)) {
                println("  ⏭️ candidate $attempt skipped (YouTube Channel Title Duplicate)")
                return@forEachIndexed
            }

            try {
                // 1.2 Semantic Deduplication
                val recentVideos = videoHistoryRepository.findAllByChannelIdOrderByCreatedAtDesc(
                    channelId, 
                    org.springframework.data.domain.PageRequest.of(0, 30)
                ).content
                
                if (geminiService.checkSimilarity(item.title, item.summary, recentVideos)) {
                     println("  ⏭️ candidate $attempt skipped (High Semantic Similarity)")
                     return@forEachIndexed
                }
    
                // 1.3 Safety Filter
                if (!geminiService.checkSensitivity(item.title, item.summary, channelId)) {
                     println("  ⛔ candidate $attempt skipped (Sensitive Content)")
                     return@forEachIndexed
                }
            } catch (e: Exception) {
                println("  ⚠️ candidate $attempt skipped (Gemini API Error: ${e.message})")
                return@forEachIndexed
            }

            // If we reach here, the item is safe and not a duplicate!
            println("✅ [$channelId] Candidate $attempt selected: ${item.title}")

            try {
                // 2. Create Record (QUEUED) 
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
                val savedVideo = videoHistoryRepository.save(initialVideo)
                
                // 3. Fire & Forget (SAGA Start)
                kafkaEventPublisher.publishRssNewItem(com.sciencepixel.event.RssNewItemEvent(
                    channelId = channelId,
                    url = item.link,
                    title = item.title,
                    category = "general"
                ))
                
                println("🚀 [$channelId] Event Published at attempt $attempt: ${item.title}")
                
                // Return the first successful one
                return savedVideo
            } catch (e: Exception) {
                println("⚠️ [$channelId] Failed to trigger process at attempt $attempt: ${item.title} - ${e.message}")
            }
        }

        println("❌ [$channelId] All ${bundle.size} candidates in bundle failed safety/duplicate checks.")
        return null
    }
}
