package com.sciencepixel.service

import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL

@Service
class PexelsService(
    @Value("\${pexels.api-key}") private val apiKey: String,
    private val geminiService: GeminiService
) {
    private val client = OkHttpClient()

    fun downloadVerifiedVideo(keyword: String, context: String, outputFile: File): Boolean {
        // 1. Search Pexels
        val request = Request.Builder()
            .url("https://api.pexels.com/videos/search?query=$keyword&per_page=5")
            .addHeader("Authorization", apiKey)
            .build()
            
            println("🔎 Pexels Searching: '$keyword'")
            println("🔑 Pexels API Key: ${if (apiKey.isNotBlank()) "YES (${apiKey.take(3)}...)" else "NO"}")

        var bestVideoUrl = ""

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: "{}"
                println("📡 Pexels Response Code: ${response.code}")
                // println("📡 Pexels Body: ${bodyString.take(500)}...") // Commented out to prevent crash

                if (!response.isSuccessful) {
                    println("❌ Pexels API Error: ${response.message}")
                    return false
                }
                
                val videos = JSONObject(bodyString).optJSONArray("videos")
                if (videos == null || videos.length() == 0) {
                    println("⚠️ No videos found in API response for '$keyword'")
                    return false
                }
                
                println("🎥 Found ${videos.length()} candidates for '$keyword'")

                // 2. Loop & Verify
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    val thumb = v.getString("image") // Thumbnail URL
                    
                    
                    println("  Candidate #$i: checking vision... (SKIPPING for Debug)")
                    println("  Thumbnail: $thumb")

                    // ** VISION CHECK (Bypassed) **
                    if (true) { // geminiService.verifyImage(thumb, context)
                        println("  ✅ Vision Check Passed (Bypassed)!")
                        // Find HD Link
                        val files = v.getJSONArray("video_files")
                        for (j in 0 until files.length()) {
                            val f = files.getJSONObject(j)
                            if (f.getInt("width") >= 720) {
                                bestVideoUrl = f.getString("link")
                                println("  ✅ Found HD Link: $bestVideoUrl")
                                break
                            }
                        }
                        if (bestVideoUrl.isNotEmpty()) break
                    } else {
                        println("  ❌ Vision Check Failed.")
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Pexels Network/Parsing Error: ${e.message}")
            e.printStackTrace()
            return false
        }

        if (bestVideoUrl.isEmpty()) {
            println("⚠️ No relevant video found for '$keyword'.")
            return false
        }

        // 3. Download
        URL(bestVideoUrl).openStream().use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }
        return true
    }

    // New: Search for a high-quality photo for thumbnail
    fun searchPhoto(keyword: String): String? {
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val request = Request.Builder()
            .url("https://api.pexels.com/v1/search?query=$encodedKeyword&per_page=5&orientation=portrait")
            .addHeader("Authorization", apiKey)
            .build()

        println("🔎 Pexels Photo Search: '$keyword'")

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("❌ Pexels Photo Error: ${response.code}")
                    return null
                }
                
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val photos = json.optJSONArray("photos")
                
                if (photos == null || photos.length() == 0) {
                    println("⚠️ No photos found for '$keyword'")
                    return null
                }
                
                // Pick the first high-quality one
                val photo = photos.getJSONObject(0)
                val src = photo.getJSONObject("src")
                val url = src.getString("large2x") // High res URL
                
                println("✅ Found Thumbnail Candidate: $url")
                url
            }
        } catch (e: Exception) {
            println("❌ Pexels Photo Exception: ${e.message}")
            null
        }
    }
}
