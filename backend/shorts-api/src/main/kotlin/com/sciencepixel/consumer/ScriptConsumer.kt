package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.RssNewItemEvent
import com.sciencepixel.event.ScriptCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.GeminiService
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
            if (channelId == "stocks" || channelId == "history") {
                val startOfDay = java.time.LocalDate.now().atStartOfDay()
                val successStatuses = listOf(VideoStatus.CREATING, VideoStatus.COMPLETED, VideoStatus.UPLOADING, VideoStatus.UPLOADED)
                val dailyCount = videoHistoryRepository.countByChannelIdAndStatusInAndCreatedAtAfter(channelId, successStatuses, startOfDay)
                
                if (dailyCount >= 1) {
                    println("🛑 [$channelId] Daily limit reached (Count: $dailyCount). Skipping execution for today.")
                    return
                }
            }

            // 오직 QUEUED 상태인 경우에만 작업을 시작하고 CREATING으로 상태를 변경하여 선점함
            if (history.status != VideoStatus.QUEUED && history.status != VideoStatus.CREATING) {
                 println("⏭️ Skipping: Video is in terminal state (${history.status}) for: ${event.title}")
                 return
            }
            
            // 이미 CREATING이면 누군가 처리 중이므로 스킵 (단, 아주 오래된 건 데드락일 수 있으나 여기서는 안전하게 스킵)
            // 예외: 최초 생성 시 getOrCreateHistory가 CREATING으로 만들었을 수 있으므로 이 로직은 QUEUED 도입 후 더욱 명확해짐
            if (history.status == VideoStatus.CREATING) {
                 // But wait, if we handle manual requests, they start as CREATING.
                 // So we only skip if it seems 'active' (e.g. updated recently). 
                 // However, with QUEUED introduced, we can strictly say: 
                 // Batch jobs start as QUEUED. Manual jobs start as CREATING.
                 // If it's QUEUED, we execute. If it's CREATING, we assume it's running OR it's a manual sync job that doesn't use this consumer.
                 // But wait, Manual Async also goes here? No, Manual Async calls asyncVideoService directly.
                 // So this Consumer is mostly for RSS Batch.
                 
                 // Let's implement Strict Claim for QUEUED items.
                 // If it is CREATING, we double check if it's stale? 
                 // For safety, let's process ONLY QUEUED items or items that just got created (if manual).
                 // But effectively, if we use QUEUED, we should look for QUEUED.
                 if (history.updatedAt.isAfter(LocalDateTime.now().minusMinutes(10))) {
                     println("⏭️ Video already in pipeline (Status: CREATING) for: ${event.title}. Skipping.")
                     return
                 }
            }

            // Claim the job (Set to CREATING)
            val processingHistory = videoHistoryRepository.save(history.copy(
                status = VideoStatus.CREATING,
                updatedAt = LocalDateTime.now()
            ))
            println("🔒 Claimed job (QUEUED -> CREATING): ${event.title}")

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

            // 3. Update History with Script Data (Stay in CREATING)
            val updatedHistory = videoHistoryRepository.save(history.copy(
                status = VideoStatus.CREATING,
                title = scriptResponse.title,
                description = scriptResponse.description,
                tags = scriptResponse.tags,
                sources = scriptResponse.sources,
                scenes = scriptResponse.scenes, // Persist script
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
                keywords = scriptResponse.tags
            ))

            logPublisher.info("shorts-controller", "Script Generated: ${scriptResponse.title}", "Scenes: ${scriptResponse.scenes.size}ea", traceId = updatedHistory.id)
            println("✅ [$channelId] Script created & event published: ${event.title}")

        } catch (e: Exception) {
            logPublisher.error("shorts-controller", "Script Generation Failed", "Error: ${e.message}")
            println("❌ [$channelId] Error: ${e.message}")
            e.printStackTrace()
            // Mark as FAILED in DB
            val event = objectMapper.readValue(message, RssNewItemEvent::class.java)
            videoHistoryRepository.findByChannelIdAndLink(channelId, event.url)?.let { 
                videoHistoryRepository.save(it.copy(
                    status = VideoStatus.FAILED, 
                    failureStep = "SCRIPT",
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
