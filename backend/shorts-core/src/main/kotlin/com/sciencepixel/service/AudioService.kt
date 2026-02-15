package com.sciencepixel.service

import org.springframework.stereotype.Service
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import org.slf4j.LoggerFactory

@Service
class AudioService {
    companion object {
        private val logger = LoggerFactory.getLogger(AudioService::class.java)
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    // Docker Internal Hostname: shorts-ai-service (container_name) or ai-media-service (service name)
    // We configured container_name in docker-compose as shorts-ai-service
    private val PYTHON_SERVICE_URL = "http://shorts-ai-service:8000/generate-audio"

    fun generateAudio(text: String, outputFile: File): Double {
        val json = JSONObject().put("text", text).put("voice", "ko-KR-SunHiNeural").toString()
        val request = Request.Builder()
            .url(PYTHON_SERVICE_URL)
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("TTS Error: ${response.code}")
            
            val resJson = JSONObject(response.body?.string() ?: "{}")
            // Python service returns filename. Because valid volume share, we can just use the file
            // But wait, Python saves to /app/output which is mapped to shared-data.
            // Kotlin service also maps shared-data to /app/shared-data.
            // So if Python says "audio_uuid.mp3", Kotlin can find it in /app/shared-data/audio_uuid.mp3
            
            // However, the AudioService interface provided in manifest implies it puts file to `outputFile`.
            // In ProductionService code provided: val duration = audioService.generateAudio(..., audioFile)
            // So we need to copy or move the file from shared-data if Python writes to random name.
            
            // Re-reading manifest AudioService.kt:
            // "return resJson.optDouble("duration", 5.0)"
            // It seems it relies on shared volume implicitly.
            // Let's implement logic to ensure the file exists at outputFile.
            
            val generatedFilename = resJson.getString("filename")
            val sharedDir = File("shared-data") // in container: /app/shared-data
            val sourceFile = File(sharedDir, generatedFilename)
            
            // Wait retry for file system sync if needed (usually instant on local volume)
            if (sourceFile.exists()) {
                sourceFile.copyTo(outputFile, overwrite = true)
                sourceFile.delete() // Clean up temp file from shared-data root
                logger.info("TTS saved to workspace: {}", outputFile.name)
            } else {
                throw IllegalStateException("TTS audio file not found: ${sourceFile.absolutePath}")
            }
            
            return resJson.optDouble("duration", 5.0) // Mock duration if not enabled in Python yet
        }
    }

    private val BGM_SERVICE_URL = "http://shorts-ai-service:8000/generate-bgm"

    fun generateBgm(prompt: String, duration: Int, outputFile: File): Boolean {
        // 1. Try Local File First (User Preference)
        try {
            // Map prompt keywords to Mood Categories
            val category = when {
                prompt.contains("Tech", ignoreCase = true) || prompt.contains("Science", ignoreCase = true) || prompt.contains("Futuristic", ignoreCase = true) -> "futuristic"
                prompt.contains("Horror", ignoreCase = true) || prompt.contains("Mystery", ignoreCase = true) || prompt.contains("Dark", ignoreCase = true) || prompt.contains("Suspense", ignoreCase = true) -> "suspense"
                prompt.contains("Stock", ignoreCase = true) || prompt.contains("Corporate", ignoreCase = true) || prompt.contains("Business", ignoreCase = true) -> "corporate"
                prompt.contains("History", ignoreCase = true) || prompt.contains("Epic", ignoreCase = true) || prompt.contains("War", ignoreCase = true) -> "epic"
                else -> "calm"
            }
            
            val localDir = File("shared-data/bgm/$category")
            if (localDir.exists() && localDir.isDirectory) {
                val files = localDir.listFiles { _, name -> name.endsWith(".mp3", ignoreCase = true) || name.endsWith(".wav", ignoreCase = true) }
                if (files != null && files.isNotEmpty()) {
                    val randomFile = files.random()
                    logger.info("Using Local BGM ({}): {}", category, randomFile.name)
                    randomFile.copyTo(outputFile, overwrite = true)
                    return true
                }
            }
        } catch (e: Exception) {
            logger.warn("Local BGM Selection Failed: {}", e.message)
        }

        // 2. Fallback to AI Generation
        logger.info("Requesting AI BGM: '{}' ({} sec)", prompt, duration)
        val json = JSONObject().put("prompt", prompt).put("duration", duration).toString()
        val request = Request.Builder()
            .url(BGM_SERVICE_URL)
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.error("BGM Generation Error: {}", response.message)
                    return false
                }
                
                val resJson = JSONObject(response.body?.string() ?: "{}")
                val generatedFilename = resJson.getString("filename")
                val sharedDir = File("shared-data")
                val sourceFile = File(sharedDir, generatedFilename)
                
                if (sourceFile.exists()) {
                    sourceFile.copyTo(outputFile, overwrite = true)
                    sourceFile.delete()
                    logger.info("AI BGM saved: {}", outputFile.name)
                    return true
                }
            }
        } catch (e: Exception) {
            logger.error("BGM Network Error: {}", e.message)
        }
        return false
    }
}
