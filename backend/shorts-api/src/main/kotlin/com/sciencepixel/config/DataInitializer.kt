package com.sciencepixel.config

import com.sciencepixel.domain.RssSource
import com.sciencepixel.repository.RssSourceRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val rssSourceRepository: RssSourceRepository,
    private val systemSettingRepository: com.sciencepixel.repository.SystemSettingRepository
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        seedDefaults()
    }

    fun resetAndSeedFactoryDefaults() {
        println("♻️ Resetting all sources and settings to factory defaults...")
        rssSourceRepository.deleteAll()
        systemSettingRepository.deleteAll() // Clear settings to enforce new defaults
        seedDefaults()
    }

    private fun seedDefaults() {
        val allSources = listOf(
            // [Science] existing...
            RssSource(channelId = "science", url = "https://www.wired.com/feed/rss", title = "Wired", category = "Tech", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "science", url = "https://www.sciencedaily.com/rss/all.xml", title = "Science Daily", category = "Science", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "science", url = "http://www.nature.com/nature.rss", title = "Nature", category = "Science", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "science", url = "https://news.ycombinator.com/rss", title = "Hacker News", category = "Tech", type = com.sciencepixel.domain.SourceType.RSS),
            
            // [Horror] Reddit
            RssSource(channelId = "horror", url = "https://www.reddit.com/r/NoSleep/top.json?t=week", title = "NoSleep", category = "Horror", type = com.sciencepixel.domain.SourceType.REDDIT_JSON),
            RssSource(channelId = "horror", url = "https://www.reddit.com/r/TwoSentenceHorror/top.json?t=week", title = "TwoSentenceHorror", category = "Horror", type = com.sciencepixel.domain.SourceType.REDDIT_JSON),
            RssSource(channelId = "horror", url = "https://www.reddit.com/r/Glitch_in_the_Matrix/top.json?t=month", title = "GlitchIntheMatrix", category = "Mystery", type = com.sciencepixel.domain.SourceType.REDDIT_JSON),
            RssSource(channelId = "horror", url = "https://www.reddit.com/r/LetsNotMeet/top.json?t=month", title = "LetsNotMeet", category = "Horror", type = com.sciencepixel.domain.SourceType.REDDIT_JSON),

            // [Stocks] Finance News
            RssSource(channelId = "stocks", url = "https://feeds.content.dowjones.io/public/rss/mw_topstories", title = "MarketWatch", category = "Finance", type = com.sciencepixel.domain.SourceType.STOCK_NEWS),
            RssSource(channelId = "stocks", url = "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10000664", title = "CNBC Finance", category = "Finance", type = com.sciencepixel.domain.SourceType.STOCK_NEWS),
            RssSource(channelId = "stocks", url = "http://feeds.reuters.com/reuters/businessNews", title = "Reuters Business", category = "Finance", type = com.sciencepixel.domain.SourceType.STOCK_NEWS),

            // [History] Wikipedia
            RssSource(channelId = "history", url = "https://en.wikipedia.org/api/rest_v1/feed/onthisday/events", title = "Wikipedia On This Day", category = "History", type = com.sciencepixel.domain.SourceType.WIKIPEDIA_ON_THIS_DAY)
        )

        var addedCount = 0
        for (source in allSources) {
            try {
                // Check dupes manually or rely on unique index (which might throw)
                // Using find to be safe
                val exists = rssSourceRepository.findAll().any { it.channelId == source.channelId && it.url == source.url }
                if (!exists) {
                    rssSourceRepository.save(source)
                    addedCount++
                }
            } catch (e: Exception) {
                // Ignore duplicates
            }
        }
        
        if (addedCount > 0) {
            println("✅ Seeded $addedCount new sources (Reddit, Finance, History).")
        }
        
        // Seed Settings
        seedSettings()
    }

    private fun seedSettings() {
        val defaultInterval = "1" // Default 1 Hour
        val channelConfigs = mapOf(
            "science" to defaultInterval,
            "horror" to defaultInterval,
            "stocks" to "24",  // 1 per day
            "history" to "24"  // 1 per day
        )

        channelConfigs.forEach { (channelId, interval) ->
            try {
               if (systemSettingRepository.findByChannelIdAndKey(channelId, "UPLOAD_INTERVAL_HOURS") == null) {
                   systemSettingRepository.save(com.sciencepixel.domain.SystemSetting(
                       channelId = channelId,
                       key = "UPLOAD_INTERVAL_HOURS",
                       value = interval,
                       description = "Hours between uploads"
                   ))
                   println("✅ Seeded UPLOAD_INTERVAL_HOURS=$interval for $channelId")
               }
            } catch (e: Exception) {
               // Ignore
            }
        }
    }
}
