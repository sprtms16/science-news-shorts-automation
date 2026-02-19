package com.sciencepixel.repository

import com.sciencepixel.domain.PexelsVideoCache
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface PexelsVideoCacheRepository : MongoRepository<PexelsVideoCache, String> {
    fun findByKeyword(keyword: String): List<PexelsVideoCache>
    fun findByPexelsVideoId(pexelsVideoId: String): PexelsVideoCache?
    fun existsByPexelsVideoId(pexelsVideoId: String): Boolean
    fun findByChannelIdIn(channelIds: List<String>): List<PexelsVideoCache>
    fun findByLastUsedAtBefore(date: LocalDateTime): List<PexelsVideoCache>
    fun countByKeyword(keyword: String): Long
}
