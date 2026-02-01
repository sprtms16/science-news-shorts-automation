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
    private val kafkaEventPublisher: com.sciencepixel.event.KafkaEventPublisher
) {

    // 매 10분마다 실행 (0, 10, 20, 30, 40, 50분)
    @Scheduled(cron = "0 0/10 * * * *")
    fun runBatchJobIfNeeded() {
        println("⏰ Batch Scheduler: Checking generation buffer at ${Date()}")

        // 1. Pre-Cleanup: 1시간 이상 경과한 '작업 중' 레코드 삭제
        try {
            cleanupService.cleanupStaleJobs()
        } catch (e: Exception) {
            println("⚠️ Stale Job Cleanup Warning: ${e.message}")
        }

        // 2. Get Limit from Settings (Default 10)
        val limit = systemSettingRepository.findById("MAX_GENERATION_LIMIT")
            .map { it.value.toIntOrNull() ?: 10 }
            .orElse(10)

        // 3. Count Active/Pending videos (Include COMPLETED but exclude UPLOADED and permanent failures)
        val excludedStatuses = listOf(
            VideoStatus.UPLOADED, 
            VideoStatus.FAILED
        )
        val activeCount = videoHistoryRepository.findByStatusNotIn(excludedStatuses).size

        println("📊 Active/Pending Video Buffer: $activeCount / $limit")

        if (activeCount < limit) {
            println("🚀 Buffer low. Triggering Batch Job...")
            try {
                val remaining = Math.max(0, limit - activeCount)
                val params = JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .addLong("remainingSlots", remaining.toLong())
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
        
        val failedVideos = videoHistoryRepository.findByStatus(VideoStatus.FAILED)
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
                println("🔄 [Auto-Recovery] Triggering full regeneration for: ${video.title} (Step: ${video.failureStep})")
                kafkaEventPublisher.publishRegenerationRequested(
                    com.sciencepixel.event.RegenerationRequestedEvent(
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
}
