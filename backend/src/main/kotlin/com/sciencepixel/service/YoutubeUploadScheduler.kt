package com.sciencepixel.service

import com.sciencepixel.domain.SystemSetting
import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoHistory
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
    private val youtubeService: YoutubeService,
    private val productionService: ProductionService,
    private val systemSettingRepository: SystemSettingRepository,
    private val notificationService: NotificationService
) {
    
    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val MAX_REGEN_COUNT = 1  // 재생성은 1회만 시도
    }

    // 매 시간 정각에 실행 ("0 0 * * * *")
    // 또한 앱 시작 직후(준비 완료 시)에도 실행
    @Scheduled(cron = "0 0 * * * *")
    @EventListener(ApplicationReadyEvent::class)
    @Async
    fun uploadPendingVideos() {
        println("⏰ Scheduler Triggered: Checking for pending videos at ${java.time.LocalDateTime.now()}")

        // 1. Check if Upload is Blocked (Quota Exceeded)
        val blockedSetting = systemSettingRepository.findById("UPLOAD_BLOCKED_UNTIL").orElse(null)
        if (blockedSetting != null) {
            if (blockedSetting.value.isBlank()) {
                println("⚠️ Upload Block setting is empty. Deleting invalid setting.")
                systemSettingRepository.delete(blockedSetting)
            } else {
                try {
                    val blockedUntil = java.time.LocalDateTime.parse(blockedSetting.value)
                    if (java.time.LocalDateTime.now().isBefore(blockedUntil)) {
                        println("⛔ Upload is BLOCKED until $blockedUntil due to Quota Exceeded.")
                        return
                    } else {
                        // Block expired, remove setting
                        systemSettingRepository.delete(blockedSetting)
                        println("🟢 Upload Block expired. Resuming uploads.")
                    }
                } catch (e: Exception) {
                    println("❌ Failed to parse UPLOAD_BLOCKED_UNTIL (${blockedSetting.value}): ${e.message}. Deleting invalid setting.")
                    systemSettingRepository.delete(blockedSetting)
                }
            }
        }
        
        // 디버깅용: 전체 상태 카운트 출력
        val allVideos = repository.findAll()
        val statusCounts = allVideos.groupingBy { it.status }.eachCount()
        println("📊 Current Video Statuses: $statusCounts")

        // COMPLETED, RETRY_PENDING 또는 QUOTA_EXCEEDED 상태의 비디오를 처리
        val pendingVideos = allVideos.filter { 
            it.status == "COMPLETED" || it.status == "RETRY_PENDING" || it.status == "QUOTA_EXCEEDED"
        }.sortedBy { it.createdAt } // 오래된 순으로 처리
        
        println("📦 Found ${pendingVideos.size} pending videos.")

        // 최대 3개까지 시도 (하나가 막혀도 다음 걸 시도하도록)
        val targetVideos = pendingVideos.take(3)

        if (targetVideos.isEmpty()) {
            println("✅ No pending videos to upload.")
            return
        }

        for (video in targetVideos) {
            val isSuccess = processVideoUpload(video)
            // 쿼터 초과 시에는 즉시 중단
            if (!isSuccess && isQuotaExceededStatus()) {
                println("🛑 Quota exceeded detected. Stopping current upload batch.")
                break
            }
        }
    }

    private fun isQuotaExceededStatus(): Boolean {
        return systemSettingRepository.existsById("UPLOAD_BLOCKED_UNTIL")
    }

    private fun processVideoUpload(video: VideoHistory): Boolean {
        try {
            // 1. Data Integrity Check
            if (video.title.isBlank() || video.filePath.isBlank()) {
                println("⚠️ Skipping invalid video record (Missing title/file): ${video.id}")
                handleBrokenVideo(video)
                return false
            }

            println("🚀 Uploading to YouTube: ${video.title}")
            val file = File(video.filePath)
            
            if (file.exists() && file.length() > 1024 * 1024) { // 최소 1MB 체크
                val tags = if (video.tags.isNullOrEmpty()) listOf("Science", "News", "Shorts") else video.tags
                val videoId = youtubeService.uploadVideo(
                    file, 
                    video.title, 
                    "${video.description ?: video.summary}\n\n#Science #News #Shorts", 
                    tags
                )
                
                // Update Status
                val updated = video.copy(
                    status = "UPLOADED",
                    youtubeUrl = videoId,
                    retryCount = 0
                )
                repository.save(updated)

                try {
                    notificationService.notifyUploadComplete(video.title, videoId)
                } catch (e: Exception) {
                    println("⚠️ Failed to send Discord notification for scheduler upload: ${e.message}")
                }

                println("✅ Upload Success: ${updated.youtubeUrl}")
                return true
            } else {
                println("⚠️ File issues detected (Length: ${if(file.exists()) file.length() else -1})")
                handleFileNotFound(video)
                return false
            }

        } catch (e: Exception) {
            println("❌ Upload Failed for '${video.title}': ${e.message}")
            
            if (e.message?.contains("quota") == true || e.message?.contains("403") == true) {
                // ... (Block logic)
                markQuotaExceeded()
                return false
            } else {
                val errorVideo = video.copy(
                    status = "ERROR",
                    summary = video.summary + "\nUpload Error: ${e.message}"
                )
                repository.save(errorVideo)
                return false
            }
        }
    }

    private fun markQuotaExceeded() {
        println("⛔ Quota Exceeded. Blocking uploads until next reset (Tomorrow 17:00 KST).")
        val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        val nextReset = if (now.hour >= 17) {
            now.plusDays(1).withHour(17).withMinute(0).withSecond(0)
        } else {
            now.withHour(17).withMinute(0).withSecond(0)
        }
        
        systemSettingRepository.save(SystemSetting(
            key = "UPLOAD_BLOCKED_UNTIL",
            value = nextReset.toString(),
            description = "Blocked due to YouTube Quota Exceeded"
        ))
    }

    private fun handleBrokenVideo(video: VideoHistory) {
        println("🛠️ Attempting to fix broken video record: ${video.title}")
        if (video.regenCount < MAX_REGEN_COUNT) {
            triggerRegeneration(video)
        } else {
            repository.save(video.copy(status = "ERROR", summary = video.summary + "\n[System] Marked as ERROR due to lack of title/file."))
        }
    }

    private fun handleFileNotFound(video: VideoHistory) {
         val currentRetry = video.retryCount
         if (currentRetry < MAX_RETRY_COUNT) {
             println("⏳ File not found (Retry ${currentRetry + 1}/$MAX_RETRY_COUNT): ${video.filePath}")
             repository.save(video.copy(
                 status = "RETRY_PENDING",
                 retryCount = currentRetry + 1
             ))
         } else {
             println("❌ File not found after $MAX_RETRY_COUNT retries: ${video.filePath}")
             // 재생성 시도
             triggerRegeneration(video)
         }
    }
    

    
    // 비디오 재생성 로직
    private fun triggerRegeneration(video: VideoHistory) {
        val regenCount = video.regenCount
        
        if (regenCount >= MAX_REGEN_COUNT) {
            println("🚫 Max regeneration attempts reached for: ${video.title}")
            repository.save(video.copy(status = "REGEN_FAILED"))
            return
        }
        
        println("🔄 Attempting video regeneration (${regenCount + 1}/$MAX_REGEN_COUNT): ${video.title}")
        
        try {
            // 상태를 REGENERATING으로 변경
            repository.save(video.copy(
                status = "REGENERATING",
                regenCount = regenCount + 1,
                retryCount = 0
            ))
            
            // NewsItem 생성 및 비디오 재생성
            val newsItem = NewsItem(
                title = video.title,
                summary = video.summary,
                link = video.link
            )
            
            val result = productionService.produceVideo(newsItem)
            val newFilePath = result.filePath
            
            if (newFilePath.isNotBlank()) {
                println("✅ Regeneration successful: $newFilePath")
                repository.save(video.copy(
                    status = "COMPLETED",
                    filePath = newFilePath,
                    retryCount = 0,
                    regenCount = regenCount + 1
                ))
            } else {
                println("❌ Regeneration failed: Empty file path")
                repository.save(video.copy(status = "REGEN_FAILED"))
            }
            
        } catch (e: Exception) {
            println("❌ Regeneration error: ${e.message}")
            e.printStackTrace()
            repository.save(video.copy(status = "REGEN_FAILED"))
        }
    }
}
