package com.sciencepixel.log.repository

import com.sciencepixel.log.domain.LogEntry
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface LogRepository : MongoRepository<LogEntry, String> {
    fun findByServiceName(serviceName: String): List<LogEntry>
    fun findByLevel(level: String): List<LogEntry>
}
