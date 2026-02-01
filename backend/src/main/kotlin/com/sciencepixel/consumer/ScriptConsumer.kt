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
class ScriptConsumer(
    private val geminiService: GeminiService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val logPublisher: LogPublisher,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_RSS_NEW_ITEM],
        groupId = KafkaConfig.GROUP_MAIN
    )
    fun consumeRssItem(message: String) {
        try {
            val event = objectMapper.readValue(message, RssNewItemEvent::class.java)
            logPublisher.info("shorts-controller", "Process Started: ${event.title}", "URL: ${event.url}")
            println("▶️ [ScriptConsumer] Received RSS item: ${event.title}")

            // 1. Create or Get History (Idempotency)
            val history = getOrCreateHistory(event)
            
            // 전역 차단 상태 확인 (업로드 차단 상태이면 아예 생성을 안 하는 것이 토큰 절약에 유리할 수 있음)
            // 여기서는 일단 기존 로직대로 진행하되, 중복 호출 체크만 강화함
            
            // 이미 완료되었거나 업로드된 경우 건너뜀
            if (history.status == VideoStatus.COMPLETED || history.status == VideoStatus.UPLOADED) {
                println("⚠️ Video already completed/uploaded for: ${event.title}. Skipping Gemini call.")
                return
            }

            // 이미 파이프라인 진행 중인 경우 건너뜀 (대기 중, 스크립트 생성됨, 렌더링 중, 업로드 대기 중 등)
            val inProgressStatuses = listOf(
                VideoStatus.SCRIPT_READY,
                VideoStatus.PROCESSING_ASSETS,
                VideoStatus.RENDERING,
                VideoStatus.RETRY_PENDING,
                VideoStatus.QUOTA_EXCEEDED,
                VideoStatus.REGENERATING
            )
            if (history.status in inProgressStatuses) {
                println("⏭️ Video already in pipeline (Status: ${history.status}) for: ${event.title}. Skipping Gemini call to save tokens.")
                return
            }

            // 2. Call Gemini
            println("🤖 generating script for: ${event.title}...")
            val scriptResponse = geminiService.writeScript(event.title, event.title) // RSS summary might be null, use title or fetch content if needed

            if (scriptResponse.scenes.isEmpty()) {
                println("⚠️ Empty script generated. Marking as ERROR.")
                videoHistoryRepository.save(history.copy(
                    status = VideoStatus.ERROR_SCRIPT_EMPTY,
                    updatedAt = LocalDateTime.now()
                ))
                return
            }

            // 3. Update History with Script Data
            val updatedHistory = videoHistoryRepository.save(history.copy(
                status = VideoStatus.SCRIPT_READY,
                title = scriptResponse.title, // Update title with the generated Korean title
                description = scriptResponse.description, // Correctly use description field
                tags = scriptResponse.tags,
                sources = scriptResponse.sources,
                updatedAt = LocalDateTime.now()
            ))

            // 4. Publish next event
            eventPublisher.publishScriptCreated(ScriptCreatedEvent(
                videoId = updatedHistory.id!!,
                title = scriptResponse.title,
                script = objectMapper.writeValueAsString(scriptResponse.scenes), // Serialize scenes to string to pass along
                summary = scriptResponse.description,
                sourceLink = event.url,
                keywords = scriptResponse.tags
            ))

            logPublisher.info("shorts-controller", "Script Generated: ${scriptResponse.title}", "Scenes: ${scriptResponse.scenes.size}ea", traceId = updatedHistory.id)
            println("✅ [ScriptConsumer] Script created & event published: ${event.title}")

        } catch (e: Exception) {
            logPublisher.error("shorts-controller", "Script Generation Failed", "Error: ${e.message}")
            println("❌ [ScriptConsumer] Error: ${e.message}")
            e.printStackTrace()
            // Optional: Publish to DLQ
        }
    }

    private fun getOrCreateHistory(event: RssNewItemEvent): VideoHistory {
        // Simple check by link (assuming unique per news)
        val existing = videoHistoryRepository.findByLink(event.url)
        if (existing != null) return existing

        val initialVideo = VideoHistory(
            id = UUID.randomUUID().toString(),
            title = event.title,
            summary = "", // Initial summary
            link = event.url,
            status = VideoStatus.QUEUED,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        return try {
            videoHistoryRepository.save(initialVideo)
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            println("⚠️ Race condition detected for link: ${event.url}. Returning existing record.")
            videoHistoryRepository.findByLink(event.url) ?: throw IllegalStateException("Record should exist but not found: ${event.url}")
        } catch (e: Exception) {
             // Fallback for other potential race conditions or DB errors
             val checkAgain = videoHistoryRepository.findByLink(event.url)
             if (checkAgain != null) return checkAgain
             throw e
        }
    }
}
