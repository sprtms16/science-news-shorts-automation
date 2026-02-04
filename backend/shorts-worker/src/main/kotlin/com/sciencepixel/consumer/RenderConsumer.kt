package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.VideoAssetsReadyEvent
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.event.ScriptCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.service.ProductionService
import com.sciencepixel.service.NotificationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = ["app.feature.consumer-render"],
    havingValue = "true",
    matchIfMissing = true
)
class RenderConsumer(
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_ASSETS_READY],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-group}"
    )
    fun consumeAssets(message: String) {
        try {
            val event = objectMapper.readValue(message, VideoAssetsReadyEvent::class.java)
            if (channelId != "renderer" && event.channelId != channelId) return
            
            println("▶️ [$channelId] Received Assets Ready event: ${event.videoId}")

            val history = videoHistoryRepository.findById(event.videoId).orElse(null)
            if (history != null) {
                videoHistoryRepository.save(history.copy(
                    status = VideoStatus.CREATING,
                    progress = 70,
                    currentStep = "영상 렌더링 중",
                    updatedAt = java.time.LocalDateTime.now()
                ))
                println("📊 [${event.title}] 진행률: 70% - 영상 렌더링 시작")
            }

            // Call Production Service to finalize video (Merge & Burn)
            val finalPath = productionService.finalizeVideo(
                videoId = event.videoId,
                title = event.title,
                clipPaths = event.clipPaths,
                durations = event.durations,
                subtitles = event.subtitles,
                mood = event.mood,
                silenceTime = event.silenceTime,
                reportImagePath = event.reportImagePath,
                targetChannelId = event.channelId // 정확한 채널 ID 전달
            )

            if (finalPath.isEmpty()) {
                println("❌ Rendering failed (empty path).")
                history?.let {
                    videoHistoryRepository.save(it.copy(
                        status = VideoStatus.FAILED,
                        failureStep = "RENDER",
                        progress = 0,
                        currentStep = "렌더링 실패",
                        errorMessage = "Rendering produced empty file path",
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                }
                return
            }

            // Update History to COMPLETED (Ready for Upload) - 100%
            val completedHistory = if (history != null) {
                videoHistoryRepository.save(history.copy(
                    status = VideoStatus.COMPLETED,
                    filePath = finalPath,
                    progress = 100,
                    currentStep = "렌더링 완료",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            } else null

            println("📊 [${event.title}] 진행률: 100% - 렌더링 완료")

            // 🔔 Discord 알림: 영상 생성 완료
            notificationService.notifyVideoCreated(
                title = event.title,
                filePath = finalPath
            )

            // Publish 'video.created' -> This triggers the existing VideoUploadConsumer!
            // We bridge the new SAGA pipeline to the existing Upload pipeline here.
            eventPublisher.publishVideoCreated(VideoCreatedEvent(
                channelId = event.channelId, // 로컬 "renderer"가 아닌 원본 채널 ID 전달
                videoId = event.videoId,
                title = event.title,
                summary = history?.summary ?: "",
                description = event.scriptEvent?.summary ?: "", // ScriptCreatedEvent.summary is description
                link = event.scriptEvent?.sourceLink ?: "",
                filePath = finalPath,
                keywords = event.keywords
            ))

            println("✅ [$channelId] Video Finalized & Upload Event Published: $finalPath")

        } catch (e: Exception) {
            println("❌ [RenderConsumer] Error: ${e.message}")
            e.printStackTrace()
             // Try to mark as FAILED
            val event = try { objectMapper.readValue(message, VideoAssetsReadyEvent::class.java) } catch(ex: Exception) { null }
            event?.let { 
                videoHistoryRepository.findById(it.videoId).ifPresent { v ->
                    videoHistoryRepository.save(v.copy(
                        status = VideoStatus.FAILED, 
                        failureStep = "RENDER",
                        errorMessage = e.message ?: "Unknown Rendering Error",
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                }
            }
        }
    }
}
