package com.sciencepixel.repository

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.DuplicateLinkGroup
import com.sciencepixel.domain.VideoStatus
import org.springframework.data.mongodb.repository.Aggregation
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface VideoHistoryRepository : MongoRepository<VideoHistory, String> {
    fun findByLink(link: String): VideoHistory?
    fun findByStatus(status: VideoStatus): List<VideoHistory>
    fun findByStatusIn(statuses: Collection<VideoStatus>): List<VideoHistory>
    fun findByStatusNot(status: VideoStatus): List<VideoHistory>
    fun findByStatusNotIn(statuses: Collection<VideoStatus>): List<VideoHistory>
    fun findByTitle(title: String): List<VideoHistory>
    fun findByRssTitle(rssTitle: String): List<VideoHistory>

    @Aggregation(pipeline = [
        "{ \$group: { _id: '\$link', count: { \$sum: 1 }, docs: { \$push: '\$\$ROOT' } } }",
        "{ \$match: { count: { \$gt: 1 } } }"
    ])
    fun findDuplicateLinks(): List<DuplicateLinkGroup>
}
