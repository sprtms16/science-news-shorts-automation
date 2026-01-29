package com.sciencepixel.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import com.sciencepixel.service.GeminiService
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.domain.VideoHistory
import java.io.File

@Component
class CleanupScheduler(
    private val cleanupService: CleanupService,
    private val videoRepository: VideoHistoryRepository,
    private val geminiService: GeminiService
) {
    /**
     * 리소스 정리 스케줄러
     * 매 시간 30분에 실행 (업로드 배치 후 실행 목적)
     * 리소스 정리 및 안전 검사 스케줄러
     * 매 30분마다 실행 ("0 0/30 * * * *")
     * 1. 업로드 완료된 비디오 파일 삭제
     * 2. 오래된 임시 작업 폴더 삭제
     * 3. 대기 중인 영상에 대한 AI 안전 검사 (Retroactive Safety Check)
     */
    @Scheduled(cron = "0 0/30 * * * *")
    fun runCleanup() {
        println("🧹 Cleanup & Safety Scheduler Triggered...")
        
        // 1. Regular Cleanup
        cleanupService.cleanupUploadedVideos()
        cleanupService.cleanupFailedVideos()
        cleanupService.cleanupOldWorkspaces()
        
        // 2. Periodic Safety Check (Pending Videos)
        runSafetyCheck()
        
        println("✅ Cleanup & Safety Finished.")
    }

    private fun runSafetyCheck() {
        // Check COMPLETED and PENDING videos (not UPLOADED)
        val targetVideos = videoRepository.findAll().filter { 
            it.status == "COMPLETED" || it.status.contains("PENDING") 
        }

        if (targetVideos.isEmpty()) return

        println("🛡️ Starting Recurring Safety Check for ${targetVideos.size} items...")
        var removedCount = 0

        targetVideos.forEach { video: VideoHistory ->
            // Skip processing if no content to check
            if (video.title.isNotBlank() && video.summary.isNotBlank()) {
                if (!geminiService.checkSensitivity(video.title, video.summary)) {
                    println("⛔ Recursive Safety Check: UNSAFE detected -> Removing '${video.title}'")
                    try {
                        if (video.filePath.isNotBlank()) {
                            File(video.filePath).delete()
                        }
                        videoRepository.delete(video)
                        removedCount++
                    } catch (e: Exception) {
                        println("❌ Error removing unsafe video: ${e.message}")
                    }
                }
            }
        }
        if (removedCount > 0) println("🛡️ Safety Check: Removed $removedCount unsafe videos.")
    }
}
