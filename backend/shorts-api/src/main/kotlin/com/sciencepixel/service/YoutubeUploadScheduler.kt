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
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    
    companion object {
        private const val MAX_REGEN_COUNT = 1  // 재생성은 1회만 시도
    }

    @Scheduled(cron = "0 0/5 * * * *") // 매 5분마다 체크
    @Async
    fun uploadPendingVideos() {
        if (channelBehavior.shouldSkipGeneration()) return // Renderer(Worker) should not upload or check schedule

        println("⏰ [$channelId] Scheduler Triggered: Checking for pending videos at ${java.time.LocalDateTime.now()}")

        if (!quotaTracker.canUpload()) {
            println("🛑 [$channelId] Quota exceeded. Skipping upload trigger.")
            return
        }

        // 1. Check Interval Setting
        val intervalHours = systemSettingRepository.findByChannelIdAndKey(channelId, "UPLOAD_INTERVAL_HOURS")
            ?.value?.toDoubleOrNull() ?: 1.0 // Default 1 hour
        
        val lastUploaded = repository.findFirstByChannelIdAndStatusOrderByUpdatedAtDesc(channelId, VideoStatus.UPLOADED)
        
        if (lastUploaded != null) {
            val nextUploadTime = lastUploaded.updatedAt.plusMinutes((intervalHours * 60).toLong())
            if (java.time.LocalDateTime.now().isBefore(nextUploadTime)) {
                println("⏳ [$channelId] Cadence check: Next upload scheduled after $nextUploadTime. Skipping.")
                return
            }
        }

        // 2. Retry Logic (Stocks & History Only)
        // User Request: "역사와 주식 채널... 10분단위로 업로드 실패한 영상에만 재 시도"
        if (channelId == "stocks" || channelId == "history") {
            val failedUploads = repository.findTop5ByChannelIdAndStatusOrderByUpdatedAtAsc(channelId, VideoStatus.FAILED)
                .filter { it.failureStep == "UPLOAD_FAIL" }
            
            if (failedUploads.isNotEmpty()) {
                println("🔄 [$channelId] Retrying ${failedUploads.size} failed uploads...")
                failedUploads.forEach { triggerUpload(it) }
                return // Prioritize recovery, skip new uploads this cycle
            }
        }

        // 3. Fetch target videos (New Uploads)
        val pendingVideos = repository.findByChannelIdAndStatus(channelId, VideoStatus.COMPLETED)
            .sortedBy { it.createdAt }
            .filter { 
                // Strict Date Rule using ChannelBehavior
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
        
        println("📦 [$channelId] Found ${pendingVideos.size} videos ready for upload.")

        // 4. Trigger 
        pendingVideos.take(1).forEach { triggerUpload(it) }
    }

    private fun triggerUpload(video: VideoHistory) {
         if (video.filePath.isNotBlank() && File(video.filePath).exists()) {
            println("🚀 [$channelId] Triggering upload via Kafka: ${video.title}")
            kafkaEventPublisher.publishVideoCreated(com.sciencepixel.event.VideoCreatedEvent(
                channelId = channelId,
                videoId = video.id ?: "",
                title = video.title,
                summary = video.summary,
                description = video.description,
                link = video.link,
                filePath = video.filePath,
                keywords = emptyList()
            ))
            
            // Do NOT update status here. Let the Consumer claim it via Atomic check.
            // This prevents the "Already UPLOADING" lock-out race condition.
        } else {
            if (video.status != VideoStatus.UPLOADED) {
                println("⚠️ [$channelId] File missing for ${video.title}. Triggering regeneration.")
                triggerRegeneration(video)
            }
        }
    }

    private fun triggerRegeneration(video: VideoHistory) {
        if (video.regenCount < MAX_REGEN_COUNT) {
            kafkaEventPublisher.publishRegenerationRequested(com.sciencepixel.event.RegenerationRequestedEvent(
                channelId = channelId, // 추가
                videoId = video.id ?: "",
                title = video.title,
                summary = video.summary,
                link = video.link,
                regenCount = video.regenCount
            ))
        } else {
            println("🚫 [$channelId] Max regeneration reached for: ${video.title}")
            repository.save(video.copy(status = VideoStatus.FAILED, updatedAt = java.time.LocalDateTime.now()))
        }
    }
}
