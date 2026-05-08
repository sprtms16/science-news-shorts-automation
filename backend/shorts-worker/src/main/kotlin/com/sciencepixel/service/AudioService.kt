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

    fun generateAudio(
        text: String,
        outputFile: File,
        voice: String = "ko-KR-SunHiNeural",
        rate: String = "+30%",
        pitch: String = "+0Hz"
    ): Double {
        val json = JSONObject()
            .put("text", text)
            .put("voice", voice)
            .put("rate", rate)
            .put("pitch", pitch)
            .toString()
        val request = Request.Builder()
            .url(PYTHON_SERVICE_URL)
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("TTS Error: ${response.code}")

            val resJson = JSONObject(response.body?.string() ?: "{}")
            val generatedFilename = resJson.getString("filename")
            val sharedDir = File("shared-data")
            val sourceFile = File(sharedDir, generatedFilename)

            if (!sourceFile.exists()) {
                throw IllegalStateException("TTS audio file not found: ${sourceFile.absolutePath}")
            }
            if (sourceFile.length() == 0L) {
                sourceFile.delete()
                throw IllegalStateException("TTS audio file is empty (0 bytes): ${sourceFile.absolutePath}")
            }

            sourceFile.copyTo(outputFile, overwrite = true)
            sourceFile.delete()

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw IllegalStateException("TTS output file is missing or empty after copy: ${outputFile.absolutePath}")
            }

            val duration = resJson.optDouble("duration", 0.0)
            if (duration <= 0.0) {
                throw IllegalStateException("TTS returned non-positive duration ($duration) for ${outputFile.name}")
            }

            println("🔊 TTS saved to workspace: ${outputFile.name} (voice=$voice, pitch=$pitch)")
            return duration
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
                    println("💿 Using Local BGM ($category): ${randomFile.name}")
                    randomFile.copyTo(outputFile, overwrite = true)
                    return true
                } else {
                     println("⚠️ No files found in local BGM folder: $category")
                }
            }
        } catch (e: Exception) {
            println("⚠️ Local BGM Selection Failed: ${e.message}")
        }

        // 2. Fallback to AI Generation
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
