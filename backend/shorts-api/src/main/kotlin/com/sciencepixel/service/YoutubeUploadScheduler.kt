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
    private val videoUploadService: VideoUploadService, // Injected
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    // ... (rest of class) ...

    private fun triggerUpload(video: VideoHistory) {
         if (video.filePath.isNotBlank() && File(video.filePath).exists()) {
            println("🚀 [$channelId] Triggering upload directly via Service: ${video.title}")
            // Direct Service Call (Bypass Kafka for Scheduler)
            videoUploadService.uploadVideo(video.id!!)
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
