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
    private val quotaTracker: QuotaTracker
) {
    
    companion object {
        private const val MAX_REGEN_COUNT = 1  // 재생성은 1회만 시도
    }

    @Scheduled(cron = "0 0 * * * *")
    @EventListener(ApplicationReadyEvent::class)
    @Async
    fun uploadPendingVideos() {
        println("⏰ Scheduler Triggered: Checking for pending/stuck videos at ${java.time.LocalDateTime.now()}")

        if (!quotaTracker.canUpload()) {
            println("🛑 Quota exceeded. Skipping hourly upload trigger.")
            return
        }

        // 2. Fetch target videos (COMPLETED, RETRY_PENDING, QUOTA_EXCEEDED)
        val targetStatuses = listOf(
            VideoStatus.COMPLETED, 
            VideoStatus.RETRY_PENDING, 
            VideoStatus.QUOTA_EXCEEDED,
            VideoStatus.STALE_JOB_ABANDONED
        )
        val pendingVideos = repository.findByStatusIn(targetStatuses).sortedBy { it.createdAt }
        
        println("📦 Found ${pendingVideos.size} videos for re-triggering.")

        // 3. Trigger via Kafka (Take only 1 video per hour to maintain cadence and quota)
        pendingVideos.take(1).forEach { video ->
            if (video.filePath.isNotBlank() && File(video.filePath).exists()) {
                println("🚀 Re-triggering upload via Kafka: ${video.title}")
                kafkaEventPublisher.publishVideoCreated(com.sciencepixel.event.VideoCreatedEvent(
                    videoId = video.id ?: "",
                    title = video.title,
                    summary = video.summary,
                    description = video.description,
                    link = video.link,
                    filePath = video.filePath,
                    keywords = emptyList()
                ))
            } else {
                // File missing -> Trigger regeneration if possible
                if (video.status != VideoStatus.UPLOADED) {
                    println("⚠️ File missing for ${video.title}. Triggering regeneration.")
                    triggerRegeneration(video)
                }
            }
        }
    }

    private fun triggerRegeneration(video: VideoHistory) {
        if (video.regenCount < MAX_REGEN_COUNT) {
            kafkaEventPublisher.publishRegenerationRequested(com.sciencepixel.event.RegenerationRequestedEvent(
                videoId = video.id ?: "",
                title = video.title,
                summary = video.summary,
                link = video.link,
                regenCount = video.regenCount
            ))
        } else {
            println("🚫 Max regeneration reached for: ${video.title}")
            repository.save(video.copy(status = VideoStatus.REGEN_FAILED, updatedAt = java.time.LocalDateTime.now()))
        }
    }
}
