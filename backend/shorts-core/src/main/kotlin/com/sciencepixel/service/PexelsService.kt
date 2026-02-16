package com.sciencepixel.service

import com.sciencepixel.domain.PexelsVideoCache
import com.sciencepixel.repository.PexelsVideoCacheRepository
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import java.security.MessageDigest

@Service
class PexelsService(
    @Value("\${pexels.api-key}") private val apiKey: String,
    private val geminiService: GeminiService,
    private val cacheRepository: PexelsVideoCacheRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private val logger = LoggerFactory.getLogger(PexelsService::class.java)
    }

    private val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024 // 50MB limit

    fun downloadVerifiedVideo(keyword: String, context: String, outputFile: File): Boolean {
        // === STEP 1: Check Cache ===
        val cachedVideos = cacheRepository.findByKeyword(keyword)
        for (cached in cachedVideos) {
            val cachedFile = File(cached.filePath)
            if (cachedFile.exists()) {
                // Vision Check 재검증 (context가 다를 수 있음)
                val isRelevant = try {
                    geminiService.checkVideoRelevance(cached.thumbnailUrl, context)
                } catch (e: Exception) {
                    logger.warn("Cache Vision Check Error: {}. Trying next cached video.", e.message)
                    false
                }

                if (isRelevant) {
                    logger.info("✓ Using cached Pexels video: {} for '{}' (usage count: {})",
                        cached.pexelsVideoId, keyword, cached.usageCount)

                    cachedFile.copyTo(outputFile, overwrite = true)

                    // Update usage stats
                    cacheRepository.save(cached.copy(
                        lastUsedAt = LocalDateTime.now(),
                        usageCount = cached.usageCount + 1
                    ))
                    return true
                }
            } else {
                logger.warn("Cached file missing: {}. Removing from cache.", cached.filePath)
                cacheRepository.delete(cached)
            }
        }

        logger.info("Cache miss for '{}'. Fetching from Pexels API...", keyword)

        // === STEP 2: Search Pexels API ===
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val request = Request.Builder()
            .url("https://api.pexels.com/videos/search?query=$encodedKeyword&per_page=5")
            .addHeader("Authorization", apiKey)
            .build()

        logger.info("Pexels Searching: '{}'", keyword)
        logger.info("Pexels API Key: {}", if (apiKey.isNotBlank()) "YES (${apiKey.take(3)}...)" else "NO")

        var bestVideoUrl = ""
        var pexelsVideoId = ""
        var thumbnailUrl = ""
        var videoWidth = 0
        var videoHeight = 0

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = try {
                    response.body?.string() ?: "{}"
                } catch (e: Exception) {
                    logger.error("Error reading Pexels response body: {}", e.message)
                    "{}"
                }

                logger.info("Pexels Response Code: {}", response.code)

                if (!response.isSuccessful) {
                    logger.error("Pexels API Error: {}", response.message)
                    return false
                }

                val videos = JSONObject(bodyString).optJSONArray("videos")
                if (videos == null || videos.length() == 0) {
                    logger.warn("No videos found in API response for '{}'", keyword)
                    return false
                }

                logger.info("Found {} candidates for '{}'", videos.length(), keyword)

                // Loop & Verify
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    val thumb = v.getString("image")

                    logger.info("Candidate #{}: Checking vision relevance...", i)
                    logger.info("Thumbnail: {}", thumb)

                    // Vision Check
                    val isRelevant = try {
                        geminiService.checkVideoRelevance(thumb, context)
                    } catch (e: Exception) {
                        logger.warn("Vision Check Error: {}. Skipping this candidate.", e.message)
                        false
                    }

                    if (isRelevant) {
                        logger.info("✅ Vision Check Passed!")

                        // Extract metadata for caching
                        pexelsVideoId = v.getInt("id").toString()
                        thumbnailUrl = thumb
                        videoWidth = v.getInt("width")
                        videoHeight = v.getInt("height")

                        // Find HD Link
                        val files = v.getJSONArray("video_files")
                        for (j in 0 until files.length()) {
                            val f = files.getJSONObject(j)
                            if (f.getInt("width") >= 720) {
                                bestVideoUrl = f.getString("link")
                                logger.info("Found HD Link: {}", bestVideoUrl)
                                break
                            }
                        }
                        if (bestVideoUrl.isNotEmpty()) break
                    } else {
                        logger.info("❌ Vision Check Failed. Trying next candidate...")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Pexels Network/Parsing Error: {}", e.message, e)
            return false
        }

        if (bestVideoUrl.isEmpty()) {
            logger.warn("No relevant video found for '{}'.", keyword)
            return false
        }

        // === STEP 3: Download with size limit ===
        val downloadRequest = Request.Builder().url(bestVideoUrl).build()
        client.newCall(downloadRequest).execute().use { downloadResponse ->
            if (!downloadResponse.isSuccessful) {
                logger.error("Pexels Download Error: {}", downloadResponse.code)
                return false
            }
            val contentLength = downloadResponse.body?.contentLength() ?: -1
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                logger.warn("Video too large ({}MB), skipping", contentLength / 1024 / 1024)
                return false
            }
            downloadResponse.body?.byteStream()?.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // === STEP 4: Save to Cache ===
        try {
            // Generate keyword hash for directory name
            val keywordHash = MessageDigest.getInstance("MD5")
                .digest(keyword.toByteArray())
                .joinToString("") { "%02x".format(it) }

            val cacheDir = File("shared-data/pexels-cache/common/$keywordHash").apply { mkdirs() }
            val cachedFile = File(cacheDir, "$pexelsVideoId.mp4")

            // Copy downloaded video to cache
            if (!cachedFile.exists()) {
                outputFile.copyTo(cachedFile, overwrite = false)
                logger.info("✓ Cached new Pexels video: {} for '{}' at {}", pexelsVideoId, keyword, cachedFile.absolutePath)
            }

            // Save metadata to MongoDB
            if (!cacheRepository.existsByPexelsVideoId(pexelsVideoId)) {
                cacheRepository.save(PexelsVideoCache(
                    keyword = keyword,
                    pexelsVideoId = pexelsVideoId,
                    filePath = cachedFile.absolutePath,
                    thumbnailUrl = thumbnailUrl,
                    width = videoWidth,
                    height = videoHeight,
                    visionCheckPassed = true,
                    visionCheckContext = context,
                    channelId = "common"
                ))
            }
        } catch (e: Exception) {
            logger.warn("Cache save failed (non-critical): {}", e.message)
            // Continue - caching failure shouldn't block video production
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

        logger.info("Pexels Photo Search: '{}'", keyword)

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.error("Pexels Photo Error: {}", response.code)
                    return null
                }
                
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val photos = json.optJSONArray("photos")
                
                if (photos == null || photos.length() == 0) {
                    logger.warn("No photos found for '{}'", keyword)
                    return null
                }
                
                // Pick the first high-quality one
                val photo = photos.getJSONObject(0)
                val src = photo.getJSONObject("src")
                val url = src.getString("large2x") // High res URL
                
                logger.info("Found Thumbnail Candidate: {}", url)
                url
            }
        } catch (e: Exception) {
            logger.error("Pexels Photo Exception: {}", e.message)
            null
        }
    }
}
