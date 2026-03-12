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
import java.time.LocalDateTime
import java.time.ZoneId

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

    companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** Statuses counted toward the daily generation limit. */
        val DAILY_LIMIT_STATUSES = listOf(
            VideoStatus.SCRIPTING,
            VideoStatus.ASSETS_QUEUED,
            VideoStatus.RENDER_QUEUED,
            VideoStatus.RENDERING,
            VideoStatus.COMPLETED,
            VideoStatus.UPLOADING,
            VideoStatus.UPLOADED
        )

        /** Statuses representing an active pipeline slot. */
        val ACTIVE_PIPELINE_STATUSES = listOf(
            VideoStatus.QUEUED,
            VideoStatus.SCRIPTING,
            VideoStatus.ASSETS_QUEUED,
            VideoStatus.ASSETS_GENERATING,
            VideoStatus.RENDER_QUEUED,
            VideoStatus.RENDERING,
            VideoStatus.RETRY_QUEUED,
            VideoStatus.COMPLETED,
            VideoStatus.UPLOADING
        )

        /** Statuses indicating active processing (for retry concurrency). */
        val PROCESSING_STATUSES = listOf(
            VideoStatus.SCRIPTING,
            VideoStatus.ASSETS_QUEUED,
            VideoStatus.ASSETS_GENERATING,
            VideoStatus.RENDER_QUEUED,
            VideoStatus.RENDERING
        )
    }

    /** TZ-correct daily limit check (single count query). */
    private fun isDailyLimitReached(): Boolean {
        val startOfDay = java.time.LocalDate.now(SEOUL_ZONE).atStartOfDay()
        val dailyCount = videoHistoryRepository.countByChannelIdAndStatusInAndCreatedAtAfter(
            channelId, DAILY_LIMIT_STATUSES, startOfDay
        )
        return dailyCount >= channelBehavior.dailyLimit
    }

    // 매 10분마다 실행 (0, 10, 20, 30, 40, 50분)
    @Scheduled(cron = "\${app.scheduling.batch-cron:0 0/10 * * * *}")
    fun runScheduledBatch() {
        triggerBatchJob(force = false)
    }

    fun triggerBatchJob(force: Boolean = false) {
        println("⏰ Batch Scheduler: Checking generation buffer at ${LocalDateTime.now(SEOUL_ZONE)} (Force: $force)")

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
        if (!force && channelBehavior.dailyLimit == 1 && isDailyLimitReached()) {
            println("🛑 [$channelId] Daily Limit Reached. Strict ${channelBehavior.dailyLimit}-per-day rule applied. (Use /manual/trigger to bypass)")
            return
        }

        val activeCount = videoHistoryRepository.countByChannelIdAndStatusIn(channelId, ACTIVE_PIPELINE_STATUSES)
        val failedCount = videoHistoryRepository.countByChannelIdAndStatus(channelId, VideoStatus.FAILED)

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

    // Phase 8: Generation Recovery & Upload Timeout (Every 10 mins)
    @Scheduled(cron = "0 0/10 * * * *")
    fun recoverFailedJobs() {
        if (channelBehavior.shouldSkipGeneration()) return

        recoverFailedGenerations()
        recoverOrphanedQueuedJobs() // Added: Rescue stuck QUEUED items
        processRetryQueue()
        recoverStuckUploads()
        recoverMissedDailyBatch()
    }

    // Phase 46: Paced Auto-Retry for persistent failures (Every 30 mins)
    @Scheduled(cron = "0 0/30 * * * *")
    fun pacedAutoRetryFailedJobs() {
        if (channelBehavior.shouldSkipGeneration()) return

        println("⏰ [$channelId] Starting Paced Auto-Retry Check (30 min interval)...")

        // 1시간 이상 경과한 FAILED 영상 중 가장 오래된 건 1개 추출
        val oneHourAgo = LocalDateTime.now().minusHours(1)
        val targetVideo = videoHistoryRepository.findFirstByChannelIdAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            channelId,
            VideoStatus.FAILED,
            oneHourAgo
        )
        if (targetVideo == null || targetVideo.failureStep == "SAFETY" || targetVideo.failureStep == "DUPLICATE") {
            return
        }

        // Check Daily Limit before trigger
        if (isDailyLimitReached()) {
            println("⏳ [$channelId] [Paced-Auto-Retry] Skipping - Daily Limit Reached.")
            return
        }

        println("🔄 [$channelId] [Paced-Auto-Retry] Rescuing persistent failure: ${targetVideo.title}")

        videoHistoryRepository.save(targetVideo.copy(
            status = VideoStatus.RETRY_QUEUED,
            errorMessage = "Auto-rescued after 1hr persistent failure (Paced Retry)",
            updatedAt = LocalDateTime.now()
        ))
    }

    // New: 당일 배치가 정상적으로 수행되지 않아 생성된 영상이 0건일 때 강제로 배치를 재트리거
    private fun recoverMissedDailyBatch() {
        when(channelId) {
            "stocks", "history" -> { /* daily channels only */ }
            else -> return // science, horror 등은 시간 단위로 돔
        }

        // 채널별 배치 시간 이후에만 체크 (배치 + 2시간 여유)
        val batchHour = when(channelId) {
            "history" -> 6   // 06:30 배치
            "stocks" -> 17   // 17:30 배치
            else -> return
        }

        val nowKst = java.time.ZonedDateTime.now(SEOUL_ZONE)
        if (nowKst.hour < batchHour + 2) return  // 배치 시간 + 2시간 전이면 스킵

        val startOfDay = nowKst.toLocalDate().atStartOfDay()

        // FAILED를 제외한 유효 생성 혹은 진행 중인 항목 검사 (count query)
        val validCount = videoHistoryRepository.countByChannelIdAndStatusInAndCreatedAtAfter(
            channelId, DAILY_LIMIT_STATUSES, startOfDay
        )

        if (validCount == 0L) {
            println("⚠️ [$channelId] No valid generations found today despite being past batch hour ($batchHour:30 KST + 2h). Missed daily batch? Triggering automatically...")
            triggerBatchJob(force = false)
        }
    }

    // New: Rescue Orphaned QUEUED items (Stuck > 1 hour)
    private fun recoverOrphanedQueuedJobs() {
        // Find items stuck in QUEUED for more than 1 hour
        // This handles cases where Batch created the record but Kafka event was lost or Consumer failed
        val oneHourAgo = LocalDateTime.now().minusHours(1)

        val stuckQueued = videoHistoryRepository.findByChannelIdAndStatusAndUpdatedAtBefore(
            channelId,
            VideoStatus.QUEUED,
            oneHourAgo
        )

        if (stuckQueued.isNotEmpty()) {
            println("⚠️ [$channelId] Found ${stuckQueued.size} stuck QUEUED items (>1hr). Re-publishing events...")

            // Check daily limit once before the loop (avoid N repeated queries)
            val limitReached = channelBehavior.dailyLimit <= 10 && isDailyLimitReached()

            stuckQueued.forEach { video ->
                if (limitReached) {
                    println("⏳ [$channelId] [Rescue] Skipping ${video.title} - Daily Limit Reached. Will retry tomorrow.")
                    return@forEach
                }

                println("🔄 [$channelId] [Rescue] Re-publishing NEW_ITEM event for: ${video.title}")
                kafkaEventPublisher.publishRssNewItem(com.sciencepixel.event.RssNewItemEvent(
                    channelId = channelId,
                    title = video.title,
                    url = video.link,
                    summary = video.summary ?: ""
                ))

                // Touch updatedAt to prevent immediate re-trigger
                videoHistoryRepository.save(video.copy(updatedAt = LocalDateTime.now()))
            }
        }
    }

    // 4. Retry Logic: FAILED -> RETRY_QUEUED
    private fun recoverFailedGenerations() {
        // Find FAILED jobs
        val failedVideos = videoHistoryRepository.findTop5ByChannelIdAndStatusOrderByUpdatedAtAsc(channelId, VideoStatus.FAILED)
        if (failedVideos.isEmpty()) return

        // Check Daily Limit
        if (isDailyLimitReached()) return

        failedVideos.filter { it.failureStep != "UPLOAD_FAIL" && it.failureStep != "SAFETY" && it.failureStep != "DUPLICATE" && (it.regenCount ?: 0) < 3 }
            .forEach { video ->
                // Fundamental Check: Is there already a successful version?
                val alreadyExists = videoHistoryRepository.existsByChannelIdAndLinkAndStatusIn(
                    channelId,
                    video.link ?: "",
                    listOf(VideoStatus.COMPLETED, VideoStatus.UPLOADED, VideoStatus.UPLOADING)
                )

                if (alreadyExists) {
                    println("⏭️ [$channelId] [Auto-Recovery] Skipping retry for '${video.title}' - A completed version already exists.")
                    videoHistoryRepository.save(video.copy(
                        status = VideoStatus.FAILED,
                        failureStep = "DUPLICATE",
                        errorMessage = "Duplicate of existing completed video",
                        updatedAt = LocalDateTime.now()
                    ))
                    return@forEach
                }

                println("⏳ [$channelId] [Recovery] Moving to RETRY_QUEUED: ${video.title}")
                videoHistoryRepository.save(video.copy(
                    status = VideoStatus.RETRY_QUEUED,
                    updatedAt = LocalDateTime.now()
                ))
            }
    }

    // 5. Retry Dispatcher: RETRY_QUEUED -> SCRIPTING (Throttle)
    private fun processRetryQueue() {
        val retries = videoHistoryRepository.findByChannelIdAndStatus(channelId, VideoStatus.RETRY_QUEUED)
        if (retries.isEmpty()) return

        // Count currently active regenerations (Active items with regenCount > 0)
        // using SCRIPTING/ASSETS/RENDERING status implies active processing.
        val activeRegens = videoHistoryRepository.findByChannelIdAndStatusIn(channelId, PROCESSING_STATUSES)
            .count { it.regenCount > 0 }

        // Define Partition Limit (e.g., 5 concurrent retries)
        val maxConcurrentRetries = 5

        if (activeRegens >= maxConcurrentRetries) {
            println("🛑 [$channelId] Retry Concurrency Limit Reached ($activeRegens/$maxConcurrentRetries). Waiting...")
            return
        }

        val slotsAvailable = maxConcurrentRetries - activeRegens
        retries.take(slotsAvailable).forEach { video ->
             println("🔄 [$channelId] [Retry-Dispatch] Starting generation retry (${(video.regenCount ?: 0) + 1}/3) for: ${video.title}")

             // Re-publish to RSS Topic to restart from Gemini
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
                 status = VideoStatus.QUEUED, // FIX: Set to QUEUED so ScriptConsumer can claim it (was SCRIPTING)
                 failureStep = "",
                 errorMessage = "",
                 updatedAt = LocalDateTime.now()
             ))
        }
    }

    private fun recoverStuckUploads() {
        val thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30)

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
                    updatedAt = LocalDateTime.now()
                ))
            }
        }
    }
}
