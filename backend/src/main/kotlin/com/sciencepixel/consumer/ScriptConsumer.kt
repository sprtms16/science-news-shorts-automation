package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.RssNewItemEvent
import com.sciencepixel.event.ScriptCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.GeminiService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.*

@Component
class ScriptConsumer(
    private val geminiService: GeminiService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_RSS_NEW_ITEM],
        groupId = KafkaConfig.GROUP_MAIN
    )
    fun consumeRssItem(message: String) {
        try {
            val event = objectMapper.readValue(message, RssNewItemEvent::class.java)
            println("▶️ [ScriptConsumer] Received RSS item: ${event.title}")

            // 1. Create or Get History (Idempotency)
            val history = getOrCreateHistory(event)
            
            if (history.status == VideoStatus.COMPLETED || history.status == VideoStatus.UPLOADED) {
                println("⚠️ Video already completed for: ${event.title}. Skipping.")
                return
            }

            // 2. Call Gemini
            println("🤖 generating script for: ${event.title}...")
            val scriptResponse = geminiService.writeScript(event.title, event.title) // RSS summary might be null, use title or fetch content if needed

            if (scriptResponse.scenes.isEmpty()) {
                println("⚠️ Empty script generated. Marking as ERROR.")
                videoHistoryRepository.save(history.copy(
                    status = VideoStatus.ERROR_SCRIPT_EMPTY,
                    updatedAt = LocalDateTime.now()
                ))
                return
            }

            // 3. Update History with Script Data
            val updatedHistory = videoHistoryRepository.save(history.copy(
                status = VideoStatus.SCRIPT_READY,
                title = scriptResponse.title, // Update title with the generated Korean title
                description = scriptResponse.description, // Correctly use description field
                tags = scriptResponse.tags,
                sources = scriptResponse.sources,
                updatedAt = LocalDateTime.now()
            ))

            // 4. Publish next event
            eventPublisher.publishScriptCreated(ScriptCreatedEvent(
                videoId = updatedHistory.id!!,
                title = scriptResponse.title,
                script = objectMapper.writeValueAsString(scriptResponse.scenes), // Serialize scenes to string to pass along
                summary = scriptResponse.description,
                sourceLink = event.url,
                keywords = scriptResponse.tags
            ))

            println("✅ [ScriptConsumer] Script created & event published: ${event.title}")

        } catch (e: Exception) {
            println("❌ [ScriptConsumer] Error: ${e.message}")
            e.printStackTrace()
            // Optional: Publish to DLQ
        }
    }

    private fun getOrCreateHistory(event: RssNewItemEvent): VideoHistory {
        // Simple check by link (assuming unique per news)
        val existing = videoHistoryRepository.findByLink(event.url)
        if (existing != null) return existing

        val initialVideo = VideoHistory(
            id = UUID.randomUUID().toString(),
            title = event.title,
            summary = "", // Initial summary
            link = event.url,
            status = VideoStatus.QUEUED,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        return videoHistoryRepository.save(initialVideo)
    }
}
