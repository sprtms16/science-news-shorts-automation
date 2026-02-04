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
    private val objectMapper: ObjectMapper,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    companion object {
        private const val MAX_RETRY_COUNT = 3
    }

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_UPLOAD_REQUESTED],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-upload-group}"
    )
    fun handleUploadRequested(message: String) {
        val event = objectMapper.readValue(message, UploadRequestedEvent::class.java)
        if (event.channelId != channelId) return
        
        println("📥 [$channelId] Received UploadRequestedEvent: ${event.videoId}")

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
                println("🔒 Claimed upload (COMPLETED -> UPLOADING): ${video.title}")

                val file = File(video.filePath)
                
                if (file.exists()) {
                    // 검증 로직: 업로드 전 데이터 무결성 체크
                    println("🔍 Verifying Upload Data for: ${video.title}")
                    
                    // 1. 영상 파일 크기 체크 (1MB 이하 경고)
                    if (file.length() < 1024 * 1024) {
                         println("⚠️ Warning: Video file size is startlingly small (${file.length()} bytes). Verify content.")
                    }
                    
                    // 2. 제목 한글 포함 여부 체크 (한국어 채널)
                    val hasKorean = video.title.any { it in '\uAC00'..'\uD7A3' }
                    if (!hasKorean) {
                        println("⛔ Upload BLOCKED: Title contains no Korean characters. (${video.title})")
                        
                        repository.save(video.copy(
                            status = VideoStatus.FAILED,
                            failureStep = "VALIDATION",
                            errorMessage = "Validation Failed: Title is English",
                            validationErrors = listOf("TITLE_ENGLISH"),
                            updatedAt = java.time.LocalDateTime.now()
                        ))
                        return
                    }

                    // 3. 태그 검증
                    val defaultTags = listOf("Science", "News", "Shorts", "SciencePixel")
                    // Use video.tags instead of event.keywords (which doesn't exist on UploadRequestedEvent)
                    val keywords = video.tags
                    val combinedTags = (defaultTags + keywords)
                        .map { it.trim().take(30) }
                        .distinct()
                        .filter { it.isNotBlank() && it.length > 1 }
                        .take(20)

                    println("✅ Verification Passed. Meta: Title='${video.title}' (${if(hasKorean) "KR" else "NON-KR"}), Tags=${combinedTags.size}ea")

                    
                    val baseDescription = if (video.description.isNotBlank()) video.description else video.summary
                    
                    val finalDescription = if (baseDescription.contains("#")) {
                        baseDescription
                    } else {
                        "${baseDescription}\n\n#Science #News #Shorts"
                    }

                    val thumbnailFile = if (video.thumbnailPath.isNotBlank()) {
                        File(video.thumbnailPath)
                    } else null

                    val youtubeUrl = youtubeService.uploadVideo(
                        file,
                        video.title,
                        finalDescription,
                        combinedTags,
                        thumbnailFile
                    )

                    // Update DB - Fetch again to avoid stale object? Or just use video.id
                    repository.findById(video.id!!).ifPresent { v ->
                        repository.save(v.copy(
                            status = VideoStatus.UPLOADED,
                            youtubeUrl = youtubeUrl,
                            updatedAt = java.time.LocalDateTime.now()
                        ))
                    }

                    // Publish success event
                    eventPublisher.publishVideoUploaded(VideoUploadedEvent(
                        channelId = channelId,
                        videoId = video.id!!,
                        youtubeUrl = youtubeUrl
                    ))

                    // Discord 알림 전송
                    notificationService.notifyUploadComplete(video.title, youtubeUrl)

                    logPublisher.info("shorts-controller", "YouTube Upload Success: ${video.title}", "URL: $youtubeUrl", traceId = video.id!!)
                    println("✅ [$channelId] Upload Success via Kafka: $youtubeUrl")
                } else {
                    println("⚠️ [$channelId] File not found: ${video.filePath}")
                    eventPublisher.publishUploadFailed(UploadFailedEvent(
                        channelId = channelId,
                        videoId = video.id!!,
                        title = video.title,
                        filePath = video.filePath,
                        reason = "File not found",
                        retryCount = 0,
                        thumbnailPath = video.thumbnailPath
                    ))
                }
            } else {
                println("⚠️ Video record ${event.videoId} not found in DB. Skipping.")
                return
            }
        } catch (e: Exception) {
            logPublisher.error("shorts-controller", "YouTube Upload Failed: ${event.videoId}", "Error: ${e.message}", traceId = event.videoId)
            
            eventPublisher.publishUploadFailed(UploadFailedEvent(
                channelId = channelId,
                videoId = event.videoId,
                title = "Unknown Title", // We might not have video object here if findById failed? Accessing event is safer for basic info if available, but event only has limited fields.
                // UploadRequestedEvent has title.
                filePath = event.filePath,
                reason = e.message ?: "Unknown error",
                retryCount = 0,
                thumbnailPath = "" 
            ))
        }
    }
}
