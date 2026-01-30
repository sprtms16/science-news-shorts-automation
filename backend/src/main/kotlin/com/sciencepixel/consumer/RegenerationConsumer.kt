package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.*
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.ProductionService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

/**
 * 비디오 재생성 Consumer
 * RegenerationRequestedEvent를 구독하여 비디오 재생성 수행
 */
@Service
class RegenerationConsumer(
    private val repository: VideoHistoryRepository,
    private val productionService: ProductionService,
    private val eventPublisher: KafkaEventPublisher,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val MAX_REGEN_COUNT = 1
    }

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_REGENERATION_REQUESTED],
        groupId = KafkaConfig.GROUP_REGEN
    )
    fun handleRegenerationRequested(message: String) {
        val event = objectMapper.readValue(message, RegenerationRequestedEvent::class.java)
        println("📥 Received RegenerationRequestedEvent: ${event.videoId}")

        if (event.regenCount >= MAX_REGEN_COUNT) {
            println("🚫 Max regeneration attempts reached: ${event.videoId}")
            repository.findById(event.videoId).ifPresent { video ->
                repository.save(video.copy(
                    status = VideoStatus.REGEN_FAILED,
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
            eventPublisher.publishToDeadLetterQueue(event, "Max regeneration attempts reached")
            return
        }

        println("🔄 Regenerating video: ${event.title}")

        try {
            repository.findById(event.videoId).ifPresent { video ->
                repository.save(video.copy(
                    status = VideoStatus.REGENERATING,
                    regenCount = event.regenCount + 1,
                    retryCount = 0,
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }

            val newsItem = NewsItem(
                title = event.title,
                summary = event.summary,
                link = event.link
            )

            val result = productionService.produceVideo(newsItem)
            val newFilePath = result.filePath

            if (newFilePath.isNotBlank()) {
                println("✅ Regeneration successful: $newFilePath")
                
                repository.findById(event.videoId).ifPresent { video ->
                    repository.save(video.copy(
                        status = VideoStatus.COMPLETED,
                        filePath = newFilePath,
                        title = result.title.ifBlank { video.title },
                        description = result.description.ifBlank { video.description },
                        tags = if (result.tags.isNotEmpty()) result.tags else video.tags,
                        sources = if (result.sources.isNotEmpty()) result.sources else video.sources,
                        retryCount = 0,
                        regenCount = event.regenCount + 1,
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                    
                    // 새로운 VideoCreatedEvent 발행 (키워드 포함)
                    eventPublisher.publishVideoCreated(VideoCreatedEvent(
                        videoId = event.videoId,
                        title = event.title,
                        summary = event.summary,
                        link = event.link,
                        filePath = newFilePath,
                        keywords = result.keywords
                    ))
                }
            } else {
                println("❌ Regeneration failed: Empty file path")
                repository.findById(event.videoId).ifPresent { video ->
                    repository.save(video.copy(
                        status = VideoStatus.REGEN_FAILED,
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                }
            }
        } catch (e: Exception) {
            println("❌ Regeneration error: ${e.message}")
            e.printStackTrace()
            repository.findById(event.videoId).ifPresent { video ->
                repository.save(video.copy(
                    status = VideoStatus.REGEN_FAILED,
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
        }
    }
}
