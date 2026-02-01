package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.event.*
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.domain.VideoStatus
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
    private val cleanupService: com.sciencepixel.service.CleanupService
) {

    companion object {
        private const val MAX_RETRY_COUNT = 3
    }

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_UPLOAD_FAILED],
        groupId = KafkaConfig.GROUP_RETRY
    )
    fun handleUploadFailed(message: String) {
        val event = objectMapper.readValue(message, UploadFailedEvent::class.java)
        println("📥 Received UploadFailedEvent: ${event.videoId} (Retry: ${event.retryCount})")

        // ⚠️ Quota Exceeded Check - Mark for later retry
        if (event.reason.lowercase().contains("quota") || event.reason.contains("403")) {
            println("🛑 YouTube quota exceeded for video: ${event.videoId}. Status marked as QUOTA_EXCEEDED for later retry.")
            
            repository.findById(event.videoId).ifPresent { video ->
                if (video.status == VideoStatus.UPLOADED) {
                    println("⏭️ Video ${event.videoId} already marked as UPLOADED. Ignoring quota failure update.")
                    return@ifPresent
                }
                
                repository.save(video.copy(
                    status = VideoStatus.QUOTA_EXCEEDED,
                    retryCount = event.retryCount,
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
            return 
        }

        if (event.retryCount < MAX_RETRY_COUNT) {
            // 재시도: VideoCreatedEvent를 다시 발행
            println("🔄 Retrying upload (${event.retryCount + 1}/$MAX_RETRY_COUNT)")
            
            repository.findById(event.videoId).ifPresent { video ->
                repository.save(video.copy(
                    status = VideoStatus.RETRY_PENDING,
                    retryCount = event.retryCount + 1,
                    updatedAt = java.time.LocalDateTime.now()
                ))
                
                // 다시 VideoCreatedEvent 발행 (retryCount 증가, 키워드 유지)
                eventPublisher.publishVideoCreated(VideoCreatedEvent(
                    videoId = event.videoId,
                    title = event.title,
                    summary = video.summary,
                    description = video.description,
                    link = video.link,
                    filePath = event.filePath,
                    keywords = event.keywords
                ))
            }
        } else {
            // 최대 재시도 횟수 초과
            println("🚫 Max retries exceeded for ${event.videoId}")
            
            repository.findById(event.videoId).ifPresent { video ->
                val file = java.io.File(video.filePath)
                
                if (file.exists() && file.length() > 0) {
                    // 파일이 이미 존재하면 다시 생성할 필요가 없음 (AI 토큰 절약)
                    println("🚩 File already exists. Skipping regeneration to save tokens. Status marked as PERMANENTLY_FAILED.")
                    repository.save(video.copy(
                        status = VideoStatus.PERMANENTLY_FAILED,
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                    eventPublisher.publishToDeadLetterQueue(event, "Max retries exceeded with existing file")
                } else if (video.regenCount < 1) {
                    // 파일이 없거나 빈 파일인 경우에만 재생성 시도
                    println("🔄 File missing or empty. Requesting regeneration for ${video.title}...")
                    eventPublisher.publishRegenerationRequested(RegenerationRequestedEvent(
                        videoId = event.videoId,
                        title = video.title,
                        summary = video.summary,
                        link = video.link,
                        regenCount = video.regenCount
                    ))
                } else {
                    // 재생성도 이미 시도한 경우 -> 파일 및 DB 레코드 삭제
                    println("💀 Regeneration already attempted. Deleting video record and file.")
                    cleanupService.deleteVideoFile(video.filePath) 
                    repository.delete(video) // Delete from DB
                    eventPublisher.publishToDeadLetterQueue(event, "Max retries and regeneration failed (Record Deleted)")
                }
            }
        }
    }

}
