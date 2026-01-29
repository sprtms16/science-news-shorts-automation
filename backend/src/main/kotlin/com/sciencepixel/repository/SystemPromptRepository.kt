package com.sciencepixel.repository

import com.sciencepixel.domain.SystemPrompt
import org.springframework.data.mongodb.repository.MongoRepository

interface SystemPromptRepository : MongoRepository<SystemPrompt, String>
