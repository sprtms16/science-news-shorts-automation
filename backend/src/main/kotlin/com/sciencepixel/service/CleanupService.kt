package com.sciencepixel.service

import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class CleanupService(
    private val repository: VideoHistoryRepository
) {
    private val sharedDataPath = "shared-data"

    fun cleanupUploadedVideos() {
        println("🧹 Starting cleanup of UPLOADED videos...")
        val uploadedVideos = repository.findAll().filter { it.status == "UPLOADED" && !it.filePath.isNullOrBlank() }

        if (uploadedVideos.isEmpty()) {
            println("✅ No uploaded videos to clean up.")
            return
        }

        val cleanupThreshold = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago
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
                                summary = video.summary + "\n[System] Resource cleaned up at ${LocalDateTime.now()}"
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
                    repository.save(video.copy(filePath = ""))
                }
            } catch (e: Exception) {
                println("❌ Error cleaning up video '${video.title}': ${e.message}")
            }
        }
        println("✅ Cleanup complete. Deleted $deletedCount video files.")
    }

    fun cleanupFailedVideos() {
        println("🧹 Starting cleanup of FAILED videos (ERROR, REGEN_FAILED)...")
        val failedVideos = repository.findAll().filter { 
            (it.status == "ERROR" || it.status == "REGEN_FAILED") && !it.filePath.isNullOrBlank() 
        }

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
                    repository.save(video.copy(status = "PERMANENTLY_FAILED"))
                    println("🚩 Marked video as PERMANENTLY_FAILED (record preserved): ${video.title}")
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
        val sharedDir = File(sharedDataPath)
        
        if (!sharedDir.exists() || !sharedDir.isDirectory) {
            println("⚠️ Shared data directory not found: $sharedDataPath")
            return
        }

        val cleanupThreshold = System.currentTimeMillis() - (1 * 60 * 60 * 1000) // 1 hour ago
        var deletedCount = 0

        sharedDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("workspace_")) {
                if (file.lastModified() < cleanupThreshold) {
                    try {
                        println("🗑️ Deleting old workspace: ${file.name}")
                        if (file.deleteRecursively()) {
                            deletedCount++
                        } else {
                            println("⚠️ Failed to delete workspace: ${file.name}")
                        }
                    } catch (e: Exception) {
                        println("❌ Error deleting workspace ${file.name}: ${e.message}")
                    }
                }
            }
        }
        println("✅ Workspace cleanup complete. Removed $deletedCount old directories.")
    }

    fun cleanupStaleJobs() {
        println("🧹 Starting cleanup of STALE jobs (Processing for > 1 hour)...")
        val threshold = LocalDateTime.now().minusHours(1)
        
        val staleVideos = repository.findAll().filter { 
            (it.status == "PROCESSING" || it.status == "REGENERATING" || it.status == "ERROR") && 
            it.createdAt.isBefore(threshold) 
        }

        if (staleVideos.isEmpty()) {
            println("✅ No stale jobs found.")
            return
        }

        var deletedCount = 0
        staleVideos.forEach { video ->
            try {
                // Delete file if exists
                if (video.filePath.isNotBlank()) {
                    val file = File(video.filePath)
                    if (file.exists()) {
                        file.delete()
                        // Try to delete workspace folder too if possible
                        file.parentFile?.let { if (it.name.startsWith("workspace_")) it.deleteRecursively() }
                    }
                }
                
                // Update status instead of deleting the record
                repository.save(video.copy(status = "STALE_JOB_ABANDONED"))
                println("🚩 Marked stale job as ABANDONED: ${video.title} (Created: ${video.createdAt})")
                deletedCount++
            } catch (e: Exception) {
                println("❌ Error cleaning up stale job '${video.title}': ${e.message}")
            }
        }
        println("✅ Stale job cleanup complete. Removed $deletedCount items.")
    }
}
