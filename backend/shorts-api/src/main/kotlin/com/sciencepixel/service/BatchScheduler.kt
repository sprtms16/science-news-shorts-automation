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

        // 3. Count Active/Pending videos
        val excludedStatuses = listOf(
            VideoStatus.UPLOADED, 
            VideoStatus.FAILED
        )
        val activeCount = videoHistoryRepository.findByChannelIdAndStatusNotIn(channelId, excludedStatuses).size

        println("📊 Active/Pending Video Buffer: $activeCount / $limit")

        if (activeCount < limit) {
            println("🚀 Buffer low. Triggering Batch Job (Throttled to 1 item)...")
            try {
                // User requested 1 generation per cycle (10 min)
                // Even if we have many slots, we only schedule 1.
                val remaining = 1L 
                
                val params = JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .addLong("remainingSlots", remaining)
                    .toJobParameters()
                
                jobLauncher.run(shortsJob, params)
            } catch (e: Exception) {
                println("❌ Batch Job Launch Failed: ${e.message}")
            }
        } else {
            println("🛑 Buffer Full (>= $limit). Skipping generation.")
        }
    }

    // 매시 5분에 실패한 영상 재시도 체크 (0 5 * * * *)
    @Scheduled(cron = "0 5 * * * *")
    fun retryFailedGenerations() {
        println("⏰ Batch Scheduler: Checking for FAILED videos to retry at ${Date()}")
        
        val failedVideos = videoHistoryRepository.findByChannelIdAndStatus(channelId, VideoStatus.FAILED)
            .filter { it.regenCount < 1 } // 재생성 시도 안 한 것만
        
        if (failedVideos.isNotEmpty()) {
            println("🔄 Found ${failedVideos.size} FAILED videos. Analyzing failure steps...")
            
            failedVideos.take(5).forEach { video ->
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
                println("🔄 [$channelId] [Auto-Recovery] Triggering full regeneration for: ${video.title}")
                kafkaEventPublisher.publishRegenerationRequested(
                    com.sciencepixel.event.RegenerationRequestedEvent(
                        channelId = channelId, // 추가
                        videoId = video.id!!,
                        title = video.title,
                        summary = video.summary,
                        link = video.link,
                        regenCount = video.regenCount
                    )
                )
            }
        }
    }
    // 매시 0분에 업로드 체크 (0 0 * * * *)
    @Scheduled(cron = "0 0 * * * *")
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
