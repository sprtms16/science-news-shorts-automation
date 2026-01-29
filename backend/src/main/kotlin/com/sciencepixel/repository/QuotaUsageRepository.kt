package com.sciencepixel.repository

import com.sciencepixel.domain.QuotaUsage
import org.springframework.data.mongodb.repository.MongoRepository

interface QuotaUsageRepository : MongoRepository<QuotaUsage, String>
