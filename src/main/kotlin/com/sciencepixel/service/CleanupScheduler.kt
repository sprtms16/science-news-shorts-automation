package com.sciencepixel.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CleanupScheduler(
    private val cleanupService: CleanupService
) {
    /**
     * 리소스 정리 스케줄러
     * 매 시간 30분에 실행 (업로드 배치 후 실행 목적)
     * 1. 업로드 완료된 비디오 파일 삭제
     * 2. 오래된 임시 작업 폴더 삭제
     */
    @Scheduled(cron = "0 30 * * * *")
    fun runCleanup() {
        println("🧹 Cleanup Scheduler Triggered: Starting resource cleanup...")
        cleanupService.cleanupUploadedVideos()
        cleanupService.cleanupOldWorkspaces()
        println("✅ Cleanup Scheduler Finished.")
    }
}
