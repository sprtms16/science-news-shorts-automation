package com.sciencepixel.service

import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.repository.SystemSettingRepository
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.Date

@Service
class BatchScheduler(
    private val jobLauncher: JobLauncher,
    private val shortsJob: Job,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val systemSettingRepository: SystemSettingRepository,
    private val cleanupService: CleanupService,
    private val kafkaEventPublisher: com.sciencepixel.event.KafkaEventPublisher,
    private val notificationService: com.sciencepixel.service.NotificationService, // Added
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    // 매 10분마다 실행 (0, 10, 20, 30, 40, 50분)
    @Scheduled(cron = "\${app.scheduling.batch-cron:0 0/10 * * * *}")
    fun runBatchJobIfNeeded() {
        println("⏰ Batch Scheduler: Checking generation buffer at ${Date()}")

        // 1. Pre-Cleanup: 1시간 이상 경과한 '작업 중' 레코드 삭제
        try {
            cleanupService.cleanupStaleJobs()
        } catch (e: Exception) {
            println("⚠️ Stale Job Cleanup Warning: ${e.message}")
        }

        // 2. Get Limit from Settings (Default 10)
        val limit = systemSettingRepository.findByChannelIdAndKey(channelId, "MAX_GENERATION_LIMIT")
            ?.value?.toIntOrNull() ?: 10

        // 3. Count Active and Failed separately
        val activeStatuses = listOf(
            VideoStatus.QUEUED,
            VideoStatus.CREATING,
            VideoStatus.COMPLETED,
            VideoStatus.UPLOADING
        )
        val activeCount = videoHistoryRepository.findByChannelIdAndStatusIn(channelId, activeStatuses).size
        val failedCount = videoHistoryRepository.findByChannelIdAndStatus(channelId, VideoStatus.FAILED).size

        println("📊 Video Buffer Strategy [$channelId]:")
        println("   - Active/Pending: $activeCount / $limit")
        println("   - Failed History: $failedCount")

        // Notification if too many failures (Buffered capacity alert)
        // User Request: Failure buffer should be same size as Active buffer to prevent waste.
        if (failedCount >= limit) {
            println("⚠️ FAILED jobs buffer reached limit ($failedCount / $limit). Sending alert & Pausing generation.")
            notificationService.sendDiscordNotification(
                title = "🚨 [Buffer Full] $channelId 채널 실패 가득 참",
                description = "실패한 영상이 한도($limit)에 도달했습니다. 추가 리소스 낭비를 막기 위해 생성을 일시 중지합니다. 확인 후 정리해주세요.",
                color = 0xFF0000
            )
            // Stop generation to prevent waste
            println("🛑 Failure Buffer Full. Skipping new generation.")
            return
        }

        if (activeCount < limit) {
            println("🚀 Active buffer has space. Triggering Batch Job...")
            try {
                val params = JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .addLong("remainingSlots", 1L)
                    .toJobParameters()
                
                jobLauncher.run(shortsJob, params)
            } catch (e: Exception) {
                println("❌ Batch Job Launch Failed: ${e.message}")
            }
        } else {
            println("🛑 Active Buffer Full ($activeCount >= $limit). Skipping generation.")
        }
    }

    // 매시 5분에 실패한 영상 재시도 체크 (0 5 * * * *)
    @Scheduled(cron = "0 5 * * * *")
    fun retryFailedGenerations() {
        println("⏰ Batch Scheduler: Checking for FAILED videos to retry at ${Date()}")
        
        val failedVideos = videoHistoryRepository.findByChannelIdAndStatus(channelId, VideoStatus.FAILED)
            .filter { it.regenCount < 1 } // 재생성 시도 안 한 것만
        
        if (failedVideos.isNotEmpty()) {
            println("🔄 Found ${failedVideos.size} FAILED videos. Processing 1 item to avoid burst load...")
            
            failedVideos.take(1).forEach { video ->
                // Skip safety issues for auto-retry? 
                // Better: if it failed for SAFETY, don't auto-retry the SAME title/link.
                if (video.failureStep == "SAFETY") {
                    println("🛡️ Skipping auto-retry for safety-blocked item: ${video.title}")
                    return@forEach
                }

                if (video.regenCount >= 3) {
                    println("🛑 Max retries (3) reached for: ${video.title}. Requires manual intervention.")
                    return@forEach
                }

                if (video.failureStep == "UPLOAD") {
                    val file = java.io.File(video.filePath)
                    if (file.exists() && file.length() > 0) {
                        println("♻️ [Auto-Recovery] File exists for ${video.title} (UPLOAD fail). Resetting to COMPLETED for retry.")
                        videoHistoryRepository.save(video.copy(
                            status = VideoStatus.COMPLETED,
                            failureStep = "",
                            errorMessage = "",
                            updatedAt = java.time.LocalDateTime.now()
                        ))
                        return@forEach
                    }
                }
                
                // Default: Full Regeneration
                println("🔄 [$channelId] [Auto-Recovery] Triggering regeneration (${video.regenCount + 1}/3) for: ${video.title}")
                kafkaEventPublisher.publishRegenerationRequested(
                    com.sciencepixel.event.RegenerationRequestedEvent(
                        channelId = channelId,
                        videoId = video.id!!,
                        title = video.title,
                        summary = video.summary,
                        link = video.link,
                        regenCount = video.regenCount + 1 // Increment count
                    )
                )
                
                // Update DB immediately to avoid duplicate triggers
                videoHistoryRepository.save(video.copy(
                    regenCount = video.regenCount + 1,
                    status = VideoStatus.QUEUED, // Set to QUEUED to show it's being retried
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
        }
    }
    // 매시 0분에 업로드 체크 (0 0 * * * *) - 설정 가능하도록 변경
    @Scheduled(cron = "\${app.scheduling.upload-cron:0 0 * * * *}")
    fun scheduleUploads() {
        println("⏰ Batch Scheduler: Checking Upload Schedule for [$channelId] at ${Date()}")

        // 1. Determine Upload Interval
        // Stocks, History -> 1 per day (24 hours min interval, or just once per calendar day?)
        // Science, Horror -> 1 per hour
        val minIntervalHours = when(channelId) {
            "stocks", "history" -> 24L
            else -> 1L
        }

        // 2. Check Last Upload Time
        val lastUploaded = videoHistoryRepository.findFirstByChannelIdAndStatusOrderByUpdatedAtDesc(channelId, VideoStatus.UPLOADED)
        val now = java.time.LocalDateTime.now()
        
        if (lastUploaded != null) {
            val hoursSinceLastUpload = java.time.temporal.ChronoUnit.HOURS.between(lastUploaded.updatedAt, now)
            if (hoursSinceLastUpload < minIntervalHours) {
                println("⏳ [$channelId] Upload skipped. Last upload was $hoursSinceLastUpload hours ago (Min Interval: $minIntervalHours hrs).")
                return
            }
        } else {
             println("🆕 [$channelId] No previous uploads found. Proceeding with first upload.")
        }

        // 3. Find Next Ready Video (FIFO)
        // Find oldest COMPLETED video
        val nextVideo = videoHistoryRepository.findAllByChannelIdOrderByCreatedAtDesc(
            channelId, 
            org.springframework.data.domain.PageRequest.of(0, 100) // Sort Descending to get list, then we pick Last (Oldest)?? 
            // Better: Find findFirstByChannelIdAndStatusOrderByCreatedAtAsc
        )
        // Since we don't have 'findFirstBy...Asc' readily exposed in repo snippets above, let's look at available methods.
        // We can fetch list by findAllByChannelIdOrderByCreatedAtDesc and take the LAST one.
        
        // Let's add specific method to repo if needed, OR use existing.
        // Assuming we can use streams or just add the method.
        // Let's check 'videoHistoryRepository' methods.
        // We have 'findAllByChannelIdOrderByCreatedAtDesc'.
        // So the last item is the oldest.
        
        val completedVideos = videoHistoryRepository.findByChannelIdAndStatus(channelId, VideoStatus.COMPLETED)
            .sortedBy { it.createdAt } // Oldest first
        
        if (completedVideos.isNotEmpty()) {
            val videoToUpload = completedVideos.first()
            println("🚀 [$channelId] Triggering Upload for: ${videoToUpload.title}")
            
            // Publish Upload Requested Event
            kafkaEventPublisher.publishUploadRequested(
                com.sciencepixel.event.UploadRequestedEvent(
                    channelId = channelId,
                    videoId = videoToUpload.id!!,
                    title = videoToUpload.title,
                    filePath = videoToUpload.filePath
                )
            )
            
            // Optimistic Update to prevent double scheduling if frequent checks?
            // VideoUploadConsumer will handle Locking (UPLOADING status).
            
        } else {
             println("📉 [$channelId] No COMPLETED videos ready for upload.")
        }
    }
}
