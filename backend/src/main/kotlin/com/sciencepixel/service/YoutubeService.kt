package com.sciencepixel.service

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoSnippet
import com.google.api.services.youtube.model.VideoStatus
import com.google.api.client.http.InputStreamContent
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.CountDownLatch

@Service
class YoutubeService(private val quotaTracker: QuotaTracker) {

    private val JSON_FACTORY = JacksonFactory.getDefaultInstance()
    private val TOKENS_DIRECTORY_PATH = "tokens"
    private val CREDENTIALS_FILE_PATH = "/client_secret.json"
    private val SCOPES = Collections.singletonList("https://www.googleapis.com/auth/youtube.upload")
    private val APPLICATION_NAME = "Science News Shorts Automation"

    private fun getFlow(): GoogleAuthorizationCodeFlow {
        val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        
        // Try to load from resources or root
        val inStream = YoutubeService::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH) 
            ?: FileInputStream("client_secret.json")

        val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(inStream))

        return GoogleAuthorizationCodeFlow.Builder(
            httpTransport, JSON_FACTORY, clientSecrets, SCOPES
        )
        .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
        .setAccessType("offline")
        .build()
    }

    private fun getCredentials(): Credential {
        val flow = getFlow()
        // Try to load existing credential
        var credential = flow.loadCredential("user")
        
        // Check if credential exists and is valid (or refreshable)
        if (credential != null && (credential.refreshToken != null || credential.expiresInSeconds == null || credential.expiresInSeconds > 60)) {
            return credential
        }
        
        // Try to refresh if possible
        try {
            if (credential != null && credential.refreshToken != null) {
                if (credential.refreshToken()) {
                    return credential
                }
            }
        } catch (e: Exception) {
            println("⚠️ Refresh failed, re-authenticating...")
        }

        // Needs new Auth
        triggerAuthFlow(flow)
        
        // After flow (and manual callback interaction), try load again
        // Note: In strict sync logic, we'd wait here. But since we throw exception instructions,
        // the user is expected to retry the job after auth.
        // However, if we block via Latch in triggerAuthFlow (not implemented here due to complexity), we could wait.
        // For now, we throw exception to stop the process and ask user to auth.
        credential = flow.loadCredential("user")
        if (credential == null) {
             throw RuntimeException("Authorization failed or timed out. Please authenticate via the URL in logs.")
        }
        return credential
    }
    
    // Explicitly public so Controller can call it
    fun triggerAuthFlow(flow: GoogleAuthorizationCodeFlow) {
        val redirectUri = "http://localhost:8080/callback"
        val authUrl = flow.newAuthorizationUrl().setRedirectUri(redirectUri).setAccessType("offline").build()
        
        println("👉 Please open the following URL to authorize:")
        println(authUrl)
        println("Waiting for callback on $redirectUri ...")
        
        // We throw here to indicate strict intervention needed. 
        // The batch job will fail, but the user can click the link, Auth, and then retry.
        throw RuntimeException("YouTube Auth Required. Visit URL in logs (Docker Console) and retry.")
    }

    // Called by OAuthController
    fun exchangeCodeForToken(code: String, redirectUri: String) {
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

    fun uploadVideo(file: File, title: String, description: String, tags: List<String>): String {
        if (!quotaTracker.canUpload()) {
            throw RuntimeException("YouTube API Quota Exceeded for today. Remaining uploads: ${quotaTracker.getRemainingUploads()}")
        }

        println("📡 Connecting to YouTube API (Google Client)...")
        val credential = getCredentials() // might throw
        
        val youtube = YouTube.Builder(
            GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential
        ).setApplicationName(APPLICATION_NAME).build()

        println("📤 Preparing Upload: $title")

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
        val response = request.execute()
        
        // 업로드 성공 시 할당량 기록
        quotaTracker.recordUpload()
        
        println("✅ YouTube Upload Complete! ID: ${response.id}")
        return "https://youtu.be/${response.id}"
    }
}
