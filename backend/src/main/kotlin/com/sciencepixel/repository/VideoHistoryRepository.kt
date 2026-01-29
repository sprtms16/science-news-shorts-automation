package com.sciencepixel.repository

import com.sciencepixel.domain.VideoHistory
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface VideoHistoryRepository : MongoRepository<VideoHistory, String> {
    fun findByLink(link: String): VideoHistory?
    fun findByStatus(status: VideoStatus): List<VideoHistory>
    fun findByStatusIn(statuses: Collection<VideoStatus>): List<VideoHistory>
    fun findByStatusNot(status: VideoStatus): List<VideoHistory>
}
