package com.sciencepixel.service

import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.repository.PexelsVideoCacheRepository
import com.sciencepixel.domain.VideoStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class CleanupService(
    private val repository: VideoHistoryRepository,
    private val pexelsCacheRepository: PexelsVideoCacheRepository,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    private val sharedDataPath = "shared-data"

    fun cleanupUploadedVideos() {
        println("🧹 [$channelId] Starting cleanup of UPLOADED videos...")
        val uploadedVideos = repository.findByChannelIdAndStatus(channelId, VideoStatus.UPLOADED)
            .filter { !it.filePath.isNullOrBlank() }

        if (uploadedVideos.isEmpty()) {
            println("✅ No uploaded videos to clean up.")
            return
        }

        // Increase safety threshold to 48 hours to allow for manual checks/sharing
        val cleanupThreshold = System.currentTimeMillis() - (48 * 60 * 60 * 1000) 
        var deletedCount = 0
        
        uploadedVideos.forEach { video ->
            try {
                // STRONG GUARD: Double check status is UPLOADED (not changed to something else concurrently)
                val currentVideo = repository.findById(video.id!!).orElse(null)
                if (currentVideo == null || currentVideo.status != VideoStatus.UPLOADED) {
                    println("🛡️ Skipping cleanup for ${video.id} (Status changed or not UPLOADED)")
                    return@forEach
                }

                val file = File(video.filePath)
                if (file.exists()) {
                    if (file.lastModified() < cleanupThreshold) {
                        if (file.delete()) {
                            println("🗑️ Deleted file for '${video.title}': ${file.path}")
                            deletedCount++
                            repository.save(video.copy(filePath = "", description = video.description + "\n[System] Resource cleaned up at ${LocalDateTime.now()}", updatedAt = LocalDateTime.now()))
                        } else {
                            println("⚠️ Failed to delete file: ${file.path}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("❌ Error cleaning up video '${video.title}': ${e.message}")
            }
        }
        println("✅ Cleanup complete. Deleted $deletedCount video files.")
    }

    // ... (cleanupFailedVideos kept as is or similar safety)

    /**
     * Delete a specific video file manually (e.g. via Admin API)
     */
    fun deleteVideoFile(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        val file = File(filePath)
        val allowedBase = File(sharedDataPath).canonicalPath
        if (!file.canonicalPath.startsWith(allowedBase)) {
            println("🛡️ [Security] Blocked deletion outside shared-data: ${file.canonicalPath}")
            return false
        }
        if (file.exists()) {
            println("🗑️ [Manual/Admin] Deleting file: ${file.absolutePath}")
            return file.delete()
        }
        return false
    }

    fun cleanupFailedVideos() {
        println("🧹 [$channelId] Cleaning up FAILED video files...")
        val failedVideos = repository.findByChannelIdAndStatus(channelId, VideoStatus.FAILED)
        
        // Threshold: 24 hours for failed videos
        val threshold = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        var count = 0

        failedVideos.forEach { video ->
            if (video.filePath.isNotBlank()) {
                val file = File(video.filePath)
                if (file.exists() && file.lastModified() < threshold) {
                    if (file.delete()) {
                        println("🗑️ Deleted FAILED video file: ${file.name}")
                        repository.save(video.copy(filePath = ""))
                        count++
                    }
                }
            }
        }
        println("✅ Failed Video Cleanup: Processed $count files.")
    }

    fun cleanupOldWorkspaces() {
        println("🧹 [$channelId] Cleaning up old workspaces...")
        val workspaceDir = File("workspace/$channelId") // Assuming this path structure based on previous context
        if (!workspaceDir.exists()) return

        // Threshold: 3 days for workspaces
        val threshold = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000)
        var count = 0

        workspaceDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.lastModified() < threshold) {
                // Check if it corresponds to an active/uploaded video?
                // For simplicity, just delete old workspaces that haven't been touched.
                // Or maybe strictly by age.
                if (dir.deleteRecursively()) {
                    count++
                }
            }
        }
        println("✅ Workspace Cleanup: Removed $count old directories.")
    }
    
    fun cleanupAiBgm() {
        println("🧹 [$channelId] Cleaning up temporary AI BGM...")
        val bgmDir = File(sharedDataPath, "bgm/$channelId")
        if (!bgmDir.exists()) return

        val threshold = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        var count = 0

         bgmDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < threshold) {
                if (file.delete()) count++
            }
        }
        println("✅ BGM Cleanup: Removed $count temp files.")
    }

    /**
     * Clean up old Pexels video cache (unused for 30+ days)
     * Runs daily at 3 AM
     */
    @Scheduled(cron = "0 0 3 * * *")
    fun cleanupOldPexelsCache() {
        println("🧹 Cleaning up old Pexels cache (unused for 30+ days)...")
        val threshold = LocalDateTime.now().minusDays(30)
        val oldVideos = pexelsCacheRepository.findByLastUsedAtBefore(threshold)

        var deletedCount = 0
        var deletedSize = 0L

        oldVideos.forEach { cached ->
            try {
                val file = File(cached.filePath)
                if (file.exists()) {
                    val fileSize = file.length()
                    if (file.delete()) {
                        pexelsCacheRepository.delete(cached)
                        deletedCount++
                        deletedSize += fileSize
                        println("🗑️ Deleted cached video: ${cached.pexelsVideoId} (${cached.keyword})")
                    } else {
                        println("⚠️ Failed to delete cache file: ${file.absolutePath}")
                    }
                } else {
                    // File doesn't exist, remove DB entry anyway
                    pexelsCacheRepository.delete(cached)
                    deletedCount++
                    println("🗑️ Removed orphaned cache DB entry: ${cached.pexelsVideoId}")
                }
            } catch (e: Exception) {
                println("❌ Error cleaning up cache '${cached.pexelsVideoId}': ${e.message}")
            }
        }

        val deletedSizeMB = String.format("%.2f", deletedSize / 1024.0 / 1024.0)
        println("✅ Pexels Cache Cleanup: $deletedCount videos removed (${deletedSizeMB} MB)")
    }

    fun cleanupAllTemporaryFiles() {
        cleanupOrphanedVideos()
        cleanupOldWorkspaces()
        cleanupAiBgm()
        cleanupOldPexelsCache()
    }

    /**
     * Identify and delete video files that have no corresponding record in the database
     * IMPORTANT: This only scans shared-data/videos/$channelId
     * The pexels-cache directory is managed separately by cleanupOldPexelsCache()
     */
    fun cleanupOrphanedVideos() {
        println("🧹 [$channelId] Scanning for orphaned video files in shared-data/videos/$channelId...")
        val videoDir = File(sharedDataPath, "videos/$channelId")
        if (!videoDir.exists() || !videoDir.isDirectory) return

        // Fetch ALL videos to ensure we don't delete false positives
        val allVideos = repository.findByChannelId(channelId)
        val registeredPaths = allVideos.mapNotNull { it.filePath }.filter { it.isNotBlank() }.map { File(it).absolutePath }.toSet()

        // Also protect based on "known filenames" from DB even if path isn't exact match (e.g. relative vs absolute)
        val registeredFilenames = allVideos.mapNotNull { it.filePath }.filter { it.isNotBlank() }.map { File(it).name }.toSet()

        var count = 0
        videoDir.listFiles()?.forEach { file ->
            // SAFETY: Explicitly skip pexels-cache directory (should not be in videos/ but be defensive)
            if (file.absolutePath.contains("pexels-cache")) {
                println("🛡️ Skipping pexels-cache file: ${file.name}")
                return@forEach
            }

            // CRITICAL: NEVER delete if file matches a known DB record's filename (even if path differs slightly)
            val isKnownFile = file.absolutePath in registeredPaths || file.name in registeredFilenames

            if (file.isFile && !isKnownFile) {
                // SAFETY: Increase threshold to 24 hours (was 30 mins)
                // This ensures we never delete a file that is "just being created" or "DB update pending"
                if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                    println("🗑️ Deleting orphaned video (Not in DB + >24h old): ${file.name}")
                    if (file.delete()) count++
                } else {
                     // Log but don't delete
                     // println("🛡️ Skipping potential orphan (too young): ${file.name}")
                }
            }
        }
        println("✅ Orphaned Video Cleanup: Deleted $count files from $channelId folder.")
    }

    fun cleanupStaleJobs() {
        println("🧹 [$channelId] Scanning for stale jobs > 2 hours...")
        val threshold = LocalDateTime.now().minusHours(2)
        val activeStatuses = listOf(
            VideoStatus.SCRIPTING,
            VideoStatus.ASSETS_QUEUED,
            VideoStatus.ASSETS_GENERATING,
            VideoStatus.RENDER_QUEUED,
            VideoStatus.RENDERING
        )

        val activeVideos = repository.findByChannelIdAndStatusIn(channelId, activeStatuses)
        var staleCount = 0

        activeVideos.filter { it.updatedAt.isBefore(threshold) }.forEach { video ->
            println("🚫 [$channelId] Stale Job detected (Updated: ${video.updatedAt}). Marking as FAILED: ${video.title}")
            repository.save(video.copy(
                status = VideoStatus.FAILED,
                failureStep = "STALE_CLEANUP",
                errorMessage = "Job stuck for > 2 hours (Stale Cleanup)",
                updatedAt = LocalDateTime.now()
            ))
            staleCount++
        }
        
        if (staleCount > 0) println("✅ Stale Job Cleanup: Marked $staleCount jobs as FAILED.")
    }
}
