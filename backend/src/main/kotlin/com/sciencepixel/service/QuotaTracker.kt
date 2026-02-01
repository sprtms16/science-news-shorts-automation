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
    private val systemSettingRepository: SystemSettingRepository
) {

    companion object {
        const val DEFAULT_DAILY_QUOTA_LIMIT = 10000
        const val UPLOAD_COST = 1600
    }

    private fun getDailyLimit(): Int {
        return systemSettingRepository.findById("YOUTUBE_DAILY_QUOTA_LIMIT")
            .map { it.value.toIntOrNull() ?: DEFAULT_DAILY_QUOTA_LIMIT }
            .orElse(DEFAULT_DAILY_QUOTA_LIMIT)
    }

    /**
     * 현재 업로드 가능한지 확인
     */
    fun canUpload(): Boolean {
        val quota = getOrCreateQuota()
        return (quota.usedUnits + UPLOAD_COST) <= getDailyLimit()
    }

    /**
     * 업로드 비용 기록
     */
    fun recordUpload() {
        val quota = getOrCreateQuota()
        val limit = getDailyLimit()
        repository.save(quota.copy(
            usedUnits = quota.usedUnits + UPLOAD_COST,
            updatedAt = LocalDateTime.now()
        ))
        println("📊 YouTube Quota Updated: ${quota.usedUnits + UPLOAD_COST} / $limit")
    }

    /**
     * 할당량 사용량 강제 초기화
     */
    fun resetQuota() {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val newQuota = QuotaUsage(
            id = "youtube_upload",
            usedUnits = 0,
            date = today,
            updatedAt = LocalDateTime.now()
        )
        repository.save(newQuota)
        println("🔄 YouTube Daily Quota units reset to 0 for $today")
    }

    /**
     * 남은 할당량 (업로드 가능 횟수) 반환
     */
    fun getRemainingUploads(): Int {
        val quota = getOrCreateQuota()
        val limit = getDailyLimit()
        val remainingUnits = limit - quota.usedUnits
        return remainingUnits / UPLOAD_COST
    }

    private fun getOrCreateQuota(): QuotaUsage {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val existing = repository.findById("youtube_upload").orElse(null)

        return if (existing == null || existing.date != today) {
            // 날짜가 바뀌었거나 레코드가 없으면 초기화
            val newQuota = QuotaUsage(
                id = "youtube_upload",
                usedUnits = 0,
                date = today,
                updatedAt = LocalDateTime.now()
            )
            repository.save(newQuota)
        } else {
            existing
        }
    }
}
