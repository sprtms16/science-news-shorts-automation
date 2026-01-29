package com.sciencepixel.repository

import com.sciencepixel.domain.RssSource
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface RssSourceRepository : MongoRepository<RssSource, String> {
    fun findByIsActiveTrue(): List<RssSource>
    fun findByUrl(url: String): RssSource?
}
