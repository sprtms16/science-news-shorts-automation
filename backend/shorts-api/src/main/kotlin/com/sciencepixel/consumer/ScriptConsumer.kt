package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.RssNewItemEvent
import com.sciencepixel.event.ScriptCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.GeminiService
import com.sciencepixel.service.JobClaimService
import com.sciencepixel.service.LogPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.*

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = ["app.feature.consumer-script"],
    havingValue = "true",
    matchIfMissing = true
)
class ScriptConsumer(
    private val geminiService: GeminiService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val logPublisher: LogPublisher,
    private val objectMapper: ObjectMapper,
    private val jobClaimService: JobClaimService,
    private val channelBehavior: com.sciencepixel.config.ChannelBehavior,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_RSS_NEW_ITEM],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-group}"
    )
    fun consumeRssItem(message: String) {
        try {
            val event = objectMapper.readValue(message, RssNewItemEvent::class.java)
            
            // Channel Filter
            // If this instance is the dedicated 'renderer', it processes ALL channels.
            if (channelId != "renderer" && event.channelId != channelId) {
                return
            }

            // 1. Create or Get History (Idempotency)
            val history = getOrCreateHistory(event)
            
            // 전역 차단 상태 확인 (업로드 차단 상태이면 아예 생성을 안 하는 것이 토큰 절약에 유리할 수 있음)
            // 여기서는 일단 기존 로직대로 진행하되, 중복 호출 체크만 강화함
            
            // 이미 완료되었거나 업로드된 경우 건너뜀
            if (history.status == VideoStatus.COMPLETED || history.status == VideoStatus.UPLOADED) {
                println("⚠️ Video already completed/uploaded for: ${event.title}. Skipping Gemini call.")
                return
            }

            // 이미 파이프라인 진행 중인 경우 건너뜀
            // 1.5 Safety Check & Claim (Locking)
            
            // Daily Limit Check for 'stocks' and 'history'
            // One successful video per day guarantee (Rate Limit)
            
            // Bypass limit if this is a Manual Retry or Auto-Recovery (regenCount > 0 or errorMessage contains "Manual")
            val isManualRetry = (history.errorMessage?.contains("Manual", ignoreCase = true) == true) || ((history.regenCount ?: 0) > 0)

            // Daily Limit Check using ChannelBehavior
            if (!isManualRetry && channelBehavior.dailyLimit == 1) {
                val startOfDay = java.time.LocalDate.now().atStartOfDay()
                val successStatuses = listOf(VideoStatus.SCRIPTING, VideoStatus.ASSETS_QUEUED, VideoStatus.RENDER_QUEUED, VideoStatus.RENDERING, VideoStatus.COMPLETED, VideoStatus.UPLOADING, VideoStatus.UPLOADED)
                val dailyCount = videoHistoryRepository.countByChannelIdAndStatusInAndCreatedAtAfter(channelId, successStatuses, startOfDay)
                
                if (dailyCount >= channelBehavior.dailyLimit) {
                    println("🛑 [$channelId] Daily limit reached (Count: $dailyCount). Skipping execution for today.")
                    return
                }
            }

            // 오직 QUEUED 상태인 경우에만 작업을 시작 - 원자적 상태 전환으로 중복 방지
            if (history.status != VideoStatus.QUEUED) {
                 println("⏭️ Skipping: Video is not in QUEUED state (${history.status}) for: ${event.title}")
                 return
            }
            
            // 원자적 Claim (MongoDB findAndModify) - 중복 실행 방지
            // QUEUED -> SCRIPTING (Start Scripting)
            if (!jobClaimService.claimJob(history.id!!, VideoStatus.QUEUED, VideoStatus.SCRIPTING)) {
                println("⏭️ Job already claimed by another instance: ${event.title}")
                return
            }

            // 2. Call Gemini
            println("🤖 generating script for: ${event.title}...")
            val content = event.summary ?: event.title
            val scriptResponse = geminiService.writeScript(event.title, content)

            if (scriptResponse.scenes.isEmpty()) {
                println("⚠️ Empty script generated. Marking as FAILED.")
                videoHistoryRepository.save(history.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "SCRIPT",
                    errorMessage = "Empty script generated by Gemini",
                    updatedAt = LocalDateTime.now()
                ))
                return
            }

            // 3. Update History with Script Data (Next: ASSETS_QUEUED)
            // Script is done, now we wait for Assets
            val updatedHistory = videoHistoryRepository.save(history.copy(
                status = VideoStatus.ASSETS_QUEUED,
                title = scriptResponse.title,
                description = scriptResponse.description,
                tags = scriptResponse.tags,
                sources = scriptResponse.sources,
                scenes = scriptResponse.scenes, // Persist script
                currentStep = "대본 생성 완료, 에셋 대기 중",
                updatedAt = LocalDateTime.now()
            ))

            // 4. Publish next event
            eventPublisher.publishScriptCreated(ScriptCreatedEvent(
                channelId = channelId, // 추가
                videoId = updatedHistory.id!!,
                title = scriptResponse.title,
                script = objectMapper.writeValueAsString(scriptResponse.scenes),
                summary = scriptResponse.description,
                sourceLink = event.url,
                mood = scriptResponse.mood,
                keywords = scriptResponse.tags
            ))

            logPublisher.info("shorts-controller", "Script Generated: ${scriptResponse.title}", "Scenes: ${scriptResponse.scenes.size}ea", traceId = updatedHistory.id)
            println("✅ [$channelId] Script created & event published: ${event.title}")

        } catch (e: Exception) {
            val isSafety = e.message?.contains("GEMINI_SAFETY_BLOCKED") == true
            
            logPublisher.error("shorts-controller", if (isSafety) "Safety Blocked" else "Script Generation Failed", "Error: ${e.message}")
            println("❌ [$channelId] Error: ${e.message}")
            
            // Mark as FAILED in DB
            val event = objectMapper.readValue(message, RssNewItemEvent::class.java)
            videoHistoryRepository.findByChannelIdAndLink(channelId, event.url)?.let { 
                videoHistoryRepository.save(it.copy(
                    status = VideoStatus.FAILED, 
                    failureStep = if (isSafety) "SAFETY" else "SCRIPT",
                    errorMessage = e.message ?: "Unknown Script Generation Error",
                    updatedAt = LocalDateTime.now()
                ))
            }
        }
    }

    private fun getOrCreateHistory(event: RssNewItemEvent): VideoHistory {
        // Simple check by link (assuming unique per news per channel)
        val existing = videoHistoryRepository.findByChannelIdAndLink(channelId, event.url)
        if (existing != null) return existing

        val initialVideo = VideoHistory(
            id = UUID.randomUUID().toString(),
            channelId = channelId, // 추가
            title = event.title,
            summary = "", 
            link = event.url,
            status = VideoStatus.QUEUED,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        return try {
            videoHistoryRepository.save(initialVideo)
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            println("⚠️ Race condition detected for link: ${event.url} in channel $channelId. Returning existing record.")
            videoHistoryRepository.findByChannelIdAndLink(channelId, event.url) ?: throw IllegalStateException("Record should exist but not found: ${event.url}")
        } catch (e: Exception) {
             val checkAgain = videoHistoryRepository.findByChannelIdAndLink(channelId, event.url)
             if (checkAgain != null) return checkAgain
             throw e
        }
    }
}
