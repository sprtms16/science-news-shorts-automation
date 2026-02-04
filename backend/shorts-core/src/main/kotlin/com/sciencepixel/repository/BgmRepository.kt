package com.sciencepixel.repository

import com.sciencepixel.domain.BgmEntity
import com.sciencepixel.domain.BgmStatus
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface BgmRepository : MongoRepository<BgmEntity, String> {
    fun findByStatus(status: BgmStatus): List<BgmEntity>
    fun findAllByOrderByCreatedAtDesc(): List<BgmEntity>
    fun findByMood(mood: String): List<BgmEntity>
    fun findByFilename(filename: String): BgmEntity?
}
