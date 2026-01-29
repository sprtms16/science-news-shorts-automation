package com.sciencepixel.service

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
    private val cleanupService: CleanupService
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

        // 3. Count Active/Pending videos (Exclude UPLOADED and those waiting for upload)
        val activeVideos = videoHistoryRepository.findAll().filter { 
            it.status != "UPLOADED" && it.status != "COMPLETED" && it.status != "QUOTA_EXCEEDED" && it.status != "RETRY_PENDING"
        }
        val activeCount = activeVideos.size

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
}
