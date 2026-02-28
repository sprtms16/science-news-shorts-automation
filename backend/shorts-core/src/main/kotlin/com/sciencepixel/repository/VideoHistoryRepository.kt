package com.sciencepixel.repository

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.DuplicateLinkGroup
import com.sciencepixel.domain.VideoStatus
import org.springframework.data.mongodb.repository.Aggregation
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface VideoHistoryRepository : MongoRepository<VideoHistory, String> {
    fun findFirstByChannelIdAndLinkOrderByCreatedAtDesc(channelId: String, link: String): VideoHistory?
    fun findByChannelIdAndStatus(channelId: String, status: VideoStatus): List<VideoHistory>
    fun findByChannelIdAndStatusIn(channelId: String, statuses: Collection<VideoStatus>): List<VideoHistory>
    fun findByStatusIn(statuses: Collection<VideoStatus>): List<VideoHistory>
    fun findByStatusNot(status: VideoStatus): List<VideoHistory>
    fun findByChannelIdAndStatusNot(channelId: String, status: VideoStatus): List<VideoHistory>
    fun findByChannelIdAndStatusNotIn(channelId: String, statuses: Collection<VideoStatus>): List<VideoHistory>
    fun findByChannelIdAndTitle(channelId: String, title: String): List<VideoHistory>
    fun findByChannelIdAndRssTitle(channelId: String, rssTitle: String): List<VideoHistory>
    fun existsByChannelIdAndLink(channelId: String, link: String): Boolean
    fun existsByChannelIdAndLinkAndStatusIn(channelId: String, link: String, statuses: Collection<VideoStatus>): Boolean
    
    // Admin 전용: 모든 채널 데이터 조회용
    fun findByChannelId(channelId: String): List<VideoHistory>
    fun findFirstByChannelIdAndStatusOrderByUpdatedAtDesc(channelId: String, status: VideoStatus): VideoHistory?
    fun findByChannelId(channelId: String, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<VideoHistory>
    fun findAllByChannelIdOrderByCreatedAtDesc(channelId: String, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<VideoHistory>

    @Aggregation(pipeline = [
        "{ \$match: { channelId: ?0 } }",
        "{ \$group: { _id: '\$link', count: { \$sum: 1 }, docs: { \$push: '\$\$ROOT' } } }",
        "{ \$match: { count: { \$gt: 1 } } }"
    ])
    fun findDuplicateLinks(channelId: String): List<DuplicateLinkGroup>

    // Daily Limit Check
    fun countByChannelIdAndStatusInAndCreatedAtAfter(channelId: String, statuses: Collection<VideoStatus>, date: java.time.LocalDateTime): Long

    // Retry Logic Queries
    fun findTop5ByChannelIdAndStatusOrderByUpdatedAtAsc(channelId: String, status: VideoStatus): List<VideoHistory>
    fun findByChannelIdAndStatusAndUpdatedAtBefore(channelId: String, status: VideoStatus, time: java.time.LocalDateTime): List<VideoHistory>
    fun findFirstByChannelIdAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(channelId: String, status: VideoStatus, time: java.time.LocalDateTime): VideoHistory?
}
