package com.sciencepixel.service

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.UploadFailedEvent
import com.sciencepixel.event.VideoUploadedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime

@Service
class VideoUploadService(
    private val repository: VideoHistoryRepository,
    private val youtubeService: YoutubeService,
    private val eventPublisher: KafkaEventPublisher,
    private val notificationService: NotificationService,
    private val logPublisher: LogPublisher,
    private val jobClaimService: JobClaimService,
    private val channelBehavior: com.sciencepixel.config.ChannelBehavior, // Dependency Injection
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    fun uploadVideo(videoId: String) {
        println("📥 [$channelId] Processing Upload for: $videoId")

        try {
            val videoOpt = repository.findById(videoId)
            if (videoOpt.isPresent) {
                val video = videoOpt.get()
                // Idempotency check
                if (video.status == VideoStatus.UPLOADED && video.youtubeUrl.isNotBlank()) {
                    println("⏭️ Video $videoId already uploaded. Skipping.")
                    return
                }

                // Channel Ownership Check
                if (video.channelId != channelId) {
                    println("⏭️ Video $videoId belongs to channel '${video.channelId}', but this service is for '$channelId'. Skipping.")
                    return
                }

                if (video.status == VideoStatus.UPLOADING) {
                    println("⏳ Video $videoId is already being uploaded. Skipping.")
                    return
                }

                if (video.status != VideoStatus.COMPLETED && 
                    video.status != VideoStatus.UPLOAD_FAILED &&
                    video.status != VideoStatus.FAILED) {
                    println("⏳ Video $videoId is in status ${video.status}. Waiting for COMPLETED.")
                    return
                }

                // Atomic Claim
                val allowedStatuses = listOf(VideoStatus.COMPLETED, VideoStatus.UPLOAD_FAILED, VideoStatus.FAILED)
                if (!jobClaimService.claimJobFromAny(videoId, allowedStatuses, VideoStatus.UPLOADING)) {
                    println("⏭️ Upload claimed by another instance: ${video.title}")
                    return
                }

                // Perform Upload
                performUpload(video)

            } else {
                println("⚠️ Video record $videoId not found.")
            }
        } catch (e: Exception) {
            handleUploadError(videoId, e)
        }
    }

    private fun performUpload(video: VideoHistory) {
        try {
            val file = File(video.filePath)
            if (!file.exists()) {
                handleFileNotFound(video)
                return
            }

            // 1. Validation
            if (file.length() < 1024 * 1024) {
                 println("⚠️ Warning: Small video file (${file.length()} bytes).")
            }

            val hasKorean = video.title.any { it in '\uAC00'..'\uD7A3' }
            if (!hasKorean) {
                failValidation(video, "Title contains no Korean characters")
                return
            }

            // 2. Metadata Prep
            val defaultTags = channelBehavior.defaultTags // Use Channel Specific Tags
            val keywords = video.tags
            val combinedTags = (defaultTags + keywords)
                .map { it.trim().take(30) }
                .distinct()
                .filter { it.isNotBlank() && it.length > 1 }
                .take(20)

            val masterScript = if (video.description.isNotBlank()) video.description else video.summary
            
            // 1. Sources (Append if available)
            val sourcesLine = if (video.sources.isNotEmpty()) "\n\n출처: ${video.sources.joinToString(", ")}" else ""

            // 2. AI Context Tags (Dynamic, Lowercase)
            val aiContextTags = keywords.joinToString(" ") { "#${it.trim().lowercase().replace(" ", "_")}" }
            
            // 3. Assemble Final Description
            // Format: Master Script + Sources + AI Context Tags + Channel Default Tags
            val finalDescription = "$masterScript$sourcesLine\n\n$aiContextTags ${channelBehavior.defaultHashtags}"

            val thumbnailFile = if (video.thumbnailPath.isNotBlank()) File(video.thumbnailPath) else null

            // 3. YouTube API Call
            val youtubeUrl = youtubeService.uploadVideo(
                file,
                video.title,
                finalDescription,
                combinedTags,
                thumbnailFile
            )

            // 4. Success Handling
            repository.findById(video.id!!).ifPresent { v ->
                repository.save(v.copy(
                    status = VideoStatus.UPLOADED,
                    youtubeUrl = youtubeUrl,
                    updatedAt = LocalDateTime.now()
                ))
            }

            eventPublisher.publishVideoUploaded(VideoUploadedEvent(
                channelId = channelId,
                videoId = video.id!!,
                youtubeUrl = youtubeUrl
            ))

            notificationService.notifyUploadComplete(video.title, youtubeUrl)
            logPublisher.info("shorts-controller", "YouTube Upload Success: ${video.title}", "URL: $youtubeUrl", traceId = video.id!!)
            println("✅ [$channelId] Upload Success: $youtubeUrl")

        } catch (e: Exception) {
            handleUploadError(video.id!!, e)
        }
    }

    private fun handleFileNotFound(video: VideoHistory) {
         println("⚠️ [$channelId] File not found: ${video.filePath}")
         repository.save(video.copy(
             status = VideoStatus.UPLOAD_FAILED,
             failureStep = "UPLOAD",
             errorMessage = "File not found: ${video.filePath}",
             updatedAt = LocalDateTime.now()
         ))
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

    private fun failValidation(video: VideoHistory, reason: String) {
        println("⛔ Upload BLOCKED: $reason (${video.title})")
        repository.save(video.copy(
            status = VideoStatus.FAILED,
            failureStep = "VALIDATION",
            errorMessage = "Validation Failed: $reason",
            validationErrors = listOf("TITLE_ENGLISH"),
            updatedAt = LocalDateTime.now()
        ))
    }

    private fun handleUploadError(videoId: String, e: Exception) {
        logPublisher.error("shorts-controller", "YouTube Upload Failed: $videoId", "Error: ${e.message}", traceId = videoId)
        
        repository.findById(videoId).ifPresent { video ->
            repository.save(video.copy(
                status = VideoStatus.UPLOAD_FAILED,
                failureStep = "UPLOAD",
                errorMessage = e.message ?: "Unknown error",
                updatedAt = LocalDateTime.now()
            ))
        }
        
        eventPublisher.publishUploadFailed(UploadFailedEvent(
            channelId = channelId,
            videoId = videoId,
            title = "Unknown",
            filePath = "",
            reason = e.message ?: "Unknown error",
            retryCount = 0,
            thumbnailPath = "" 
        ))
    }
}
