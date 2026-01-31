package com.sciencepixel.repository

import com.sciencepixel.domain.YoutubeVideoEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface YoutubeVideoRepository : MongoRepository<YoutubeVideoEntity, String> {
    fun findByTitleContainingIgnoreCase(title: String): List<YoutubeVideoEntity>
    fun findAllByOrderByPublishedAtDesc(pageable: Pageable): Page<YoutubeVideoEntity>
}
