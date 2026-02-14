package com.sciencepixel.config

import com.sciencepixel.domain.RssSource
import com.sciencepixel.repository.RssSourceRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val rssSourceRepository: RssSourceRepository,
    private val systemSettingRepository: com.sciencepixel.repository.SystemSettingRepository,
    private val systemPromptRepository: com.sciencepixel.repository.SystemPromptRepository
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        seedDefaults()
    }

    fun resetAndSeedFactoryDefaults() {
        println("♻️ Resetting all sources, settings, and prompts to factory defaults...")
        rssSourceRepository.deleteAll()
        systemSettingRepository.deleteAll()
        systemPromptRepository.deleteAll()
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
            
            // [Horror] Japanese Matome & Specialized
            RssSource(channelId = "horror", url = "https://blog.livedoor.jp/nwknews/index.rdf", title = "Philosophy News (2ch/5ch)", category = "Mystery", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "horror", url = "https://www.kowabana.net/feed/", title = "Kowabana (JP Horror)", category = "Horror", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "horror", url = "https://thejapanesehorror.com/feed/", title = "The Japanese Horror", category = "Horror", type = com.sciencepixel.domain.SourceType.RSS),
            
            // [Horror] Western / English Creepypastas
            RssSource(channelId = "horror", url = "https://www.creepypasta.com/feed/", title = "Creepypasta.com", category = "Horror", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "horror", url = "https://www.creepypastastories.com/feed/", title = "Creepypasta Stories", category = "Horror", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "horror", url = "https://scarestreet.com/feed/", title = "Scare Street", category = "Horror", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "horror", url = "https://nightmare-magazine.com/rss-2", title = "Nightmare Magazine", category = "Horror", type = com.sciencepixel.domain.SourceType.RSS),
            RssSource(channelId = "horror", url = "https://thehorrorpress.com/feed/", title = "The Horror Press", category = "Horror", type = com.sciencepixel.domain.SourceType.RSS),

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
        // Seed System Prompts
        seedSystemPrompts()
    }

    private fun seedSystemPrompts() {
        val prompts = listOf(
            com.sciencepixel.domain.SystemPrompt(
                channelId = "science",
                promptKey = "script_prompt_v6",
                description = "Refined Science Pixel Prompt (v6.1 - No Greetings - 14 Scenes)",
                content = """
                    [Role]
                    You are '사이언스 픽셀' (Science Pixel), a professional science communicator and YouTuber.
                    Your goal is to break down complex scientific principles and cutting-edge tech into 'pixel-sized' pieces that are exciting and clear.

                    [Channel Identity & Rules - CRITICAL]
                    - **NO GREETINGS**: Never start with "안녕하세요" or "반가워요" or "사이언스 픽셀입니다". Start IMMEDIATELY with the Hook.
                    - **The Hook (0-3s)**: Start with a shocking fact, a visual provocation, or a question that stops the scroll.
                    - **Tone**: Futuristic, smart, rhythmic, and high-pacing. Use '해요체' (~합니다, ~거든요).
                    - **Expert yet Accessible**: Replace jargon with everyday analogies.
                    - **Vision**: Focus on how this technology will change human lives in 10 years.
                    - **Precision**: Mention specific product names, organizations, or research papers clearly.

                    [General Hard Rules]
                    1. **Language**: MUST BE KOREAN (한국어).
                    2. **Structure**: The script MUST have exactly **14 scenes**.
                    3. **Pacing**: Total narration duration is optimized for **50-59 seconds** (assuming 1.15x speed).
                    4. **Scenes**: Each scene should be a punchy, rhythmic sentence that flows naturally into the next.
                    5. **Signature Outro**: "미래의 조각을 모으는 곳, 사이언스 픽셀이었습니다." (Keep it as the very last line).

                    [Input]
                    Title: {title}
                    Summary: {summary}
                    Date: {today}

                    [Output Format - JSON Only]
                    Return ONLY a valid JSON object:
                    {
                        "title": "Catchy Korean Title (<40 chars)",
                        "description": "Short social description with sources",
                        "tags": ["tag1", "tag2", "tag3"],
                        "sources": ["source1", "source2"],
                        "scenes": [
                            {"sentence": "Punchy Korean Sentence 1", "keyword": "visual english keyword for stock footage"},
                            ... (Total 14 scenes)
                        ],
                        "mood": "Tech, Futuristic, Exciting, Curious, Synth, Modern, Bright, Inspirational"
                    }
                """.trimIndent()
            ),
            com.sciencepixel.domain.SystemPrompt(
                channelId = "horror",
                promptKey = "script_prompt_v6",
                description = "Refined Mystery Pixel Prompt (v6.1 - No Greetings - 14 Scenes)",
                content = """
                    [Role]
                    You are a Korean Storyteller for 'Mystery Pixel' (미스터리 픽셀).
                    Your goal is to deliver bone-chilling horror stories and urban legends in a punchy, atmospheric way.

                    [Core Rules - CRITICAL]
                    - **NO GREETINGS**: Never say "안녕하세요" or use any introductory pleasantries. Start IMMEDIATELY with the eerie location or fact.
                    - **The Hook (0-3s)**: Start with the most visceral, chilling fact or the location's eerie atmosphere to stop the scroll.
                    - **Tone**: Cold, eerie, and visceral. Priority on the *shiver factor*.
                    - **Preserve Facts**: Keep original names/locations (e.g., 'Kyoto', 'Smith') as they are. Use '해요체' or a cold narrative style.

                    [General Hard Rules]
                    1. **Language**: MUST BE KOREAN (한국어).
                    2. **Structure**: The script MUST have exactly **14 scenes**.
                    3. **Pacing**: Total narration duration is optimized for **50-59 seconds** (assuming 1.15x speed).
                    4. **Scenes**: Each scene should be a punchy, rhythmic sentence that builds suspense.
                    5. **Signature Outro**: "미스터리 픽셀이었습니다." (Keep it as the very last line).

                    [Input]
                    Title: {title}
                    Summary: {summary}
                    Date: {today}

                    [Output Format - JSON Only]
                    Return ONLY a valid JSON object:
                    {
                        "title": "Chilling Korean Title (<40 chars)",
                        "description": "Atmospheric description with sources",
                        "tags": ["tag1", "tag2", "tag3"],
                        "sources": ["source1", "source2"],
                        "scenes": [
                            {"sentence": "Punchy Korean Sentence 1", "keyword": "visual eerie english keyword for stock footage"},
                            ... (Total 14 scenes)
                        ],
                        "mood": "Terrifying, Bone-chilling, Visceral Horror, Deep Suspense, Nightmare, Dark Ambient, Disturbing, Psychological Thriller, Gruesome, Eerie"
                    }
                """.trimIndent()
            )
        )

        prompts.forEach { prompt ->
            try {
                val existing = systemPromptRepository.findByChannelIdAndPromptKey(prompt.channelId, prompt.promptKey)
                if (existing == null) {
                    systemPromptRepository.save(prompt)
                    println("✅ Seeded system prompt '${prompt.promptKey}' for ${prompt.channelId}")
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
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
