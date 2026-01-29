package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.VideoAssetsReadyEvent
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.event.ScriptCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.ProductionService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class RenderConsumer(
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_ASSETS_READY],
        groupId = KafkaConfig.GROUP_MAIN
    )
    fun consumeAssets(message: String) {
        try {
            val event = objectMapper.readValue(message, VideoAssetsReadyEvent::class.java)
            println("▶️ [RenderConsumer] Received Assets Ready event: ${event.videoId}")

            val history = videoHistoryRepository.findById(event.videoId).orElse(null)
            if (history != null) {
                videoHistoryRepository.save(history.copy(status = "RENDERING"))
            }

            // Call Production Service to finalize video (Merge & Burn)
            val finalPath = productionService.finalizeVideo(
                videoId = event.videoId,
                title = event.title,
                clipPaths = event.clipPaths,
                durations = event.durations,
                subtitles = event.subtitles,
                mood = event.mood
            )

            if (finalPath.isEmpty()) {
                println("❌ Rendering failed (empty path).")
                videoHistoryRepository.save(history!!.copy(status = "ERROR_RENDERING"))
                return
            }

            // Update History to COMPLETED (Ready for Upload)
            val completedHistory = videoHistoryRepository.save(history!!.copy(
                status = "COMPLETED",
                filePath = finalPath
            ))

            // Publish 'video.created' -> This triggers the existing VideoUploadConsumer!
            // We bridge the new SAGA pipeline to the existing Upload pipeline here.
            eventPublisher.publishVideoCreated(VideoCreatedEvent(
                videoId = event.videoId,
                title = event.title,
                summary = event.scriptEvent?.summary ?: "",
                link = event.scriptEvent?.sourceLink ?: "",
                filePath = finalPath,
                keywords = event.keywords
            ))

            println("✅ [RenderConsumer] Video Finalized & Upload Event Published: $finalPath")

        } catch (e: Exception) {
            println("❌ [RenderConsumer] Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
