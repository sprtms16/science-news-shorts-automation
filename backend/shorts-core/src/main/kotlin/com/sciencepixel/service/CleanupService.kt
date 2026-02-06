package com.sciencepixel.service

import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.domain.VideoStatus
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class CleanupService(
    private val repository: VideoHistoryRepository,
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
     * Identify and delete video files that have no corresponding record in the database
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
}
