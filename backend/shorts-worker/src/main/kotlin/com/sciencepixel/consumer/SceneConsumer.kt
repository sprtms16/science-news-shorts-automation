package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.Scene
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.ScriptCreatedEvent
import com.sciencepixel.event.VideoAssetsReadyEvent

import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.ProductionService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
    name = ["app.feature.consumer-scene"],
    havingValue = "true",
    matchIfMissing = true
)
class SceneConsumer(
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val objectMapper: ObjectMapper,
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
        topics = [KafkaConfig.TOPIC_SCRIPT_CREATED],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-group}"
    )
    fun consumeScript(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
        try {
            val event = objectMapper.readValue(message, ScriptCreatedEvent::class.java)
            if (channelId != "renderer" && event.channelId != channelId) return
            
            println("▶️ [$channelId] Received Script event: ${event.title}")

            // Deserialize script (List<Scene>)
            val scenes: List<Scene> = objectMapper.readValue(event.script)

            // Update Status + Progress (10%: 시작)
            // Atomic Claim: ASSETS_QUEUED -> ASSETS_GENERATING
            if (!jobClaimService.claimJob(event.videoId, VideoStatus.ASSETS_QUEUED, VideoStatus.ASSETS_GENERATING)) {
                 println("⏭️ Job already claimed or not in ASSETS_QUEUED state: ${event.title}")
                 return
            }

            val history = videoHistoryRepository.findById(event.videoId).orElse(null)
            if (history != null) {
                videoHistoryRepository.save(history.copy(
                    progress = 10,
                    currentStep = "에셋 생성 시작",
                    updatedAt = java.time.LocalDateTime.now()
                ))
                println("📊 [${event.title}] 진행률: 10% - 에셋 생성 시작 (Status: ASSETS_GENERATING)")
            }

            // Call Production Service to generate assets (Clips) with progress callback
            val assetResult = productionService.produceAssetsOnly(
                title = event.title, 
                scenes = scenes, 
                videoId = event.videoId, 
                mood = event.mood,
                reportImagePath = event.reportImagePath,
                targetChannelId = event.channelId, // 정확한 채널 ID 전달
                onProgress = { progress, step ->
                    videoHistoryRepository.findById(event.videoId).ifPresent { v ->
                        videoHistoryRepository.save(v.copy(
                            progress = progress,
                            currentStep = step,
                            updatedAt = java.time.LocalDateTime.now()
                        ))
                    }
                }
            )

            if (assetResult.clipPaths.isEmpty()) {
                println("❌ Assets generation failed (empty clips).")
                history?.let {
                    videoHistoryRepository.save(it.copy(
                        status = VideoStatus.FAILED,
                        failureStep = "ASSETS",
                        errorMessage = "Empty asset clips generated",
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                }
                return
            }

            // Progress update (60%: 에셋 생성 완료 -> RENDER_QUEUED)
            videoHistoryRepository.findById(event.videoId).ifPresent { v ->
                videoHistoryRepository.save(v.copy(
                    status = VideoStatus.RENDER_QUEUED,
                    progress = 60,
                    currentStep = "에셋 생성 완료, 렌더링 대기",
                    updatedAt = java.time.LocalDateTime.now()
                ))
                println("⏳ [${event.title}] 에셋 완료. Status -> RENDER_QUEUED. Publishing event...")
            }

            // Publish Next Event
            eventPublisher.publishVideoAssetsReady(VideoAssetsReadyEvent(
                channelId = event.channelId, // 로컬 "renderer"가 아닌 원본 채널 ID 전달
                videoId = event.videoId,
                title = event.title,
                clipPaths = assetResult.clipPaths,
                durations = assetResult.durations,
                subtitles = assetResult.subtitles,
                keywords = event.keywords,
                mood = event.mood,
                reportImagePath = event.reportImagePath,
                silenceRanges = assetResult.silenceRanges,
                scriptEvent = event
            ))

            println("✅ [$channelId] Assets ready & event published: ${event.videoId}")

        } catch (e: Exception) {
            println("❌ [SceneConsumer] Error: ${e.message}")
            e.printStackTrace()
            // Try to mark as FAILED
            val event = try { objectMapper.readValue(message, ScriptCreatedEvent::class.java) } catch(ex: Exception) { null }
            event?.let { 
                videoHistoryRepository.findById(it.videoId).ifPresent { v ->
                    videoHistoryRepository.save(v.copy(
                        status = VideoStatus.FAILED, 
                        failureStep = "ASSETS",
                        errorMessage = e.message ?: "Unknown Asset Generation Error",
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                }
            }
        }
    }

    @DltHandler
    fun handleDlt(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
        println("💀 [SceneConsumer] Message moved to DLT topic $topic: $message")
        try {
            val event = objectMapper.readValue(message, ScriptCreatedEvent::class.java)
            videoHistoryRepository.findById(event.videoId).ifPresent { v ->
                videoHistoryRepository.save(v.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "ASSETS_DLT",
                    errorMessage = "Failed after worker retries in SceneConsumer",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
        } catch (e: Exception) {
            println("❌ Error processing DLT message: ${e.message}")
        }
    }
}
