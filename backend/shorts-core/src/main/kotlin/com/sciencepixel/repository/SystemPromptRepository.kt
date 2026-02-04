package com.sciencepixel.repository

import com.sciencepixel.domain.SystemPrompt
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface SystemPromptRepository : MongoRepository<SystemPrompt, String> {
    fun findByChannelIdAndPromptKey(channelId: String, promptKey: String): SystemPrompt?
    fun findByChannelId(channelId: String): List<SystemPrompt>
}
