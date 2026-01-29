package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.Scene
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.ScriptCreatedEvent
import com.sciencepixel.event.VideoAssetsReadyEvent

import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.ProductionService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class SceneConsumer(
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_SCRIPT_CREATED],
        groupId = KafkaConfig.GROUP_MAIN
    )
    fun consumeScript(message: String) {
        try {
            val event = objectMapper.readValue(message, ScriptCreatedEvent::class.java)
            println("▶️ [SceneConsumer] Received Script event: ${event.title}")

            // Deserialize script (List<Scene>)
            val scenes: List<Scene> = objectMapper.readValue(event.script)

            // Update Status
            val history = videoHistoryRepository.findById(event.videoId).orElse(null)
            if (history != null) {
                videoHistoryRepository.save(history.copy(
                    status = "PROCESSING_ASSETS",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }

            // Call Production Service to generate assets (Clips)
            // We need to refactor ProductionService to expose a method that returns clip paths
            val assetResult = productionService.produceAssetsOnly(event.title, scenes, event.videoId)

            if (assetResult.clipPaths.isEmpty()) {
                println("❌ Assets generation failed (empty clips).")
                videoHistoryRepository.save(history!!.copy(
                    status = "ERROR_ASSETS",
                    updatedAt = java.time.LocalDateTime.now()
                ))
                return
            }

            // Publish Next Event
            eventPublisher.publishVideoAssetsReady(VideoAssetsReadyEvent(
                videoId = event.videoId,
                title = event.title,
                mood = assetResult.mood,
                clipPaths = assetResult.clipPaths,
                durations = assetResult.durations,
                subtitles = assetResult.subtitles,
                keywords = event.keywords,
                scriptEvent = event
            ))

            println("✅ [SceneConsumer] Assets ready & event published: ${event.videoId}")

        } catch (e: Exception) {
            println("❌ [SceneConsumer] Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
