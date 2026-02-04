package com.sciencepixel.controller

import com.sciencepixel.service.GeminiService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RestController
@RequestMapping("/api/admin/bgm")
@CrossOrigin(origins = ["*"])
class AdminBgmController(
    private val bgmRepository: com.sciencepixel.repository.BgmRepository,
    private val eventPublisher: com.sciencepixel.event.KafkaEventPublisher,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    private val PENDING_PATH = "/app/shared-data/bgm/pending"

    @GetMapping("/list")
    fun getBgmList(): ResponseEntity<List<com.sciencepixel.domain.BgmEntity>> {
        // Return latest first
        return ResponseEntity.ok(bgmRepository.findAllByOrderByCreatedAtDesc())
    }

    @PostMapping("/retry/{id}")
    fun retryBgmVerification(@PathVariable id: String): ResponseEntity<Map<String, String>> {
        val entityOpt = bgmRepository.findById(id)
        if (entityOpt.isEmpty) return ResponseEntity.notFound().build()
        
        val entity = entityOpt.get()
        
        // Reset Status to PROCESSING
        val updatedEntity = entity.copy(
            status = com.sciencepixel.domain.BgmStatus.PROCESSING,
            errorMessage = null,
            updatedAt = java.time.LocalDateTime.now()
        )
        bgmRepository.save(updatedEntity)
        
        // Publish Event Again
        eventPublisher.publishBgmUpload(
            com.sciencepixel.event.BgmUploadEvent(
                bgmId = entity.id!!,
                filePath = entity.filePath,
                filename = entity.filename,
                channelId = entity.channelId
            )
        )
        
        println("🔄 BGM Retry Triggered for: ${entity.filename}")
        return ResponseEntity.ok(mapOf("message" to "Retry initiated"))
    }

    @PostMapping("/upload")
    fun uploadBgm(
        @RequestParam("files") files: List<MultipartFile>,
        @RequestParam("forceCategory", required = false) forceCategory: String?
    ): ResponseEntity<Map<String, Any>> {
        if (files.isEmpty()) return ResponseEntity.badRequest().body(mapOf("error" to "No files selected"))

        val uploadedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val duplicatesCount = java.util.concurrent.atomic.AtomicInteger(0)

        for (file in files) {
            if (file.isEmpty || file.originalFilename == null) continue
            try {
                // 0. Duplicate Check
                val safeFilename = file.originalFilename!!.replace(" ", "_")
                val existing = bgmRepository.findByFilename(safeFilename)
                
                if (existing != null) {
                    println("ℹ️ Duplicate BGM detected: $safeFilename. Skipping upload, preserving existing.")
                    duplicatesCount.incrementAndGet()
                    continue
                }

                // 1. Save to Pending Directory
                val pendingDir = File(PENDING_PATH)
                if (!pendingDir.exists()) pendingDir.mkdirs()

                // Create Unique Filename to prevent collision in pending
                val uniqueId = java.util.UUID.randomUUID().toString()
                val tempFilename = "${uniqueId}_$safeFilename"
                
                val targetFile = File(pendingDir, tempFilename)
                file.transferTo(targetFile)

                // 2. Save Initial Entity (Status: PENDING)
                val entity = com.sciencepixel.domain.BgmEntity(
                    id = uniqueId,
                    filename = safeFilename,
                    filePath = targetFile.absolutePath,
                    status = com.sciencepixel.domain.BgmStatus.PENDING,
                    channelId = channelId,
                    mood = if (!forceCategory.isNullOrBlank() && forceCategory != "auto") forceCategory else null
                )
                bgmRepository.save(entity)

                // 3. Publish Event (Async Analysis)
                eventPublisher.publishBgmUpload(
                    com.sciencepixel.event.BgmUploadEvent(
                        bgmId = uniqueId,
                        filePath = targetFile.absolutePath,
                        filename = safeFilename,
                        channelId = channelId
                    )
                )

                uploadedCount.incrementAndGet()
                println("✅ BGM Upload Queued: $safeFilename (ID: $uniqueId)")

            } catch (e: Exception) {
                e.printStackTrace()
                println("❌ BGM Upload Failed: ${file.originalFilename} - ${e.message}")
            }
        }
        
        return ResponseEntity.accepted().body(mapOf(
            "message" to "Upload processed.",
            "uploaded" to uploadedCount.get(),
            "duplicates" to duplicatesCount.get()
        ))
    }
    @DeleteMapping("/{id}")
    fun deleteBgm(@PathVariable id: String): ResponseEntity<Map<String, String>> {
        val entityOpt = bgmRepository.findById(id)
        if (entityOpt.isEmpty) return ResponseEntity.notFound().build()
        
        val entity = entityOpt.get()
        
        return try {
            // Delete File
            val file = File(entity.filePath)
            if (file.exists()) {
                if (file.delete()) {
                    println("🗑️ Deleted BGM File: ${entity.filePath}")
                } else {
                    println("⚠️ Failed to delete BGM File: ${entity.filePath}")
                }
            }
            
            // Delete DB
            bgmRepository.delete(entity)
            println("🗑️ Deleted BGM Entity: ${entity.filename}")
            
            ResponseEntity.ok(mapOf("message" to "Deleted successfully"))
        } catch (e: Exception) {
            e.printStackTrace()
            ResponseEntity.internalServerError().body(mapOf("error" to "Failed to delete: ${e.message}"))
        }
    }

    @PutMapping("/{id}")
    fun updateBgm(
        @PathVariable id: String,
        @RequestBody updates: Map<String, String>
    ): ResponseEntity<Map<String, String>> {
        val entityOpt = bgmRepository.findById(id)
        if (entityOpt.isEmpty) return ResponseEntity.notFound().build()
        
        var entity = entityOpt.get()
        val SHARED_DATA_PATH = "/app/shared-data/bgm"
        
        try {
            // 1. Update Mood (Category) & Move File
            if (updates.containsKey("mood")) {
                val newMood = updates["mood"] ?: "unknown"
                val oldMood = entity.mood ?: "pending"
                
                if (newMood != oldMood) {
                    val currentFile = File(entity.filePath)
                    if (currentFile.exists()) {
                        val targetDir = File(SHARED_DATA_PATH, newMood)
                        if (!targetDir.exists()) targetDir.mkdirs()
                        
                        val newFile = File(targetDir, currentFile.name)
                        Files.move(currentFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        newFile.setReadable(true, false)
                        
                        entity = entity.copy(
                            mood = newMood,
                            filePath = newFile.absolutePath,
                            updatedAt = java.time.LocalDateTime.now()
                        )
                        println("📂 Moved BGM: $oldMood -> $newMood")
                    }
                }
            }
            
            // 2. Update Filename (Rename)
            // Note: This is tricky if simple rename, but let's support it if requested.
            // Simplification: Only support Mood update for now as requested "CRUD".
            
            bgmRepository.save(entity)
            return ResponseEntity.ok(mapOf("message" to "Updated successfully"))
            
        } catch (e: Exception) {
            e.printStackTrace()
            return ResponseEntity.internalServerError().body(mapOf("error" to "Update failed: ${e.message}"))
        }
    }
}
