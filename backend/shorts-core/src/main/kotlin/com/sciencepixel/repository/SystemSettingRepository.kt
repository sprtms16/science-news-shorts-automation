package com.sciencepixel.repository

import com.sciencepixel.domain.SystemSetting
import org.springframework.data.mongodb.repository.MongoRepository

interface SystemSettingRepository : MongoRepository<SystemSetting, String> {
    fun findByChannelIdAndKey(channelId: String, key: String): SystemSetting?
    fun findByChannelId(channelId: String): List<SystemSetting>
}
