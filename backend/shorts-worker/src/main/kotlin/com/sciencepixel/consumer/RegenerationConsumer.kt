package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.*
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.ProductionService
import com.sciencepixel.service.LogPublisher
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

/**
 * 비디오 재생성 Consumer
 * RegenerationRequestedEvent를 구독하여 비디오 재생성 수행
 */
@Service
class RegenerationConsumer(
    private val repository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val logPublisher: LogPublisher,
    private val objectMapper: ObjectMapper,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    companion object {
        private const val MAX_REGEN_COUNT = 1
    }

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_REGENERATION_REQUESTED],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-regen-group}"
    )
    fun handleRegenerationRequested(message: String) {
        val event = objectMapper.readValue(message, RegenerationRequestedEvent::class.java)
        if (event.channelId != channelId) return
        
        println("📥 [$channelId] Received RegenerationRequestedEvent: ${event.videoId}")

        if (event.regenCount >= MAX_REGEN_COUNT) {
            println("🚫 Max regeneration attempts reached: ${event.videoId}")
            repository.findById(event.videoId).ifPresent { video ->
                repository.save(video.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "REGEN",
                    errorMessage = "Max regeneration attempts reached",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
            logPublisher.warn("shorts-controller", "Max regeneration attempts reached", "Video ID: ${event.videoId}", traceId = event.videoId)
            eventPublisher.publishToDeadLetterQueue(event, "Max regeneration attempts reached")
            return
        }

        println("🔄 Regenerating video: ${event.title}")

        try {
            val existingVideo = repository.findById(event.videoId).orElse(null)
            if (existingVideo != null && existingVideo.filePath.isNotBlank() && java.io.File(existingVideo.filePath).exists()) {
                println("⏭️ File already exists for ${event.title}. Skipping AI generation and re-using existing file to save tokens.")
                
                // Status를 COMPLETED로 돌려 스케줄러가 다시 집도록 함
                repository.save(existingVideo.copy(
                    status = VideoStatus.COMPLETED,
                    retryCount = 0,
                    updatedAt = java.time.LocalDateTime.now()
                ))
                
                // 새로운 VideoCreatedEvent 발행 (바로 업로드 시도)
                eventPublisher.publishVideoCreated(VideoCreatedEvent(
                    channelId = channelId, // 추가
                    videoId = event.videoId,
                    title = event.title,
                    summary = existingVideo.summary,
                    description = existingVideo.description,
                    link = event.link,
                    filePath = existingVideo.filePath,
                    keywords = existingVideo.tags
                ))
                return
            }

            // Since we removed the monolithic produceVideo, we'll re-trigger the pipeline flow
            // instead of calling ProductionService directly.
            // 1. Reset Status to CREATING
            // 2. Publish RssNewItemEvent (treat as new, but update regen count)
            
            repository.findById(event.videoId).ifPresent { video ->
                repository.save(video.copy(
                    status = VideoStatus.QUEUED, // Reset to QUEUED to be picked up by ScriptConsumer again
                    regenCount = event.regenCount + 1,
                    retryCount = 0,
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }

            // Re-publish as RSS Event to restart the flow
            val rssEvent = RssNewItemEvent(
                channelId = channelId,
                title = event.title,
                summary = event.summary,
                url = event.link,
                publishedAt = java.time.LocalDateTime.now().toString()
            )
            
            // Wait, ScriptConsumer checks history "getOrCreateHistory". 
            // If history exists, it uses it.
            // So if we reset status to QUEUED, ScriptConsumer will pick it up and confirm it as QUEUED and process it.
            
            eventPublisher.publishRssNewItem(rssEvent)
            
            println("✅ Regeneration trigger re-queued via RSS Event: ${event.title}")
            logPublisher.info("shorts-controller", "Regeneration Re-queued", "Title: ${event.title}", traceId = event.videoId)

        } catch (e: Exception) {
            logPublisher.error("shorts-controller", "Regeneration Error: ${event.title}", "Error: ${e.message}", traceId = event.videoId)
            println("❌ Regeneration error: ${e.message}")
            e.printStackTrace()
            repository.findById(event.videoId).ifPresent { video ->
                repository.save(video.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "REGEN",
                    errorMessage = e.message ?: "Unknown Regeneration Error",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
        }
    }
}
