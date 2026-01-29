package com.sciencepixel.service

import org.springframework.stereotype.Service
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File

@Service
class AudioService {
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
                println("🔊 TTS saved to workspace: ${outputFile.name}")
            } else {
                 println("⚠️ Audio file not found at ${sourceFile.absolutePath}")
            }
            
            return resJson.optDouble("duration", 5.0) // Mock duration if not enabled in Python yet
        }
    }

    private val BGM_SERVICE_URL = "http://shorts-ai-service:8000/generate-bgm"

    fun generateBgm(prompt: String, duration: Int, outputFile: File): Boolean {
        println("🎵 Requesting AI BGM: '$prompt' ($duration sec)")
        val json = JSONObject().put("prompt", prompt).put("duration", duration).toString()
        val request = Request.Builder()
            .url(BGM_SERVICE_URL)
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("❌ BGM Generation Error: ${response.message}")
                    return false
                }
                
                val resJson = JSONObject(response.body?.string() ?: "{}")
                val generatedFilename = resJson.getString("filename")
                val sharedDir = File("shared-data")
                val sourceFile = File(sharedDir, generatedFilename)
                
                if (sourceFile.exists()) {
                    sourceFile.copyTo(outputFile, overwrite = true)
                    sourceFile.delete()
                    println("✅ AI BGM saved: ${outputFile.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            println("❌ BGM Network Error: ${e.message}")
        }
        return false
    }
}
