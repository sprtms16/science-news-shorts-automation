package com.sciencepixel.service

import com.sciencepixel.config.ChannelBehavior
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
    private val channelBehavior: ChannelBehavior,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    // 매 10분마다 실행 (0, 10, 20, 30, 40, 50분)
    @Scheduled(cron = "\${app.scheduling.batch-cron:0 0/10 * * * *}")
    fun runScheduledBatch() {
        triggerBatchJob(force = false)
    }

    fun triggerBatchJob(force: Boolean = false) {
        println("⏰ Batch Scheduler: Checking generation buffer at ${Date()} (Force: $force)")

        // 1. Pre-Cleanup: 1시간 이상 경과한 '작업 중' 레코드 삭제
        try {
            cleanupService.cleanupStaleJobs()
        } catch (e: Exception) {
            println("⚠️ Stale Job Cleanup Warning: ${e.message}")
        }

        // 2. Get Limit from Settings (Default 10)
        val limit = systemSettingRepository.findByChannelIdAndKey(channelId, "MAX_GENERATION_LIMIT")
            ?.value?.toIntOrNull() ?: 10
        
        // Daily Limit Check using ChannelBehavior
        if (!force && channelBehavior.dailyLimit == 1) {
            val startOfDay = java.time.LocalDate.now().atStartOfDay()
            val todayCount = videoHistoryRepository.findAllByChannelIdOrderByCreatedAtDesc(channelId, org.springframework.data.domain.PageRequest.of(0, 100))
                .count { it.createdAt.isAfter(startOfDay) }
            
            if (todayCount >= channelBehavior.dailyLimit) {
                println("🛑 [$channelId] Daily Limit Reached (Generated: $todayCount). Strict ${channelBehavior.dailyLimit}-per-day rule applied. (Use /manual/trigger to bypass)")
                return
            }
        }

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
        if (!force && failedCount >= limit) {
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

            // Async Flow using ChannelBehavior
            if (channelBehavior.useAsyncFlow) {
                println("📡 [BatchScheduler] Triggering Async Discovery via Kafka for $channelId...")
                kafkaEventPublisher.publishStockDiscoveryRequested(
                    com.sciencepixel.event.StockDiscoveryRequestedEvent(channelId)
                )
                return 
            }

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

    // 매시 5분에 실패한 영상 재시도 체크 - 비활성화 (Kafka 자동화로 대체)
    // @Scheduled(cron = "0 5 * * * *")
    fun retryFailedGenerations() {
        // Deprecated: Replaced by recoverFailedJobs
    }

    // Phase 8: Generation Recovery & Upload Timeout (Every 10 mins)
    @Scheduled(cron = "0 0/10 * * * *")
    fun recoverFailedJobs() {
        if (channelBehavior.shouldSkipGeneration()) return 

        recoverFailedGenerations()
        recoverStuckUploads()
    }

    private fun recoverFailedGenerations() {
        // Find FAILED jobs (excluding upload errors, safety errors treated as permanent fail generally but checking policy)
        // User request: "10분 단위로 실패 영상을 복구 시도 하되... 최대 파티션 수만큼"
        val failedVideos = videoHistoryRepository.findTop5ByChannelIdAndStatusOrderByUpdatedAtAsc(channelId, VideoStatus.FAILED)
        
        failedVideos.filter { it.failureStep != "UPLOAD_FAIL" && it.failureStep != "SAFETY" && (it.regenCount ?: 0) < 3 }
            .forEach { video ->
                println("🔄 [$channelId] [Auto-Recovery] Triggering generation retry (${(video.regenCount ?: 0) + 1}/3) for: ${video.title}")
                
                // Re-publish to RSS Topic to restart from Gemin
                kafkaEventPublisher.publishRssNewItem(
                     com.sciencepixel.event.RssNewItemEvent(
                         channelId = channelId,
                         title = video.title ?: "Untitled",
                         url = video.link ?: "",
                         summary = video.summary ?: ""
                     )
                )

                videoHistoryRepository.save(video.copy(
                    regenCount = (video.regenCount ?: 0) + 1,
                    status = VideoStatus.QUEUED, // Reset to QUEUED to pass ScriptConsumer check
                    failureStep = "",
                    errorMessage = "",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
    }

    private fun recoverStuckUploads() {
        val thirtyMinutesAgo = java.time.LocalDateTime.now().minusMinutes(30)
        
        // Find Stuck Uploads (UPLOADING for > 30 mins)
        val stuckUploads = videoHistoryRepository.findByChannelIdAndStatusAndUpdatedAtBefore(
            channelId, VideoStatus.UPLOADING, thirtyMinutesAgo
        )

        if (stuckUploads.isNotEmpty()) {
            println("⚠️ [$channelId] Found ${stuckUploads.size} stuck uploads. Marking as UPLOAD_FAILED.")
            stuckUploads.forEach { video ->
                videoHistoryRepository.save(video.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "UPLOAD_FAIL",
                    errorMessage = "Upload Timeout (>30min)",
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
