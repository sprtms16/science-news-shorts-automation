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

        val cleanupThreshold = System.currentTimeMillis() - (6 * 60 * 60 * 1000) // 6 hours ago
        var deletedCount = 0
        
        uploadedVideos.forEach { video ->
            try {
                val file = File(video.filePath)
                if (file.exists()) {
                    // Only delete if the file is older than 24 hours
                    if (file.lastModified() < cleanupThreshold) {
                        if (file.delete()) {
                            println("🗑️ Deleted file for '${video.title}': ${file.path}")
                            deletedCount++
                            
                            // Update DB to reflect cleanup (preserve other metadata)
                            repository.save(video.copy(
                                filePath = "", // Clear path to indicate deletion
                                description = video.description + "\n[System] Resource cleaned up at ${LocalDateTime.now()}",
                                updatedAt = LocalDateTime.now()
                            ))
                        } else {
                            println("⚠️ Failed to delete file: ${file.path}")
                        }
                    } else {
                        println("ℹ️ Skipping recently uploaded file (within 24h): ${file.name}")
                    }
                } else {
                    println("⚠️ File not found (already deleted?): ${video.filePath}")
                    // Clean up DB entry even if file is missing if marked as UPLOADED but path is set
                    repository.save(video.copy(
                        filePath = "",
                        updatedAt = LocalDateTime.now()
                    ))
                }
            } catch (e: Exception) {
                println("❌ Error cleaning up video '${video.title}': ${e.message}")
            }
        }
        println("✅ Cleanup complete. Deleted $deletedCount video files.")
    }

    fun cleanupFailedVideos() {
        println("🧹 [$channelId] Starting cleanup of FAILED videos...")
        val failedVideos = repository.findByChannelIdAndStatus(channelId, VideoStatus.FAILED)
            .filter { !it.filePath.isNullOrBlank() }

        if (failedVideos.isEmpty()) {
            println("✅ No failed videos to clean up.")
            return
        }

        val cleanupThreshold = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago
        var deletedCount = 0

        failedVideos.forEach { video ->
            try {
                // If it's a final failure and old enough, delete BOTH file and DB record
                val file = File(video.filePath)
                val isOldEnough = file.exists() && file.lastModified() < cleanupThreshold || !file.exists() 
                
                if (isOldEnough) {
                    if (file.exists()) {
                        file.delete()
                    }
                    repository.save(video.copy(
                        filePath = "",
                        errorMessage = (video.errorMessage + "\n[System] File deleted due to old binary").trim(),
                        updatedAt = LocalDateTime.now()
                    ))
                    println("🚩 Cleaned up file for FAILED video (record preserved): ${video.title}")
                    deletedCount++
                }
            } catch (e: Exception) {
                println("❌ Error cleaning up failed video '${video.title}': ${e.message}")
            }
        }
        println("✅ Failed videos cleanup complete. Removed $deletedCount items.")
    }

    fun deleteVideoFile(filePath: String) {
        if (filePath.isBlank()) return
        
        try {
            val file = File(filePath)
            if (file.exists()) {
                if (file.delete()) {
                    println("🗑️ Manually deleted video file: $filePath")
                } else {
                    println("⚠️ Failed to delete video file: $filePath")
                }
            } else {
                println("⚠️ File not found for deletion: $filePath")
            }
        } catch (e: Exception) {
            println("❌ Error deleting file $filePath: ${e.message}")
        }
    }

    fun cleanupOldWorkspaces() {
        println("🧹 Starting cleanup of old workspace directories...")
        val workspaceRoot = File(sharedDataPath, "workspace")
        
        if (!workspaceRoot.exists() || !workspaceRoot.isDirectory) {
            println("ℹ️ Workspace directory not found or empty: ${workspaceRoot.path}")
            return
        }

        val cleanupThreshold = System.currentTimeMillis() - (2 * 60 * 60 * 1000) // 2 hours ago
        var deletedCount = 0

        // Traverse: workspace -> channelId -> videoId
        workspaceRoot.listFiles()?.filter { it.isDirectory }?.forEach { channelDir ->
            channelDir.listFiles()?.filter { it.isDirectory }?.forEach { videoDir ->
                if (videoDir.lastModified() < cleanupThreshold) {
                    try {
                        println("🗑️ Deleting old workspace: ${channelDir.name}/${videoDir.name}")
                        if (videoDir.deleteRecursively()) {
                            deletedCount++
                        }
                    } catch (e: Exception) {
                        println("❌ Error deleting workspace ${videoDir.name}: ${e.message}")
                    }
                }
            }
            
            // Clean up empty channel directories
            if (channelDir.listFiles()?.isEmpty() == true) {
                channelDir.delete()
            }
        }
        
        // Also clean up legacy workspace_ folders in root just in case
        File(sharedDataPath).listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("workspace_") && file.lastModified() < cleanupThreshold) {
                file.deleteRecursively()
            }
        }
        
        println("✅ Workspace cleanup complete. Removed $deletedCount old directories.")
    }

    /**
     * More aggressive cleanup that deletes ALL folders in workspace/ regardless of age.
     * Use with caution.
     */
    fun cleanupAllTemporaryFiles(): Int {
        println("🧹 AGGRESSIVE: Cleaning up ALL temporary workspaces...")
        val workspaceRoot = File(sharedDataPath, "workspace")
        var count = 0
        if (workspaceRoot.exists()) {
            workspaceRoot.listFiles()?.forEach { channelDir ->
                if (channelDir.isDirectory) {
                    channelDir.listFiles()?.forEach { videoDir ->
                        if (videoDir.deleteRecursively()) count++
                    }
                }
            }
        }
        return count
    }

    fun cleanupStaleJobs() {
        println("🧹 [$channelId] Starting cleanup of STALE jobs (Processing or Uploading for too long)...")
        val now = LocalDateTime.now()
        
        // 1. Stuck in Processing (Gemini, Assets, Render)
        val creatingThreshold = now.minusMinutes(30)
        val processingStatuses = listOf(
            VideoStatus.SCRIPTING, 
            VideoStatus.ASSETS_QUEUED, 
            VideoStatus.ASSETS_GENERATING, 
            VideoStatus.RENDER_QUEUED, 
            VideoStatus.RENDERING
        )
        val staleProcessing = repository.findByChannelIdAndStatusIn(channelId, processingStatuses).filter { 
            it.updatedAt.isBefore(creatingThreshold) 
        }

        // 2. Stuck in UPLOADING (YouTube API/Network issue)
        val uploadingThreshold = now.minusHours(1) // Reduced to 1 hour
        val staleUploading = repository.findByChannelIdAndStatus(channelId, VideoStatus.UPLOADING).filter {
            it.updatedAt.isBefore(uploadingThreshold)
        }

        val totalStale = staleProcessing + staleUploading

        if (totalStale.isEmpty()) {
            println("✅ No stale jobs found.")
            return
        }

        var deletedCount = 0
        totalStale.forEach { video ->
            try {
                // Determine if we should delete file (only if it exists and is potentially corrupted/stuck)
                if (video.filePath.isNotBlank()) {
                    val file = File(video.filePath)
                    val isProcessing = processingStatuses.contains(video.status)
                    if (file.exists() && isProcessing) {
                        file.delete()
                    }
                }
                
                // Mark as FAILED so it can be retried or inspected manually
                val reason = when(video.status) {
                    VideoStatus.SCRIPTING -> "SCRIPTING_TIMEOUT"
                    VideoStatus.ASSETS_QUEUED -> "ASSETS_QUEUE_TIMEOUT"
                    VideoStatus.ASSETS_GENERATING -> "ASSETS_GEN_TIMEOUT"
                    VideoStatus.RENDER_QUEUED -> "RENDER_QUEUE_TIMEOUT"
                    VideoStatus.RENDERING -> "RENDERING_TIMEOUT" 
                    else -> "UPLOADING_TIMEOUT"
                }
                repository.save(video.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "STALE_JOB",
                    errorMessage = "Job abandoned due to inactivity (>$reason). Original status: ${video.status}",
                    updatedAt = LocalDateTime.now()
                ))
                println("🚩 Marked stale job (${video.status}) as FAILED: ${video.title}")
                deletedCount++
            } catch (e: Exception) {
                println("❌ Error cleaning up stale job '${video.title}': ${e.message}")
            }
        }
        println("✅ Stale job cleanup complete. Processed $deletedCount items.")
    }

    /**
     * Delete AI-generated BGM files in the root of shared-data (orphans from previous versions)
     */
    fun cleanupAiBgm() {
        println("🧹 Cleaning up orphaned AI BGM files in shared-data root...")
        val sharedDir = File(sharedDataPath)
        if (!sharedDir.exists()) return

        val threshold = System.currentTimeMillis() - (1 * 60 * 60 * 1000) // 1 hour ago
        var count = 0
        sharedDir.listFiles { _, name -> name.startsWith("ai_bgm_") && name.endsWith(".wav") }?.forEach { file ->
            if (file.lastModified() < threshold) {
                if (file.delete()) count++
            }
        }
        println("✅ AI BGM Cleanup: Deleted $count files.")
    }

    /**
     * Identify and delete video files that have no corresponding record in the database
     */
    fun cleanupOrphanedVideos() {
        println("🧹 [$channelId] Scanning for orphaned video files in shared-data/videos/$channelId...")
        val videoDir = File(sharedDataPath, "videos/$channelId")
        if (!videoDir.exists() || !videoDir.isDirectory) return

        val allVideos = repository.findByChannelId(channelId)
        val registeredPaths = allVideos.mapNotNull { it.filePath }.toSet()
        
        var count = 0
        videoDir.listFiles()?.forEach { file ->
            if (file.isFile && file.path !in registeredPaths) {
                // Heuristic: only delete if older than 30 mins to avoid deleting currently being rendered files
                if (System.currentTimeMillis() - file.lastModified() > 30 * 60 * 1000) {
                    println("🗑️ Deleting orphaned video (Not in DB): ${file.name}")
                    if (file.delete()) count++
                }
            }
        }
        println("✅ Orphaned Video Cleanup: Deleted $count files from $channelId folder.")
    }
}
