package com.sciencepixel.service

import com.sciencepixel.domain.QuotaUsage
import com.sciencepixel.repository.QuotaUsageRepository
import com.sciencepixel.repository.SystemSettingRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * YouTube API 할당량 추적 및 관리 서비스
 */
@Service
class QuotaTracker(
    private val repository: QuotaUsageRepository,
    private val systemSettingRepository: SystemSettingRepository,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    private val dateFormatter = DateTimeFormatter.ISO_DATE

    companion object {
        const val DEFAULT_DAILY_QUOTA_LIMIT = 10000
        const val UPLOAD_COST = 1600
    }

    private fun getDailyLimit(): Int {
        return systemSettingRepository.findByChannelIdAndKey(channelId, "YOUTUBE_DAILY_QUOTA_LIMIT")
            ?.value?.toIntOrNull() ?: DEFAULT_DAILY_QUOTA_LIMIT
    }

    /**
     * 현재 업로드 가능한지 확인 (할당량 소진 여부 체크)
     */
    fun canUpload(): Boolean {
        val suspended = systemSettingRepository.findByChannelIdAndKey(channelId, "YOUTUBE_UPLOAD_SUSPENDED")
            ?.value == "true"
        
        return !suspended
    }

    /**
     * 할당량 초과 시 호출하여 업로드를 중단시킴
     */
    fun setSuspended(reason: String = "Quota Exceeded") {
        val existing = systemSettingRepository.findByChannelIdAndKey(channelId, "YOUTUBE_UPLOAD_SUSPENDED")
        
        val setting = existing?.copy(
            value = "true",
            description = "YouTube Upload Suspended: $reason",
            updatedAt = LocalDateTime.now()
        ) ?: com.sciencepixel.domain.SystemSetting(
            channelId = channelId,
            key = "YOUTUBE_UPLOAD_SUSPENDED",
            value = "true",
            description = "YouTube Upload Suspended: $reason",
            updatedAt = LocalDateTime.now()
        )

        systemSettingRepository.save(setting)
        println("🛑 YouTube Upload SUSPENDED ($channelId): $reason")
    }

    /**
     * 할당량 사용량 강제 초기화 (매일 16시 자동 실행)
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 16 * * *")
    fun resetQuota() {
        val existing = systemSettingRepository.findByChannelIdAndKey(channelId, "YOUTUBE_UPLOAD_SUSPENDED")
        
        val setting = existing?.copy(
            value = "false",
            description = "YouTube Upload Resumed after Reset",
            updatedAt = LocalDateTime.now()
        ) ?: com.sciencepixel.domain.SystemSetting(
            channelId = channelId,
            key = "YOUTUBE_UPLOAD_SUSPENDED",
            value = "false",
            description = "YouTube Upload Resumed after Reset",
            updatedAt = LocalDateTime.now()
        )

        systemSettingRepository.save(setting)
        println("🔄 YouTube Daily Quota Reset ($channelId): Uploading resumed at 16:00 KST.")
    }

    /**
     * 남은 할당량 추정 (UI 표시용 - 이제 에러 기반이므로 단순화)
     */
    fun isSuspended(): Boolean = !canUpload()
}

