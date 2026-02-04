package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.event.StockDiscoveryRequestedEvent
import com.sciencepixel.repository.RssSourceRepository
import com.sciencepixel.service.ContentProviderService
import com.sciencepixel.service.AsyncVideoService
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import com.sciencepixel.domain.NewsItem

@Component
class StockDiscoveryConsumer(
    private val contentProviderService: ContentProviderService,
    private val asyncVideoService: AsyncVideoService,
    private val rssSourceRepository: RssSourceRepository,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(topics = [KafkaConfig.TOPIC_STOCK_DISCOVERY_REQUESTED], groupId = KafkaConfig.GROUP_MAIN)
    fun handleStockDiscoveryRequest(message: String) {
        try {
            val event = objectMapper.readValue(message, StockDiscoveryRequestedEvent::class.java)
            println("📉 [StockConsumer] Received request for channel: ${event.channelId}")

            // 1. Validate Channel
            if (event.channelId != "stocks") {
                println("⚠️ [StockConsumer] Ignored non-stock channel request: ${event.channelId}")
                return
            }

            // 2. Find Active Source (Assume only 1 active stock source for now, or pick first)
            val sources = rssSourceRepository.findByChannelIdAndIsActive(event.channelId, true)
            if (sources.isEmpty()) {
                println("⚠️ [StockConsumer] No active RSS sources found for '${event.channelId}'")
                return
            }
            val source = sources.first() // Use the primary source

            println("🚀 [StockConsumer] Starting Deep-Dive Discovery using source: ${source.title}")

            // 3. Execute Deep Dive (Time Consuming: Google News + Gemini Analysis)
            val discoveredItems = contentProviderService.fetchStockNews(source)

            println("✅ [StockConsumer] Discovery Complete. Found ${discoveredItems.size} items.")

            // 4. Trigger Async Video Creation for each item
            // Limit to 1 item per batch as per requirement (or loop if needed, but usually 1 is enough for daily)
            discoveredItems.take(1).forEach { newsItem ->
                println("🎥 [StockConsumer] Triggering Video Creation for: ${newsItem.title}")
                
                 // Create History Record First
                val history = VideoHistory(
                    channelId = event.channelId,
                    title = newsItem.title,
                    link = newsItem.link,
                    summary = newsItem.summary,
                    status = VideoStatus.QUEUED,
                    updatedAt = java.time.LocalDateTime.now()
                )
                val savedHistory = videoHistoryRepository.save(history)
                
                // Async Create
                asyncVideoService.createVideoAsync(newsItem, savedHistory.id!!)
            }

        } catch (e: Exception) {
            println("❌ [StockConsumer] Error processing request: ${e.message}")
            e.printStackTrace()
        }
    }
}
