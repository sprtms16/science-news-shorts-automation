package com.sciencepixel.service

import com.sciencepixel.domain.SystemSetting
import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.repository.SystemSettingRepository
import org.springframework.context.event.EventListener
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.io.File

@Service
class YoutubeUploadScheduler(
    private val repository: VideoHistoryRepository,
    private val kafkaEventPublisher: com.sciencepixel.event.KafkaEventPublisher,
    private val systemSettingRepository: SystemSettingRepository,
    private val notificationService: NotificationService,
    private val quotaTracker: QuotaTracker,
    private val channelBehavior: com.sciencepixel.config.ChannelBehavior,
    private val videoUploadService: VideoUploadService,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    
    companion object {
        private const val MAX_REGEN_COUNT = 1
    }

    @Scheduled(cron = "\${app.scheduling.upload-cron:0 0/5 * * * *}") 
    fun uploadPendingVideos() {
        // 1. Channel Behavior & Quota Check
        if (channelBehavior.shouldSkipGeneration()) return 

        println("⏰ [$channelId] Schedular Triggered: Checking for pending videos (Time: ${java.time.LocalDateTime.now()})")

        if (!quotaTracker.canUpload()) {
            println("🛑 [$channelId] Quota exceeded. Skipping upload trigger.")
            return
        }

        // 1.5 Determine Upload Interval before fetching list
        val settingOpt = systemSettingRepository.findByChannelIdAndKey(channelId, "UPLOAD_INTERVAL_HOURS")
        val minIntervalHours = settingOpt?.value?.toLongOrNull() ?: when(channelId) {
            "stocks", "history" -> 24L
            else -> 12L
        }

        val lastUploaded = repository.findFirstByChannelIdAndStatusOrderByUpdatedAtDesc(channelId, VideoStatus.UPLOADED)
        val now = java.time.LocalDateTime.now()
        if (lastUploaded != null) {
            val hoursSinceLastUpload = java.time.temporal.ChronoUnit.HOURS.between(lastUploaded.updatedAt, now)
            if (hoursSinceLastUpload < minIntervalHours) {
                println("⏳ [$channelId] Upload skipped (YoutubeUploadScheduler). Last upload was $hoursSinceLastUpload hours ago (Min Interval: $minIntervalHours hrs).")
                return
            }
        }

        // 2. Fetch target videos (COMPLETED only)
        // We only pick ONE video to upload per cycle.
        val pendingVideos = repository.findByChannelIdAndStatus(channelId, VideoStatus.COMPLETED)
            .sortedBy { it.createdAt } // Oldest first
            .filter { 
                if (channelBehavior.requiresStrictDateCheck) {
                    val startOfDay = java.time.LocalDate.now().atStartOfDay()
                    val isToday = it.createdAt.isAfter(startOfDay)
                    if (!isToday) {
                        println("⏳ [$channelId] Skipping old video (Created: ${it.createdAt}) due to strict 'Today's News' policy.")
                    }
                    isToday
                } else {
                    true
                }
            }
        
        if (pendingVideos.isEmpty()) {
            println("📦 [$channelId] No pending COMPLETED videos found.")
            return
        }

        // 3. Trigger One Upload
        val targetVideo = pendingVideos.first()
        println("🚀 [$channelId] Triggering upload for: ${targetVideo.title}")
        
        try {
            triggerUpload(targetVideo)
        } catch (e: Exception) {
            println("❌ [$channelId] Error during scheduled upload for ${targetVideo.title}: ${e.message}")
            // Stop on Error: Do NOT retry, just leave it as UPLOAD_FAILED (or whatever the service sets)
            // The service handles setting the status to UPLOAD_FAILED.
            // We do not proceed to next video. We stop here.
        }
    }

    private fun triggerUpload(video: VideoHistory) {
         if (video.filePath.isNotBlank() && File(video.filePath).exists()) {
            videoUploadService.uploadVideo(video.id!!)
        } else {
            println("⚠️ [$channelId] File missing for ${video.title}. Marking as FAILED.")
            repository.save(video.copy(
                status = VideoStatus.FAILED,
                failureStep = "UPLOAD",
                errorMessage = "File missing during scheduled upload",
                updatedAt = java.time.LocalDateTime.now()
            ))
        }
    }
}
