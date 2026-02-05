package com.sciencepixel.service

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoSnippet
import com.google.api.services.youtube.model.VideoStatus
import com.google.api.client.http.InputStreamContent
import com.sciencepixel.domain.YoutubeVideoStat
import com.sciencepixel.domain.YoutubeVideoResponse
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.CountDownLatch

@Service
class YoutubeService(
    private val quotaTracker: QuotaTracker,
    private val youtubeVideoRepository: com.sciencepixel.repository.YoutubeVideoRepository,
    private val notificationService: NotificationService, // 추가: 알림 서비스 주입
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String,
    @org.springframework.beans.factory.annotation.Value("\${YOUTUBE_REDIRECT_URI:http://localhost:8080/callback}") private val redirectUri: String
) {

    private val JSON_FACTORY = JacksonFactory.getDefaultInstance()
    private val TOKENS_DIRECTORY_PATH = "tokens/$channelId"
    
    private val credentialFolder = when(channelId) {
        "science" -> "SciencePixel"
        "horror" -> "MysteryPixel"
        "stocks" -> "ValuePixel"
        "history" -> "HistoryPixel"
        else -> "SciencePixel" // Default fallback
    }
    private val CREDENTIALS_FILE_PATH = "/app/client_secret/$credentialFolder/client_secret.json"
    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/youtube.upload",
        "https://www.googleapis.com/auth/youtube.readonly",
        "https://www.googleapis.com/auth/youtube"
    )
    private val APPLICATION_NAME = "Science News Shorts Automation"

    private var cachedUploadsPlaylistId: String? = null

    private fun getYoutubeClient(targetChannelId: String? = null): YouTube {
        val credential = getCredentials(targetChannelId)
        return YouTube.Builder(
            GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential
        ).setApplicationName(APPLICATION_NAME).build()
    }

    private fun getUploadsPlaylistId(youtube: YouTube): String {
        cachedUploadsPlaylistId?.let { return it }
        val channelResponse = youtube.channels().list(listOf("contentDetails")).setMine(true).execute()
        val uploadsId = channelResponse.items.first().contentDetails.relatedPlaylists.uploads
        cachedUploadsPlaylistId = uploadsId
        return uploadsId
    }

    fun getMyVideosStats(limit: Long = 20, pageToken: String? = null, targetChannelId: String? = null): YoutubeVideoResponse {
        val youtube = getYoutubeClient(targetChannelId)
        val uploadsId = getUploadsPlaylistId(youtube)

        // 1. Get PlaylistItems (latest videos)
        val playlistItemsRequest = youtube.playlistItems()
            .list(listOf("snippet", "contentDetails"))
            .setPlaylistId(uploadsId)
            .setMaxResults(limit)
        
        if (!pageToken.isNullOrEmpty()) {
            playlistItemsRequest.pageToken = pageToken
        }

        val playlistItemsResponse = playlistItemsRequest.execute()
        val nextPageToken = playlistItemsResponse.nextPageToken

        val videoIds = playlistItemsResponse.items?.map { it.contentDetails.videoId } ?: emptyList()
        if (videoIds.isEmpty()) return YoutubeVideoResponse(emptyList(), null)

        // 2. Get Video Statistics
        val videosResponse = youtube.videos()
            .list(listOf("snippet", "statistics"))
            .setId(videoIds)
            .execute()

        val videos = videosResponse.items.map { video ->
            YoutubeVideoStat(
                videoId = video.id,
                title = video.snippet.title,
                description = video.snippet.description ?: "",
                viewCount = video.statistics.viewCount?.toLong() ?: 0L,
                likeCount = video.statistics.likeCount?.toLong() ?: 0L,
                publishedAt = video.snippet.publishedAt.toString(),
                thumbnailUrl = video.snippet.thumbnails.default.url
            )
        }.sortedByDescending { it.publishedAt }

        return YoutubeVideoResponse(videos, nextPageToken)
    }

    fun getVideoSnippet(videoId: String, targetChannelId: String? = null): VideoSnippet? {
        val youtube = getYoutubeClient(targetChannelId)
        val response = youtube.videos().list(listOf("snippet")).setId(listOf(videoId)).execute()
        return response.items.firstOrNull()?.snippet
    }

    fun isTitleDuplicateOnChannel(title: String): Boolean {
        try {
            // 1. Normalized exact/containment check first (Fast)
            val normalizedTarget = title.replace(Regex("\\s+"), "").lowercase()
            
            // Search for potential duplicates in local DB (cached YouTube titles)
            val potentialDuplicates = youtubeVideoRepository.findAllByChannelIdOrderByPublishedAtDesc(
                channelId, 
                org.springframework.data.domain.PageRequest.of(0, 50)
            ).content
            
            val exactMatch = potentialDuplicates.any { 
                val normalizedExisting = it.title.replace(Regex("\\s+"), "").lowercase()
                normalizedExisting == normalizedTarget
            }
            
            if (exactMatch) {
                println("⚠️ Exact duplicate found locally for channel $channelId: $title")
                return true
            }
            
            // 2. Similarity Check (Levenshtein Distance)
            val allVideos = youtubeVideoRepository.findByChannelId(channelId)
            
            // Threshold: 0.6 (60% match)
            val threshold = 0.6
            
            val similarVideo = allVideos.find { video ->
                val similarity = calculateSimilarity(title, video.title)
                if (similarity >= threshold) {
                    println("⚠️ Similar duplicate found in channel $channelId: '${video.title}' (Score: $similarity) similar to '$title'")
                    true
                } else {
                    false
                }
            }
            
            return similarVideo != null

        } catch (e: Exception) {
            println("⚠️ Error checking channel duplicates from local DB: ${e.message}")
            return false // Fallback
        }
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0
        val levDistance = getLevenshteinDistance(longer, shorter)
        return (longer.length - levDistance).toDouble() / longer.length.toDouble()
    }

    private fun getLevenshteinDistance(x: String, y: String): Int {
        val m = x.length
        val n = y.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    private fun getFlow(targetChannelId: String? = null): GoogleAuthorizationCodeFlow {
        val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        val effectiveChannelId = targetChannelId ?: channelId
        val tokenPath = "tokens/$effectiveChannelId"
        
        // Priority 1: Environment variable (Base64 encoded JSON) - for GitHub Actions / CI/CD
        val secretB64 = System.getenv("YOUTUBE_CLIENT_SECRET_JSON_B64")
        val inStream = if (!secretB64.isNullOrBlank()) {
            println("✅ Using client_secret from environment variable (B64)")
            val decoded = java.util.Base64.getDecoder().decode(secretB64)
            java.io.ByteArrayInputStream(decoded)
        } else {
            // Priority 2: File system (Docker volume mount) - for local development
            val targetCredentialFolder = when(effectiveChannelId) {
                "science" -> "SciencePixel"
                "horror" -> "MysteryPixel"
                "stocks" -> "ValuePixel"
                "history" -> "HistoryPixel"
                else -> "SciencePixel"
            }
            val credentialPath = "/app/client_secret/$targetCredentialFolder/client_secret.json"
            val credentialFile = File(credentialPath)
            
            if (credentialFile.exists()) {
                println("✅ Found client_secret at: $credentialPath")
                FileInputStream(credentialFile)
            } else {
                // Priority 3: Classpath resource (for JAR packaging)
                println("⚠️ File not found at $credentialPath, trying classpath...")
                YoutubeService::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH) 
                    ?: throw RuntimeException("❌ client_secret.json not found. Set YOUTUBE_CLIENT_SECRET_JSON_B64 env var or mount the file at $credentialPath")
            }
        }

        val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(inStream))

        return GoogleAuthorizationCodeFlow.Builder(
            httpTransport, JSON_FACTORY, clientSecrets, SCOPES
        )
        .setDataStoreFactory(FileDataStoreFactory(File(tokenPath)))
        .setAccessType("offline")
        .build()
    }



    private fun getCredentials(targetChannelId: String? = null): Credential {
        val effectiveChannelId = targetChannelId ?: channelId
        val flow = getFlow(targetChannelId)
        
        println("🔑 [$effectiveChannelId] Loading credentials from tokens/$effectiveChannelId...")
        
        // Try to load existing credential
        var credential = flow.loadCredential("user")
        
        if (credential == null) {
            println("⚠️ [$effectiveChannelId] No stored credential found!")
        } else {
            println("✅ [$effectiveChannelId] Credential loaded. RefreshToken: ${credential.refreshToken?.take(20)}..., ExpiresIn: ${credential.expiresInSeconds}s")
        }
        
        // Check if credential exists and is valid (or refreshable)
        if (credential != null && (credential.refreshToken != null || credential.expiresInSeconds == null || credential.expiresInSeconds > 60)) {
            println("✅ [$effectiveChannelId] Credential is valid, using existing token.")
            return credential
        }
        
        // Try to refresh if possible
        try {
            if (credential != null && credential.refreshToken != null) {
                println("🔄 [$effectiveChannelId] Attempting to refresh token...")
                if (credential.refreshToken()) {
                    println("✅ [$effectiveChannelId] Token refreshed successfully!")
                    return credential
                } else {
                    println("⚠️ [$effectiveChannelId] Token refresh returned false.")
                }
            }
        } catch (e: Exception) {
            println("⚠️ [$effectiveChannelId] Refresh failed: ${e.message}")
        }

        // Needs new Auth
        triggerAuthFlow(flow, targetChannelId)
        
        credential = flow.loadCredential("user")
        if (credential == null) {
             throw RuntimeException("Authorization failed or timed out for $effectiveChannelId. Please authenticate via the URL in logs.")
        }
        return credential
    }

    
    fun getAuthorizationUrl(targetChannelId: String? = null): String {
        val flow = getFlow(targetChannelId)
        return flow.newAuthorizationUrl().setRedirectUri(redirectUri).setAccessType("offline").build()
    }

    // Explicitly public so Controller can call it
    fun triggerAuthFlow(flow: GoogleAuthorizationCodeFlow, targetChannelId: String? = null) {
        val effectiveChannelId = targetChannelId ?: channelId
        val authUrl = getAuthorizationUrl(effectiveChannelId)
        
        println("👉 [$effectiveChannelId] Please open the following URL to authorize:")
        println(authUrl)
        println("Waiting for callback on $redirectUri ...")

        // 🔔 Discord/Telegram 알림 전송
        notificationService.notifyAuthRequired(effectiveChannelId, authUrl)
        
        throw RuntimeException("YouTube Auth Required for $effectiveChannelId. Visit URL in logs or call /api/youtube/auth-url and retry.")
    }

    // Called by OAuthController
    fun exchangeCodeForToken(code: String) {
        println("🔑 Exchanging code for token via Google Lib...")
        try {
            val flow = getFlow()
            val tokenResponse = flow.newTokenRequest(code).setRedirectUri(redirectUri).execute()
            flow.createAndStoreCredential(tokenResponse, "user")
            println("✅ Credential saved successfully to $TOKENS_DIRECTORY_PATH")
        } catch (e: Exception) {
            println("❌ Failed to exchange code: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    fun uploadVideo(file: File, title: String, description: String, tags: List<String>, thumbnailFile: File? = null, targetChannelId: String? = null): String {
        val effectiveChannelId = targetChannelId ?: channelId
        println("📡 [$effectiveChannelId] Connecting to YouTube API (Google Client)...")
        val credential = getCredentials(effectiveChannelId) // might throw
        
        val youtube = YouTube.Builder(
            GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential
        ).setApplicationName(APPLICATION_NAME).build()

        println("📤 [$effectiveChannelId] Preparing Upload: $title")

        val video = Video()
        
        val snippet = VideoSnippet()
        // Sanitize title: Remove only control characters and dangerous HTML/XML chars, preserve Korean
        // YouTube supports unicode including Korean, so we only remove: < > " ' / \ control chars
        val sanitizedTitle = title
            .replace(Regex("[<>\"'/\\\\\\p{Cntrl}]"), "")  // Remove dangerous chars and control chars
            .trim()
        snippet.title = if (sanitizedTitle.length > 100) sanitizedTitle.substring(0, 97) + "..." else sanitizedTitle
        
        // Sanitize description: Remove dangerous HTML chars
        val sanitizedDesc = description
            .replace("<", "")
            .replace(">", "")
        snippet.description = if (sanitizedDesc.length > 4000) sanitizedDesc.substring(0, 3997) + "..." else sanitizedDesc
        snippet.tags = tags
        snippet.categoryId = "28" // Science & Technology
        video.snippet = snippet

        val status = VideoStatus()
        status.privacyStatus = "public"  // 공개 업로드 (사용자 요청)
        status.selfDeclaredMadeForKids = false // 아동용 아님 명시
        video.status = status

        val mediaContent = InputStreamContent("video/mp4", FileInputStream(file))

        val request = youtube.videos().insert(listOf("snippet", "status"), video, mediaContent)
        
        val response = try {
            request.execute()
        } catch (e: GoogleJsonResponseException) {
            if (e.details?.errors?.any { it.reason == "quotaExceeded" } == true) {
                println("🛑 [$effectiveChannelId] YouTube Quota Exceeded detected during upload.")
                quotaTracker.setSuspended("Quota Exceeded Error from YouTube API ($effectiveChannelId)")
            }
            throw e
        } catch (e: Exception) {
            throw e
        }
        
        println("✅ [$effectiveChannelId] YouTube Upload Complete! ID: ${response.id}")
        
        // Custom Thumbnail Upload
        if (thumbnailFile != null && thumbnailFile.exists()) {
            try {
                println("🖼️ [$effectiveChannelId] Uploading Custom Thumbnail...")
                val thumbContent = InputStreamContent("image/jpeg", FileInputStream(thumbnailFile))
                youtube.thumbnails().set(response.id, thumbContent).execute()
                println("✅ [$effectiveChannelId] Thumbnail Set Successfully!")
            } catch (e: Exception) {
                println("⚠️ [$effectiveChannelId] Failed to upload thumbnail: ${e.message}")
            }
        }
        
        return "https://youtu.be/${response.id}"
    }

    fun updateVideoMetadata(videoId: String, title: String? = null, description: String? = null, targetChannelId: String? = null) {
        println("📡 Updating YouTube metadata for video ID: $videoId (Channel: ${targetChannelId ?: channelId})")
        val youtube = getYoutubeClient(targetChannelId)

        // 1. Get existing video snippet
        val listResponse = youtube.videos().list(listOf("snippet")).setId(listOf(videoId)).execute()
        if (listResponse.items.isEmpty()) {
            println("⚠️ Video not found on YouTube: $videoId")
            return
        }

        val video = listResponse.items.first()
        val snippet = video.snippet

        // 2. Update snippet fields
        if (title != null) {
            val sanitizedTitle = title
                .replace(Regex("[<>\"'/\\\\\\p{Cntrl}]"), "")
                .trim()
            snippet.title = if (sanitizedTitle.length > 100) sanitizedTitle.substring(0, 97) + "..." else sanitizedTitle
        }

        if (description != null) {
            val sanitizedDesc = description
                .replace("<", "")
                .replace(">", "")
            snippet.description = if (sanitizedDesc.length > 4000) sanitizedDesc.substring(0, 3997) + "..." else sanitizedDesc
        }

        // 3. Execute update
        youtube.videos().update(listOf("snippet"), video).execute()
        println("✅ Metadata updated successfully for video: $videoId")
    }

    fun setThumbnail(videoId: String, file: File, targetChannelId: String? = null) {
        if (!file.exists()) {
            println("⚠️ Thumbnail file not found: ${file.path}")
            return
        }

        println("🖼️ Setting custom thumbnail for video: $videoId (Channel: ${targetChannelId ?: channelId})")
        val youtube = getYoutubeClient(targetChannelId)
        
        try {
            val thumbContent = InputStreamContent("image/jpeg", FileInputStream(file))
            youtube.thumbnails().set(videoId, thumbContent).execute()
            println("✅ Thumbnail upload complete for video: $videoId")
        } catch (e: Exception) {
            println("❌ Failed to set thumbnail for $videoId: ${e.message}")
            throw e
        }
    }
}
