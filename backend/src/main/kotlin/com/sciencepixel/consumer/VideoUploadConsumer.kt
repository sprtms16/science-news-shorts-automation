package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.*
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.YoutubeService
import com.sciencepixel.service.NotificationService
import com.sciencepixel.service.LogPublisher
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import java.io.File

/**
 * 비디오 업로드 Consumer
 * VideoCreatedEvent를 구독하여 YouTube 업로드 수행
 */
@Service
class VideoUploadConsumer(
    private val repository: VideoHistoryRepository,
    private val youtubeService: YoutubeService,
    private val eventPublisher: KafkaEventPublisher,
    private val notificationService: NotificationService,
    private val logPublisher: LogPublisher,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val MAX_RETRY_COUNT = 3
    }

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_VIDEO_CREATED],
        groupId = KafkaConfig.GROUP_UPLOAD
    )
    fun handleVideoCreated(message: String) {
        val event = objectMapper.readValue(message, VideoCreatedEvent::class.java)
        println("📥 Received VideoCreatedEvent: ${event.videoId}")

        try {
            val videoOpt = repository.findById(event.videoId)
            if (videoOpt.isPresent) {
                val video = videoOpt.get()
                // Idempotency check: Already uploaded or currently uploading?
                if (video.status == VideoStatus.UPLOADED && video.youtubeUrl.isNotBlank()) {
                    println("⏭️ Video ${event.videoId} already uploaded to YouTube. Skipping duplicate upload.")
                    return
                }
                
                if (video.status == VideoStatus.UPLOADING) {
                    println("⏳ Video ${event.videoId} is already being uploaded by another process. Skipping.")
                    return
                }
                
                // Status check: Is it ready? (Avoid race with rendering/etc)
                if (video.status != VideoStatus.COMPLETED) {
                    println("⏳ Video ${event.videoId} is in status ${video.status}. Waiting for it to reach COMPLETED state.")
                    return
                }

                // Claim the upload (Set to UPLOADING)
                repository.save(video.copy(
                    status = VideoStatus.UPLOADING,
                    updatedAt = java.time.LocalDateTime.now()
                ))
                println("🔒 Claimed upload (COMPLETED -> UPLOADING): ${event.title}")
            } else {
                println("⚠️ Video record ${event.videoId} not found in DB. Skipping.")
                return
            }

            val file = File(event.filePath)
            
            if (file.exists()) {
                // 검증 로직: 업로드 전 데이터 무결성 체크
                println("🔍 Verifying Upload Data for: ${event.title}")
                
                // 1. 영상 파일 크기 체크 (1MB 이하 경고)
                if (file.length() < 1024 * 1024) {
                     println("⚠️ Warning: Video file size is startlingly small (${file.length()} bytes). Verify content.")
                }
                
                // 2. 제목 한글 포함 여부 체크 (한국어 채널)
                // 2. 제목 한글 포함 여부 체크 (한국어 채널)
                val hasKorean = event.title.any { it in '\uAC00'..'\uD7A3' }
                if (!hasKorean) {
                    println("⛔ Upload BLOCKED: Title contains no Korean characters. (${event.title})")
                    
                    // FAILED 상태와 함께 validationErrors 저장
                    repository.findById(event.videoId).ifPresent { video ->
                        repository.save(video.copy(
                            status = VideoStatus.FAILED,
                            failureStep = "VALIDATION",
                            errorMessage = "Validation Failed: Title is English",
                            validationErrors = listOf("TITLE_ENGLISH"),
                            updatedAt = java.time.LocalDateTime.now()
                        ))
                    }
                    return // 업로드 중단
                }

                // 3. 태그 검증
                val defaultTags = listOf("Science", "News", "Shorts", "SciencePixel")
                val combinedTags = (defaultTags + event.keywords)
                    .map { it.trim().take(30) }
                    .distinct()
                    .filter { it.isNotBlank() && it.length > 1 } // 한 글자 태그 제외
                    .take(20)

                println("✅ Verification Passed. Meta: Title='${event.title}' (${if(hasKorean) "KR" else "NON-KR"}), Tags=${combinedTags.size}ea")

                
                val baseDescription = if (event.description.isNotBlank()) event.description else event.summary
                
                // Only append default hashtags if none are present in the base description
                val finalDescription = if (baseDescription.contains("#")) {
                    baseDescription
                } else {
                    "${baseDescription}\n\n#Science #News #Shorts"
                }

                val thumbnailFile = if (event.thumbnailPath.isNotBlank()) {
                    File(event.thumbnailPath)
                } else null

                val youtubeUrl = youtubeService.uploadVideo(
                    file,
                    event.title,
                    finalDescription,
                    combinedTags,
                    thumbnailFile
                )

                // Update DB
                repository.findById(event.videoId).ifPresent { video ->
                    repository.save(video.copy(
                        status = VideoStatus.UPLOADED,
                        youtubeUrl = youtubeUrl,
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                }

                // Publish success event
                eventPublisher.publishVideoUploaded(VideoUploadedEvent(
                    videoId = event.videoId,
                    youtubeUrl = youtubeUrl
                ))

                // Discord 알림 전송 (업로드 정보 최우선)
                notificationService.notifyUploadComplete(event.title, youtubeUrl)

                logPublisher.info("shorts-controller", "YouTube Upload Success: ${event.title}", "URL: $youtubeUrl", traceId = event.videoId)
                println("✅ Upload Success via Kafka: $youtubeUrl")
            } else {
                println("⚠️ File not found: ${event.filePath}")
                // Publish failure event for retry
                eventPublisher.publishUploadFailed(UploadFailedEvent(
                    videoId = event.videoId,
                    title = event.title,
                    filePath = event.filePath,
                    reason = "File not found",
                    retryCount = 0,
                    thumbnailPath = event.thumbnailPath
                ))
            }
        } catch (e: Exception) {
            logPublisher.error("shorts-controller", "YouTube Upload Failed: ${event.title}", "Error: ${e.message}", traceId = event.videoId)
            
            eventPublisher.publishUploadFailed(UploadFailedEvent(
                videoId = event.videoId,
                title = event.title,
                filePath = event.filePath,
                reason = e.message ?: "Unknown error",
                retryCount = 0,
                thumbnailPath = event.thumbnailPath
            ))
        }
    }
}
