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
import org.springframework.kafka.annotation.KafkaListener
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
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_SCRIPT_CREATED],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-group}"
    )
    fun consumeScript(message: String) {
        try {
            val event = objectMapper.readValue(message, ScriptCreatedEvent::class.java)
            if (channelId != "renderer" && event.channelId != channelId) return
            
            println("▶️ [$channelId] Received Script event: ${event.title}")

            // Deserialize script (List<Scene>)
            val scenes: List<Scene> = objectMapper.readValue(event.script)

            // Update Status + Progress (10%: 시작)
            val history = videoHistoryRepository.findById(event.videoId).orElse(null)
            if (history != null) {
                videoHistoryRepository.save(history.copy(
                    status = VideoStatus.ASSETS_GENERATING,
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
                silenceTime = assetResult.silenceTime,
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
}
