package com.sciencepixel.repository

import com.sciencepixel.domain.VideoHistory
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface VideoHistoryRepository : MongoRepository<VideoHistory, String> {
    fun findByLink(link: String): VideoHistory?
}
