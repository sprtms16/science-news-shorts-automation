package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.domain.BgmStatus
import com.sciencepixel.event.BgmUploadEvent
import com.sciencepixel.repository.BgmRepository
import com.sciencepixel.service.GeminiService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Component
class BgmVerificationConsumer(
    private val geminiService: GeminiService,
    private val bgmRepository: BgmRepository,
    private val objectMapper: ObjectMapper
) {

    private val SHARED_DATA_PATH = "/app/shared-data/bgm"

    @KafkaListener(topics = [KafkaConfig.TOPIC_BGM_UPLOAD], groupId = KafkaConfig.GROUP_MAIN)
    fun processBgm(message: String) {
        val event = try {
            objectMapper.readValue(message, BgmUploadEvent::class.java)
        } catch (e: Exception) {
            println("❌ [BgmConsumer] Failed to parse event: ${e.message}")
            return
        }

        println("🎧 [BgmConsumer] Processing: ${event.filename} (ID: ${event.bgmId})")

        // 1. Fetch Entity
        val entityOpt = bgmRepository.findById(event.bgmId)
        if (entityOpt.isEmpty) {
            println("⚠️ [BgmConsumer] Entity not found for ID: ${event.bgmId}")
            return
        }
        val entity = entityOpt.get()

        // 2. Set PROCESSING
        bgmRepository.save(entity.copy(status = BgmStatus.PROCESSING, updatedAt = java.time.LocalDateTime.now()))

        try {
            val file = File(event.filePath)
            if (!file.exists()) {
                throw RuntimeException("File not found at ${event.filePath}")
            }

            // 3. Classify (Gemini)
            // If mood is already set (Force Category), skip AI
            val mood = if (!entity.mood.isNullOrBlank()) {
                println("⏩ [BgmConsumer] Using forced category: ${entity.mood}")
                entity.mood
            } else {
                geminiService.classifyAudio(file, event.filename)
            }

            // 4. Move to Final Directory
            val targetDir = File(SHARED_DATA_PATH, mood)
            if (!targetDir.exists()) targetDir.mkdirs()

            // Remove UUID prefix for final filename if desired, or keep it to be safe. 
            // Let's keep original filename if possible, but handle duplicates.
            // Actually, let's just use the filename from the entity which is "safeFilename"
            val finalFilename = entity.filename
            var targetFile = File(targetDir, finalFilename)
            
            // Handle duplicates: append (1), (2) etc or just overwrite?
            // Simple overwrite for now as requested
            
            Files.move(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            targetFile.setReadable(true, false)

            // 5. Update DB -> COMPLETED
            bgmRepository.save(entity.copy(
                status = BgmStatus.COMPLETED,
                mood = mood,
                filePath = targetFile.absolutePath, // Update to final path
                updatedAt = java.time.LocalDateTime.now()
            ))

            println("✅ [BgmConsumer] Completed: ${event.filename} -> [$mood]")

        } catch (e: Exception) {
            println("❌ [BgmConsumer] Failed: ${e.message}")
            bgmRepository.save(entity.copy(
                status = BgmStatus.FAILED,
                errorMessage = e.message,
                updatedAt = java.time.LocalDateTime.now()
            ))
        }
    }
}
