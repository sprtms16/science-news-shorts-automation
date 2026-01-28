package com.sciencepixel.service

import com.sciencepixel.domain.QuotaUsage
import com.sciencepixel.domain.QuotaUsageRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * YouTube API 할당량 추적 및 관리 서비스
 */
@Service
class QuotaTracker(private val repository: QuotaUsageRepository) {

    companion object {
        const val DAILY_QUOTA_LIMIT = 10000
        const val UPLOAD_COST = 1600
    }

    /**
     * 현재 업로드 가능한지 확인
     */
    fun canUpload(): Boolean {
        val quota = getOrCreateQuota()
        return (quota.usedUnits + UPLOAD_COST) <= DAILY_QUOTA_LIMIT
    }

    /**
     * 업로드 비용 기록
     */
    fun recordUpload() {
        val quota = getOrCreateQuota()
        repository.save(quota.copy(
            usedUnits = quota.usedUnits + UPLOAD_COST,
            updatedAt = LocalDateTime.now()
        ))
        println("📊 YouTube Quota Updated: ${quota.usedUnits + UPLOAD_COST} / $DAILY_QUOTA_LIMIT")
    }

    /**
     * 남은 할당량 (업로드 가능 횟수) 반환
     */
    fun getRemainingUploads(): Int {
        val quota = getOrCreateQuota()
        val remainingUnits = DAILY_QUOTA_LIMIT - quota.usedUnits
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
