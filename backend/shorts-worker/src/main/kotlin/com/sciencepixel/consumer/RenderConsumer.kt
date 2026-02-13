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
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.retry.annotation.Backoff
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
    private val jobClaimService: com.sciencepixel.service.JobClaimService, // Inject Service
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    @RetryableTopic(
        attempts = "3",
        backoff = Backoff(delay = 60000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        include = [Exception::class]
    )
    @KafkaListener(
        topics = [KafkaConfig.TOPIC_ASSETS_READY],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-group}"
    )
    fun consumeAssets(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
        try {
            val event = objectMapper.readValue(message, VideoAssetsReadyEvent::class.java)
            if (channelId != "renderer" && event.channelId != channelId) return
            
            println("▶️ [$channelId] Received Assets Ready event: ${event.videoId}")

            // Atomic Claim: RENDER_QUEUED -> RENDERING
            // Note: Since we don't have event.status, we rely on DB Current Status (RENDER_QUEUED)
            // But sometimes the status might be ASSETS_QUEUED if skipped scene generation? No.
            // Let's use RENDER_QUEUED. If it fails, maybe it's already completed.
            
            val history = videoHistoryRepository.findById(event.videoId).orElse(null)
            if (history != null) {
                if (history.status == VideoStatus.COMPLETED || history.status == VideoStatus.UPLOADED) {
                    println("⏭️ Video already rendered: ${event.title}. Skipping.")
                    return
                }

                if (!jobClaimService.claimJob(event.videoId, VideoStatus.RENDER_QUEUED, VideoStatus.RENDERING)) {
                     println("⏭️ Job already claimed or not in RENDER_QUEUED state: ${event.title}")
                     return
                }

                videoHistoryRepository.save(history.copy(
                    // Status already updated by claimJob
                    progress = 70,
                    currentStep = "영상 렌더링 중 (Status: RENDERING)",
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
                silenceRanges = event.silenceRanges,
                reportImagePath = event.reportImagePath,
                targetChannelId = event.channelId // 정확한 채널 ID 전달
            )

            if (finalPath.isEmpty()) {
                println("❌ Rendering failed (empty path or 0-byte file).")
                history?.let {
                    videoHistoryRepository.save(it.copy(
                        status = VideoStatus.FAILED,
                        failureStep = "RENDER",
                        progress = 0,
                        currentStep = "렌더링 실패 (0바이트 또는 파일 생성 실패)",
                        errorMessage = "Rendering produced empty file path or 0-byte output",
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

            println("✅ [$channelId] Video Finalized & Ready for Scheduler: $finalPath")

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

    @DltHandler
    fun handleDlt(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
        println("💀 [RenderConsumer] Message moved to DLT topic $topic: $message")
        try {
            val event = objectMapper.readValue(message, VideoAssetsReadyEvent::class.java)
            videoHistoryRepository.findById(event.videoId).ifPresent { v ->
                videoHistoryRepository.save(v.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "RENDER_DLT",
                    errorMessage = "Failed after worker retries in RenderConsumer",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
        } catch (e: Exception) {
            println("❌ Error processing DLT message: ${e.message}")
        }
    }
}
