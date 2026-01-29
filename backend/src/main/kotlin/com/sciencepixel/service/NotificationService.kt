package com.sciencepixel.service

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * 비디오 생성/업로드 완료 알림 서비스
 * Discord, Telegram 웹훅을 통해 알림 전송
 */
@Service
class NotificationService(
    @Value("\${notification.discord.webhook:}") private val discordWebhook: String,
    @Value("\${notification.telegram.bot-token:}") private val telegramBotToken: String,
    @Value("\${notification.telegram.chat-id:}") private val telegramChatId: String
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Discord로 알림 전송
     */
    fun sendDiscordNotification(
        title: String,
        description: String,
        youtubeUrl: String? = null,
        color: Int = 0x00FF00  // 기본: 초록색 (성공)
    ) {
        if (discordWebhook.isBlank()) {
            println("⚠️ Discord Webhook URL이 설정되지 않음")
            return
        }

        try {
            val embed = JSONObject().apply {
                put("title", title)
                put("description", description)
                put("color", color)
                if (youtubeUrl != null) {
                    put("url", youtubeUrl)
                }
                put("footer", JSONObject().put("text", "사이언스 픽셀 자동화 시스템"))
                put("timestamp", java.time.Instant.now().toString())
            }

            val payload = JSONObject().apply {
                put("embeds", org.json.JSONArray().put(embed))
            }

            val requestBody = RequestBody.create(
                "application/json".toMediaType(),
                payload.toString()
            )

            val request = Request.Builder()
                .url(discordWebhook)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                println("✅ Discord 알림 전송 성공")
            } else {
                println("❌ Discord 알림 전송 실패: ${response.code}")
            }
            response.close()
        } catch (e: Exception) {
            println("❌ Discord 알림 에러: ${e.message}")
        }
    }

    /**
     * Telegram으로 알림 전송
     */
    fun sendTelegramNotification(message: String) {
        if (telegramBotToken.isBlank() || telegramChatId.isBlank()) {
            println("⚠️ Telegram Bot Token 또는 Chat ID가 설정되지 않음")
            return
        }

        try {
            val url = "https://api.telegram.org/bot$telegramBotToken/sendMessage"
            
            val payload = JSONObject().apply {
                put("chat_id", telegramChatId)
                put("text", message)
                put("parse_mode", "HTML")
            }

            val requestBody = RequestBody.create(
                "application/json".toMediaType(),
                payload.toString()
            )

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                println("✅ Telegram 알림 전송 성공")
            } else {
                println("❌ Telegram 알림 전송 실패: ${response.code}")
            }
            response.close()
        } catch (e: Exception) {
            println("❌ Telegram 알림 에러: ${e.message}")
        }
    }

    /**
     * 비디오 생성 완료 알림
     */
    fun notifyVideoCreated(title: String, filePath: String) {
        val message = """
            🎬 비디오 생성 완료!
            
            📌 제목: $title
            📁 파일: $filePath
            
            YouTube 업로드 대기 중...
        """.trimIndent()

        sendDiscordNotification(
            title = "🎬 비디오 생성 완료",
            description = "**$title**\n\n파일: `$filePath`\n\nYouTube 업로드 대기 중...",
            color = 0xFFA500  // 주황색
        )
        sendTelegramNotification(message)
    }

    /**
     * YouTube 업로드 완료 알림
     */
    fun notifyUploadComplete(title: String, youtubeUrl: String) {
        val message = """
            ✅ YouTube 업로드 완료!
            
            📌 제목: $title
            🔗 링크: $youtubeUrl
        """.trimIndent()

        sendDiscordNotification(
            title = "✅ YouTube 업로드 완료!",
            description = "**$title**\n\n🔗 [$youtubeUrl]($youtubeUrl)",
            youtubeUrl = youtubeUrl,
            color = 0x00FF00  // 초록색
        )
        sendTelegramNotification(message)
    }

    /**
     * 에러 알림
     */
    fun notifyError(title: String, error: String) {
        val message = """
            ❌ 에러 발생!
            
            📌 제목: $title
            ⚠️ 에러: $error
        """.trimIndent()

        sendDiscordNotification(
            title = "❌ 에러 발생",
            description = "**$title**\n\n⚠️ $error",
            color = 0xFF0000  // 빨간색
        )
        sendTelegramNotification(message)
    }
}
