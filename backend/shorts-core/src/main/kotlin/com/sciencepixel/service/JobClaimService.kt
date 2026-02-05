package com.sciencepixel.service

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * MongoDB를 사용한 분산 락 서비스
 * 원자적 상태 전환으로 중복 실행 방지
 */
@Service
class JobClaimService(
    private val mongoTemplate: MongoTemplate
) {
    /**
     * 원자적으로 비디오 상태 전환 시도
     * 
     * @param videoId 대상 비디오 ID
     * @param fromStatus 현재 상태 (이 상태여야만 전환됨)
     * @param toStatus 변경할 상태
     * @return 성공 시 true, 이미 다른 프로세스가 선점한 경우 false
     */
    fun claimJob(videoId: String, fromStatus: VideoStatus, toStatus: VideoStatus): Boolean {
        val result = mongoTemplate.findAndModify(
            Query.query(
                Criteria.where("_id").`is`(videoId)
                    .and("status").`is`(fromStatus)
            ),
            Update.update("status", toStatus)
                .set("updatedAt", LocalDateTime.now()),
            FindAndModifyOptions.options().returnNew(false),
            VideoHistory::class.java
        )
        
        if (result != null) {
            println("🔒 [JobClaimService] Claimed: $videoId ($fromStatus → $toStatus)")
        } else {
            println("⏭️ [JobClaimService] Skip: $videoId is not in $fromStatus state")
        }
        
        return result != null
    }
    
    /**
     * 여러 상태 중 하나에서 전환 시도
     */
    fun claimJobFromAny(videoId: String, fromStatuses: List<VideoStatus>, toStatus: VideoStatus): Boolean {
        val result = mongoTemplate.findAndModify(
            Query.query(
                Criteria.where("_id").`is`(videoId)
                    .and("status").`in`(fromStatuses)
            ),
            Update.update("status", toStatus)
                .set("updatedAt", LocalDateTime.now()),
            FindAndModifyOptions.options().returnNew(false),
            VideoHistory::class.java
        )
        
        if (result != null) {
            println("🔒 [JobClaimService] Claimed: $videoId (${result.status} → $toStatus)")
        } else {
            println("⏭️ [JobClaimService] Skip: $videoId is not in any of $fromStatuses")
        }
        
        return result != null
    }
}
