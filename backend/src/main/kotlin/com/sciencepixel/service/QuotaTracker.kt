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
    private val dateFormatter = DateTimeFormatter.ISO_DATE

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
     * 할당량 사용량 강제 초기화 (매일 16시 자동 실행 및 수동 호출)
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 16 * * *")
    fun resetQuota() {
        val quotaDate = getCurrentQuotaDate()
        val newQuota = QuotaUsage(
            id = "youtube_upload",
            usedUnits = 0,
            date = quotaDate,
            updatedAt = LocalDateTime.now()
        )
        repository.save(newQuota)
        println("🔄 YouTube Daily Quota units reset to 0 for period starting at $quotaDate (Reset triggered at 16:00 or manually)")
    }

    private fun getCurrentQuotaDate(): String {
        val now = LocalDateTime.now()
        // 16시(오후 4시)를 기준으로 할당량이 초기화됨
        val quotaDate = if (now.hour < 16) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
        return quotaDate.format(dateFormatter)
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
        val quotaDate = getCurrentQuotaDate()
        val existing = repository.findById("youtube_upload").orElse(null)

        return if (existing == null || existing.date != quotaDate) {
            // 날짜(제한 기준)가 바뀌었거나 레코드가 없으면 초기화
            val newQuota = QuotaUsage(
                id = "youtube_upload",
                usedUnits = 0,
                date = quotaDate,
                updatedAt = LocalDateTime.now()
            )
            repository.save(newQuota)
        } else {
            existing
        }
    }
}
