package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.event.*
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.service.JobClaimService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

/**
 * 업로드 재시도 Consumer
 * UploadFailedEvent를 구독하여 재시도 또는 DLQ/재생성 처리
 */
@Service
class UploadRetryConsumer(
    private val repository: VideoHistoryRepository,
    private val eventPublisher: KafkaEventPublisher,
    private val objectMapper: ObjectMapper,
    private val cleanupService: com.sciencepixel.service.CleanupService,
    private val jobClaimService: JobClaimService,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    companion object {
        private const val MAX_RETRY_COUNT = 3
    }

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_UPLOAD_FAILED],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-retry-group}"
    )
    fun handleUploadFailed(message: String) {
        val event = objectMapper.readValue(message, UploadFailedEvent::class.java)
        if (event.channelId != channelId) return
        
        println("📥 [$channelId] Received UploadFailedEvent: ${event.videoId} (Retry: ${event.retryCount})")

        // ⚠️ Quota Exceeded Check - Keep as COMPLETED for scheduler to retry later
        if (event.reason.lowercase().contains("quota") || event.reason.contains("403")) {
            println("🛑 YouTube quota exceeded for video: ${event.videoId}. Status remains COMPLETED for later retry.")
            
            repository.findById(event.videoId).ifPresent { video ->
                if (video.status == VideoStatus.UPLOADED) {
                    println("⏭️ Video ${event.videoId} already marked as UPLOADED. Ignoring quota failure update.")
                    return@ifPresent
                }
                
                // We keep it as COMPLETED so the YoutubeUploadScheduler picks it up later
                repository.save(video.copy(
                    status = VideoStatus.COMPLETED,
                    retryCount = event.retryCount,
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
            return 
        }

        // Fix: Use DB retryCount as source of truth to avoid infinite loop
        repository.findById(event.videoId).ifPresent { video ->
            val currentRetryCount = video.retryCount
            
            if (currentRetryCount < MAX_RETRY_COUNT) {
                 // 재시도: VideoCreatedEvent를 다시 발행
                println("🔄 Retrying upload (${currentRetryCount + 1}/$MAX_RETRY_COUNT)")
                
                repository.save(video.copy(
                    status = VideoStatus.COMPLETED,
                    retryCount = currentRetryCount + 1,
                    updatedAt = java.time.LocalDateTime.now()
                ))
                
                // 다시 VideoCreatedEvent 발행
                eventPublisher.publishVideoCreated(VideoCreatedEvent(
                    channelId = channelId, // 추가
                    videoId = event.videoId,
                    title = event.title,
                    summary = video.summary,
                    description = video.description,
                    link = video.link,
                    filePath = event.filePath,
                    keywords = event.keywords,
                    thumbnailPath = video.thumbnailPath
                ))
            } else {
                // 최대 재시도 횟수 초과
                println("🚫 Max retries exceeded for ${event.videoId} (Count: $currentRetryCount)")
                
                val file = java.io.File(video.filePath)
                
                if (file.exists() && file.length() > 0) {
                    println("🚩 File already exists. Status marked as COMPLETED (for manual retry).")
                    repository.save(video.copy(
                        status = VideoStatus.COMPLETED,
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                    eventPublisher.publishToDeadLetterQueue(event, "Max retries exceeded with existing file")
                } else if (video.regenCount < 1) {
                    // 파일이 없거나 빈 파일인 경우에만 재생성 시도
                    println("🔄 [$channelId] File missing or empty. Requesting regeneration for ${video.title}...")
                    eventPublisher.publishRegenerationRequested(RegenerationRequestedEvent(
                        channelId = channelId, // 추가
                        videoId = event.videoId,
                        title = video.title,
                        summary = video.summary,
                        link = video.link,
                        regenCount = video.regenCount
                    ))
                    
                    repository.save(video.copy(
                        status = VideoStatus.FAILED, 
                        failureStep = "UPLOAD",
                        errorMessage = "File missing or empty after upload attempts: ${event.reason}",
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                } else {
                    // 재생성도 이미 시도한 경우 -> 상태 FAILED로 유지
                    println("💀 Regeneration already attempted. Marking as FAILED.")
                    repository.save(video.copy(
                        status = VideoStatus.FAILED, 
                        failureStep = "UPLOAD",
                        errorMessage = "Max retries and regeneration failed: ${event.reason}",
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                    eventPublisher.publishToDeadLetterQueue(event, "Max retries and regeneration failed")
                }
            }
        }
    }

}
