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
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    
    companion object {
        private const val MAX_REGEN_COUNT = 1  // 재생성은 1회만 시도
    }

    @Scheduled(cron = "0 0/5 * * * *") // 매 5분마다 체크
    @Async
    fun uploadPendingVideos() {
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

        // 2. Fetch target videos
        val pendingVideos = repository.findByChannelIdAndStatus(channelId, VideoStatus.COMPLETED)
            .sortedBy { it.createdAt }
        
        println("📦 [$channelId] Found ${pendingVideos.size} videos ready for upload.")

        // 3. Trigger One by One
        pendingVideos.take(1).forEach { video ->
            if (video.filePath.isNotBlank() && File(video.filePath).exists()) {
                println("🚀 [$channelId] Triggering upload via Kafka: ${video.title}")
                
                // Immediately mark as UPLOADING to prevent race conditions or duplicate triggers
                repository.save(video.copy(
                    status = VideoStatus.UPLOADING,
                    updatedAt = java.time.LocalDateTime.now()
                ))

                kafkaEventPublisher.publishUploadRequested(com.sciencepixel.event.UploadRequestedEvent(
                    channelId = channelId,
                    videoId = video.id ?: "",
                    title = video.title,
                    filePath = video.filePath
                ))
            } else {
                if (video.status != VideoStatus.UPLOADED) {
                    println("⚠️ [$channelId] File missing for ${video.title}. Triggering regeneration.")
                    triggerRegeneration(video)
                }
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
