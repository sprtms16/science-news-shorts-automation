package com.sciencepixel.controller

import com.sciencepixel.domain.SystemPrompt
import com.sciencepixel.repository.SystemPromptRepository
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.domain.DuplicateLinkGroup
import com.sciencepixel.service.GeminiService
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.data.domain.Sort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.sciencepixel.service.ProductionService
import com.sciencepixel.repository.SystemSettingRepository
import com.sciencepixel.domain.SystemSetting
import com.sciencepixel.service.YoutubeSyncService
import com.sciencepixel.repository.YoutubeVideoRepository
import org.springframework.data.domain.PageRequest

@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = ["*"])
class AdminController(
    private val videoRepository: VideoHistoryRepository,
    private val promptRepository: SystemPromptRepository,
    private val geminiService: GeminiService,
    private val kafkaEventPublisher: com.sciencepixel.event.KafkaEventPublisher, // Renamed to avoid conflict
    private val productionService: ProductionService,
    private val systemSettingRepository: SystemSettingRepository,
    private val cleanupService: com.sciencepixel.service.CleanupService,
    private val youtubeUploadScheduler: com.sciencepixel.service.YoutubeUploadScheduler,
    private val youtubeService: com.sciencepixel.service.YoutubeService,
    private val youtubeVideoRepository: YoutubeVideoRepository,
    private val youtubeSyncService: YoutubeSyncService,
    private val quotaTracker: com.sciencepixel.service.QuotaTracker
) {

    @PostMapping("/videos/upload-pending")
    fun triggerPendingUploads(): ResponseEntity<Map<String, Any>> {
        // Run asynchronously via @Async on the scheduler method
        youtubeUploadScheduler.uploadPendingVideos()
        
        return ResponseEntity.ok(mapOf(
            "message" to "Triggered pending video upload check in background."
        ))
    }

    @GetMapping("/youtube/my-videos")
    fun getMyVideos(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<com.sciencepixel.domain.YoutubeVideoResponse> {
        val pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending())
        val videoPage = youtubeVideoRepository.findAllByOrderByPublishedAtDesc(pageable)
        
        val stats = videoPage.content.map { entity ->
            com.sciencepixel.domain.YoutubeVideoStat(
                videoId = entity.videoId,
                title = entity.title,
                description = entity.description,
                viewCount = entity.viewCount,
                likeCount = entity.likeCount,
                publishedAt = entity.publishedAt.toString(),
                thumbnailUrl = entity.thumbnailUrl
            )
        }
        
        val nextPage = if (videoPage.hasNext()) (page + 1).toString() else null
        
        return ResponseEntity.ok(com.sciencepixel.domain.YoutubeVideoResponse(stats, nextPage))
    }

    @PostMapping("/youtube/sync")
    fun triggerYoutubeSync(): ResponseEntity<Map<String, Any>> {
        youtubeSyncService.syncVideos()
        return ResponseEntity.ok(mapOf(
            "message" to "YouTube synchronization triggered successfully."
        ))
    }

    @DeleteMapping("/videos/history/uploaded")
    fun deleteUploadedHistory(): ResponseEntity<Map<String, Any>> {
        val uploadedOnes = videoRepository.findByStatus(VideoStatus.UPLOADED)
        val count = uploadedOnes.size
        if (uploadedOnes.isNotEmpty()) {
            videoRepository.deleteAll(uploadedOnes)
        }
        return ResponseEntity.ok(mapOf(
            "deletedCount" to count,
            "message" to "Successfully deleted $count uploaded records from local database."
        ))
    }

    @PostMapping("/maintenance/reset-quota-status")
    fun resetQuotaStatus(): ResponseEntity<Map<String, Any>> {
        // Now quota handling is automatic (remains COMPLETED)
        return ResponseEntity.ok(mapOf(
            "message" to "Quota status reset is now automatic in the new status system."
        ))
    }

    @PostMapping("/maintenance/reset-daily-quota-units")
    fun resetDailyQuotaUnits(): ResponseEntity<Map<String, Any>> {
        quotaTracker.resetQuota()
        
        // Also clear any system-wide block settings
        systemSettingRepository.deleteById("UPLOAD_BLOCKED_UNTIL")
        
        return ResponseEntity.ok(mapOf(
            "message" to "Successfully reset internal YouTube daily quota units and cleared block settings."
        ))
    }

    @PostMapping("/videos/{id}/metadata/regenerate")
    fun regenerateMetadata(@PathVariable id: String): ResponseEntity<VideoHistory> {
        val video = videoRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        
        val response = geminiService.regenerateMetadataOnly(video.title, video.summary)
        
        val updated = video.copy(
            title = response.title.ifEmpty { video.title },
            description = response.description,
            tags = response.tags,
            sources = response.sources,
            updatedAt = LocalDateTime.now()
        )
        
        return ResponseEntity.ok(videoRepository.save(updated))
    }

    @PostMapping("/youtube/update-all-descriptions")
    fun updateAllYoutubeDescriptions(): ResponseEntity<Map<String, Any>> {
        val uploadedVideos = videoRepository.findByStatus(VideoStatus.UPLOADED)
        var updatedCount = 0
        val failedVideos = mutableListOf<String>()

        uploadedVideos.forEach { video ->
            if (video.youtubeUrl.isNotBlank() && video.description.isNotBlank()) {
                try {
                    val videoId = if (video.youtubeUrl.contains("youtu.be/")) {
                        video.youtubeUrl.substringAfter("youtu.be/").substringBefore("?").trim()
                    } else if (video.youtubeUrl.contains("v=")) {
                        video.youtubeUrl.substringAfter("v=").substringBefore("&").trim()
                    } else {
                        null
                    }

                    if (videoId != null && videoId.isNotEmpty()) {
                        youtubeService.updateVideoMetadata(videoId, description = video.description)
                        updatedCount++
                        Thread.sleep(300) // Safety delay
                    }
                } catch (e: Exception) {
                    println("❌ Failed to update description for ${video.id}: ${e.message}")
                    failedVideos.add("${video.id}: ${e.message}")
                }
            }
        }

        return ResponseEntity.ok(mapOf(
            "totalAttempted" to uploadedVideos.size,
            "updatedCount" to updatedCount,
            "failedCount" to failedVideos.size,
            "failedItems" to failedVideos,
            "message" to "Batch description update process finished."
        ))
    }

    @PostMapping("/youtube/fix-video-description")
    fun fixVideoDescription(@RequestParam videoId: String): ResponseEntity<Map<String, Any>> {
        val allVideos = videoRepository.findAll()
        val videoInDb = allVideos.find { it.youtubeUrl.contains(videoId) }
        
        val title: String
        val summary: String
        
        if (videoInDb != null) {
            title = videoInDb.title
            summary = videoInDb.summary
        } else {
            val snippet = youtubeService.getVideoSnippet(videoId) ?: return ResponseEntity.badRequest().body(mapOf("error" to "Video not found on YouTube"))
            title = snippet.title
            summary = snippet.title
        }

        val generated = geminiService.regenerateMetadataOnly(title, summary)
        val baseDescription = generated.description
        
        // 업로드 로직과 동일하게 해시태그 추가 (기존에 #이 없을 경우만)
        val finalDescription = if (baseDescription.contains("#")) {
            baseDescription
        } else {
            "${baseDescription}\n\n#Science #News #Shorts"
        }

        youtubeService.updateVideoMetadata(videoId, description = finalDescription)

        if (videoInDb != null) {
            videoRepository.save(videoInDb.copy(
                description = finalDescription,
                updatedAt = LocalDateTime.now()
            ))
        }

        return ResponseEntity.ok(mapOf(
            "status" to "success",
            "videoId" to videoId,
            "title" to title,
            "generatedDescription" to finalDescription,
            "message" to "Description fixed successfully."
        ))
    }

    @PostMapping("/videos/rematch-files")
    fun rematchFilesWithDb(): ResponseEntity<Map<String, Any>> {
        val outputDir = File("/app/shared-data")
        if (!outputDir.exists()) {
            return ResponseEntity.ok(mapOf("matched" to 0, "message" to "Directory /app/shared-data not found"))
        }

        val videoFiles = outputDir.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".mp4") } ?: emptyList()
        var matchedCount = 0
        val matchedVideos = mutableListOf<String>()

        val videosToMatchStatuses = listOf(
            VideoStatus.COMPLETED,
            VideoStatus.CREATING,
            VideoStatus.FAILED
        )
        val videosToMatch = videoRepository.findByStatusIn(videosToMatchStatuses).filter { 
            it.filePath.isBlank() || !File(it.filePath).exists()
        }

        for (video in videosToMatch) {
            val videoCreatedAt = video.createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val videoTitleSlug = video.title.take(20).replace(Regex("[^a-zA-Z0-9가-힣]"), "_").lowercase()
            
            // Try to find matching file
            val matchingFile = videoFiles.find { file ->
                val fileName = file.name.lowercase()
                
                // 1. Title Slug Match (High Precision)
                if (fileName.contains(videoTitleSlug)) {
                    return@find true
                }
                
                // 2. Timestamp Match (Fallback for files without title slug or with truncated titles)
                if (fileName.startsWith("shorts_")) {
                    val namePart = fileName.substringBeforeLast(".")
                    val fileTimestampStr = namePart.substringAfterLast("_")
                    val fileTimestamp = fileTimestampStr.toLongOrNull()
                    
                    if (fileTimestamp != null) {
                        val diff = java.lang.Math.abs(fileTimestamp - videoCreatedAt)
                        diff <= 900000L // 15 minutes
                    } else false
                } else false
            }

            if (matchingFile != null) {
                videoRepository.save(video.copy(
                    filePath = matchingFile.absolutePath,
                    status = if (video.status == VideoStatus.FAILED) VideoStatus.COMPLETED else video.status,
                    failureStep = "",
                    errorMessage = "",
                    updatedAt = LocalDateTime.now()
                ))
                matchedCount++
                matchedVideos.add("${video.id}: ${matchingFile.name}")
            }
        }

        return ResponseEntity.ok(mapOf(
            "matched" to matchedCount,
            "total_without_file" to videosToMatch.size,
            "matched_videos" to matchedVideos.take(20)
        ))
    }

    @PostMapping("/videos/regenerate-missing-files")
    fun regenerateMissingFiles(): ResponseEntity<Map<String, Any>> {
        val regenerationTargetStatuses = listOf(
            VideoStatus.COMPLETED,
            VideoStatus.FAILED
        )
        val targetVideos = videoRepository.findByStatusIn(regenerationTargetStatuses).filter {
            it.filePath.isBlank() || !File(it.filePath).exists()
        }

        var triggeredCount = 0
        targetVideos.forEach { video ->
            // Update status (Preserve UPLOADED status to avoid re-uploading)
            val nextStatus = if (video.status == VideoStatus.UPLOADED) VideoStatus.UPLOADED else VideoStatus.CREATING
            videoRepository.save(video.copy(status = nextStatus, updatedAt = LocalDateTime.now()))
            
            kafkaEventPublisher.publishRegenerationRequested(com.sciencepixel.event.RegenerationRequestedEvent(
                videoId = video.id ?: "",
                title = video.title,
                summary = video.summary,
                link = video.link,
                regenCount = video.regenCount
            ))
            triggeredCount++
        }

        return ResponseEntity.ok(mapOf(
            "triggered" to triggeredCount,
            "message" to "Triggered regeneration for $triggeredCount videos."
        ))
    }

    @PostMapping("/videos/regenerate-all-metadata")
    fun regenerateAllMetadata(): ResponseEntity<Map<String, Any>> {
        val allVideos = videoRepository.findAll()
        val videosToUpdate = allVideos.filter { video ->
            !video.title.any { it in '\uAC00'..'\uD7A3' }
        }

        var updatedCount = 0
        val results = mutableListOf<String>()

        for (video in videosToUpdate.take(10)) {
            try {
                val response = geminiService.regenerateMetadataOnly(video.title, video.summary)
                videoRepository.save(video.copy(
                    title = response.title.ifEmpty { video.title },
                    description = response.description,
                    tags = response.tags,
                    sources = response.sources,
                    updatedAt = LocalDateTime.now()
                ))
                updatedCount++
                results.add("✅ ${video.id}: ${video.title} -> ${response.title}")
                Thread.sleep(500)
            } catch (e: Exception) {
                results.add("❌ ${video.id}: ${e.message}")
            }
        }

        return ResponseEntity.ok(mapOf(
            "updated" to updatedCount,
            "total_non_korean" to videosToUpdate.size,
            "results" to results
        ))
    }

    @GetMapping("/videos")
    fun getAllVideos(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "15") size: Int
    ): ResponseEntity<Map<String, Any?>> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val videoPage = videoRepository.findAll(pageable)
        
        return ResponseEntity.ok(mapOf(
            "videos" to videoPage.content,
            "nextPage" to if (videoPage.hasNext()) page + 1 else null,
            "totalCount" to videoPage.totalElements
        ))
    }

    @GetMapping("/videos/{id}")
    fun getVideo(@PathVariable id: String): ResponseEntity<VideoHistory> {
        return videoRepository.findById(id).map { ResponseEntity.ok(it) }.orElse(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/videos/{id}")
    fun deleteVideo(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        val video = videoRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        
        try {
            // 1. Delete physical file if exists
            if (video.filePath.isNotBlank()) {
                cleanupService.deleteVideoFile(video.filePath)
            }
            
            // 2. Delete DB record
            videoRepository.deleteById(id)
            
            println("🗑️ Manually deleted video record and file: ${video.id}")
            return ResponseEntity.ok(mapOf("status" to "success", "message" to "Video deleted successfully"))
        } catch (e: Exception) {
            println("❌ Error deleting video ${video.id}: ${e.message}")
            return ResponseEntity.internalServerError().body(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }

    @PutMapping("/videos/{id}/status")
    fun updateVideoStatus(@PathVariable id: String, @RequestBody request: UpdateStatusRequest): ResponseEntity<VideoHistory> {
        val video = videoRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        
        var updated = video.copy(
            status = request.status,
            youtubeUrl = request.youtubeUrl ?: video.youtubeUrl,
            updatedAt = LocalDateTime.now()
        )

        // If manually setting to UPLOADED, clean up the file
        if (request.status == VideoStatus.UPLOADED) {
            try {
                if (video.filePath.isNotBlank()) {
                    val file = File(video.filePath)
                    if (file.exists() && file.delete()) {
                        println("🗑️ Manually marked as UPLOADED. Deleted file: ${file.path}")
                    }
                }
                updated = updated.copy(filePath = "")
            } catch (e: Exception) {
                println("⚠️ Failed to delete file for manual upload: ${e.message}")
            }
        }
        
        return ResponseEntity.ok(videoRepository.save(updated))
    }

    @GetMapping("/videos/{id}/download")
    fun downloadVideo(@PathVariable id: String): ResponseEntity<Resource> {
        val video = videoRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        if (video.filePath.isBlank()) return ResponseEntity.notFound().build()

        return try {
            val path = Paths.get(video.filePath)
            val resource = UrlResource(path.toUri())
            if (resource.exists() && resource.isReadable) {
                // RFC 5987 compliant content disposition (handles UTF-8 correctly)
                val contentDisposition = org.springframework.http.ContentDisposition
                    .builder("attachment")
                    .filename("${video.title}.mp4", StandardCharsets.UTF_8)
                    .build()

                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                    .body(resource)
            } else ResponseEntity.notFound().build()
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/prompts")
    fun getAllPrompts(): List<SystemPrompt> = promptRepository.findAll()

    @GetMapping("/prompts/{id}")
    fun getPrompt(@PathVariable id: String): ResponseEntity<SystemPrompt> {
        return promptRepository.findById(id).map { ResponseEntity.ok(it) }.orElse(ResponseEntity.notFound().build())
    }

    @PostMapping("/prompts")
    fun savePrompt(@RequestBody prompt: SystemPrompt): SystemPrompt = promptRepository.save(prompt.copy(updatedAt = LocalDateTime.now()))

    @PostMapping("/videos/cleanup-sensitive")
    fun cleanupSensitiveVideos(): ResponseEntity<Map<String, Any>> {
        val videos = videoRepository.findByStatusNot(VideoStatus.UPLOADED)
            .sortedByDescending { it.createdAt }
            .take(20) // 최신 20개만 집중 검사 (API 429 방지)
            
        var deletedCount = 0
        videos.forEach { video ->
            if (!geminiService.checkSensitivity(video.title, video.summary)) {
                if (video.filePath.isNotBlank()) {
                    cleanupService.deleteVideoFile(video.filePath)
                }
                videoRepository.delete(video)
                deletedCount++
                println("⛔ Safety Cleanup: Deleted sensitive video '${video.title}'")
            }
        }
        return ResponseEntity.ok(mapOf(
            "deletedCount" to deletedCount,
            "message" to "Safety cleanup finished for latest 20 items."
        ))
    }

    @PostMapping("/videos/cleanup-failed")
    fun cleanupFailedVideos(): ResponseEntity<Map<String, Any>> {
        try {
            cleanupService.cleanupFailedVideos()
            return ResponseEntity.ok(mapOf("message" to "Failed videos cleanup triggered successfully"))
        } catch (e: Exception) {
            return ResponseEntity.internalServerError().body(mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    @PostMapping("/maintenance/deep-cleanup")
    fun deepCleanup(): ResponseEntity<Map<String, Any>> {
        val allVideos = videoRepository.findAll()
        var deletedCount = 0
        
        allVideos.forEach { video ->
            val fileExists = if (video.filePath.isNotBlank()) File(video.filePath).exists() else false
            val isUploaded = video.status == VideoStatus.UPLOADED
            
            // "유튜브에 올라간 것(UPLOADED)과 영상파일이 만들어진 것(fileExists)을 제외한 나머지" 삭제
            if (!isUploaded && !fileExists) {
                videoRepository.delete(video)
                deletedCount++
                println("🗑️ Deep Cleanup: Deleted inconsistent record '${video.title}' (Status: ${video.status})")
            }
        }
        
        return ResponseEntity.ok(mapOf(
            "deletedCount" to deletedCount,
            "remainingCount" to videoRepository.count(),
            "message" to "Deep cleanup completed. Only UPLOADED or records with existing files preserved."
        ))
    }

    // System Settings API
    @GetMapping("/settings")
    fun getAllSettings(): List<SystemSetting> = systemSettingRepository.findAll()

    @GetMapping("/settings/{key}")
    fun getSetting(@PathVariable key: String): ResponseEntity<SystemSetting> {
        return systemSettingRepository.findById(key)
            .map { ResponseEntity.ok(it) }
            .orElse(ResponseEntity.notFound().build())
    }

    @PostMapping("/maintenance/cleanup-deleted-youtube")
    fun cleanupDeletedYoutubeVideos(): ResponseEntity<Map<String, Any>> {
        val uploadedVideos = videoRepository.findByStatus(VideoStatus.UPLOADED)
        var cleanedCount = 0
        val deletedIds = mutableListOf<String>()

        uploadedVideos.forEach { video ->
            if (video.youtubeUrl.isNotBlank()) {
                val videoId = if (video.youtubeUrl.contains("youtu.be/")) {
                    video.youtubeUrl.substringAfter("youtu.be/").substringBefore("?").trim()
                } else if (video.youtubeUrl.contains("v=")) {
                    video.youtubeUrl.substringAfter("v=").substringBefore("&").trim()
                } else {
                    null
                }

                if (videoId != null) {
                    try {
                        val snippet = youtubeService.getVideoSnippet(videoId)
                        if (snippet == null) {
                            // Video is missing on YT
                            videoRepository.save(video.copy(
                                status = VideoStatus.FAILED,
                                failureStep = "YOUTUBE",
                                errorMessage = "Video deleted on YouTube",
                                updatedAt = LocalDateTime.now()
                            ))
                            cleanedCount++
                            deletedIds.add(video.id ?: "unknown")
                        }
                    } catch (e: Exception) {
                        println("⚠️ Failed to check existence for ${video.id}: ${e.message}")
                    }
                }
            }
        }

        return ResponseEntity.ok(mapOf(
            "checkedCount" to uploadedVideos.size,
            "cleanedCount" to cleanedCount,
            "deletedIds" to deletedIds,
            "message" to "Cleanup of deleted YouTube videos finished."
        ))
    }

    @PostMapping("/maintenance/sync-uploaded")
    fun syncUploadedStatus(): ResponseEntity<Map<String, Any>> {
        val videos = videoRepository.findByStatusIn(listOf(
            VideoStatus.COMPLETED,
            VideoStatus.CREATING,
            VideoStatus.FAILED
        )).filter { 
            it.youtubeUrl.isNotBlank() 
        }
        var updatedCount = 0

        videos.forEach { video ->
            try {
                // Delete file if exists
                if (video.filePath.isNotBlank()) {
                    val file = File(video.filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }
                
                videoRepository.save(video.copy(
                    status = VideoStatus.UPLOADED,
                    filePath = "",
                    updatedAt = LocalDateTime.now()
                ))
                updatedCount++
            } catch (e: Exception) {
                println("❌ Sync Error for ${video.id}: ${e.message}")
            }
        }

        return ResponseEntity.ok(mapOf(
            "syncedCount" to updatedCount,
            "message" to "$updatedCount videos synced to UPLOADED status."
        ))
    }

    @PostMapping("/maintenance/deduplicate-links")
    fun deduplicateLinks(): ResponseEntity<Map<String, Any>> {
        val duplicateGroups = videoRepository.findDuplicateLinks()
        var deletedCount = 0
        
        duplicateGroups.forEach { group ->
            val link = group._id
            val videos = group.docs
            
            // Keep the most recent one (by createdAt)
            val sorted = videos.sortedByDescending { it.createdAt }
            val toDelete = sorted.drop(1)
            
            toDelete.forEach { video ->
                if (video.filePath.isNotBlank()) {
                    cleanupService.deleteVideoFile(video.filePath)
                }
                videoRepository.delete(video)
                deletedCount++
                println("🗑️ Deduplication (Agg): Deleted duplicate record for '$link' (ID: ${video.id})")
            }
        }
        
        return ResponseEntity.ok(mapOf(
            "duplicateLinksFound" to duplicateGroups.size,
            "recordsDeleted" to deletedCount,
            "message" to "Aggregation-based deduplication completed. $deletedCount duplicate records removed."
        ))
    }

    @PostMapping("/settings")
    fun saveSetting(@RequestBody setting: SystemSetting): SystemSetting {
        return systemSettingRepository.save(setting.copy(updatedAt = LocalDateTime.now()))
    }

    @PostMapping("/maintenance/repair-all")
    fun repairSystem(): ResponseEntity<Map<String, Any>> {
        val videos = videoRepository.findAll()
        var updatedCount = 0
        var regenTriggeredCount = 0
        val now = LocalDateTime.now()

        videos.forEach { video ->
            try {
                val file = File(video.filePath)
                val hasLink = video.youtubeUrl.isNotBlank()
                val hasFile = video.filePath.isNotBlank() && file.exists()
                
                var targetStatus: VideoStatus = video.status

                if (hasLink) {
                    targetStatus = VideoStatus.UPLOADED
                } else if (hasFile) {
                    targetStatus = VideoStatus.COMPLETED
                } else {
                    // No link, No file
                    // If it was recently updated (within 30 mins), maybe it's still CREATING
                    val isRecent = video.updatedAt.isAfter(now.minusMinutes(30))
                    targetStatus = if (isRecent) VideoStatus.CREATING else VideoStatus.FAILED
                }

                if (video.status != targetStatus) {
                    val updatedVideo = video.copy(
                        status = targetStatus,
                        updatedAt = LocalDateTime.now()
                    )
                    videoRepository.save(updatedVideo)
                    updatedCount++

                    // If it became FAILED, trigger regeneration
                    if (targetStatus == VideoStatus.FAILED && video.regenCount < 1) {
                        kafkaEventPublisher.publishRegenerationRequested(
                            com.sciencepixel.event.RegenerationRequestedEvent(
                                videoId = video.id!!,
                                title = video.title,
                                summary = video.summary,
                                link = video.link,
                                regenCount = video.regenCount
                            )
                        )
                        regenTriggeredCount++
                    }
                }
            } catch (e: Exception) {
                println("❌ Repair Error for ${video.id}: ${e.message}")
            }
        }

        return ResponseEntity.ok(mapOf(
            "totalChecked" to videos.size,
            "updatedStatusCount" to updatedCount,
            "regenerationTriggered" to regenTriggeredCount,
            "message" to "Repair and migration to 4-status system complete."
        ))
    }

    @PostMapping("/maintenance/reset-creating-to-queued")
    fun resetCreatingToQueued(): ResponseEntity<Map<String, Any>> {
        val stuckVideos = videoRepository.findByStatus(VideoStatus.CREATING)
        var resetCount = 0
        
        stuckVideos.forEach { video ->
            videoRepository.save(video.copy(
                status = VideoStatus.QUEUED,
                updatedAt = LocalDateTime.now()
            ))
            // Re-publish RSS event to restart the pipeline? 
            // Better to let ScriptConsumer pick it up if we implement a poller, 
            // OR we just set to QUEUED and manually republish event?
            // Since our system is Event-Driven, just changing status to QUEUED isn't enough to trigger Consumer active listening if the event is gone.
            // BUT ScriptConsumer consumes events. It doesn't poll DB.
            // So we MUST also re-publish the RssNewItemEvent for these items.
            
            kafkaEventPublisher.publishRssNewItem(com.sciencepixel.event.RssNewItemEvent(
                url = video.link,
                title = video.title,
                category = "general"
            ))
            
            resetCount++
        }
        
        return ResponseEntity.ok(mapOf(
            "message" to "Reset $resetCount stuck CREATING videos to QUEUED and re-published events.",
            "resetCount" to resetCount
        ))
    }

    @PostMapping("/maintenance/translate-uploaded-videos")
    fun translateUploadedEnglishVideos(): ResponseEntity<Map<String, Any>> {
        val uploadedVideos = videoRepository.findByStatus(VideoStatus.UPLOADED)
        var updateCount = 0
        val updatedVideos = mutableListOf<String>()

        uploadedVideos.forEach { video ->
            if (video.youtubeUrl.isNotBlank()) {
                // Check for English title
                val hasKorean = video.title.any { it in '\uAC00'..'\uD7A3' }
                if (!hasKorean) {
                    println("🔄 Found English title for video ${video.id} (${video.title}). Translating...")
                    
                    try {
                        // 1. Regenerate Metadata (Korean)
                        val newMeta = geminiService.regenerateMetadataOnly(video.title, video.summary)
                        
                        // 2. Extract Video ID
                        val videoId = video.youtubeUrl.substringAfterLast("/").substringAfter("v=")
                        
                        if (videoId.isNotBlank() && videoId != video.youtubeUrl) {
                            // 3. Update YouTube
                            youtubeService.updateVideoMetadata(
                                videoId = videoId,
                                title = newMeta.title,
                                description = newMeta.description
                            )
                            
                            // 4. Update Local DB
                            videoRepository.save(video.copy(
                                title = newMeta.title,
                                description = newMeta.description,
                                tags = newMeta.tags,
                                sources = newMeta.sources,
                                updatedAt = LocalDateTime.now()
                            ))
                            
                            updatedVideos.add("${video.title} -> ${newMeta.title}")
                            updateCount++
                        }
                    } catch (e: Exception) {
                        println("❌ Failed to translate video ${video.id}: ${e.message}")
                    }
                }
            }
        }

        return ResponseEntity.ok(mapOf(
            "message" to "Translated and updated $updateCount videos.",
            "updatedVideos" to updatedVideos
        ))
    }
}

data class UpdateStatusRequest(val status: VideoStatus, val youtubeUrl: String? = null)
