package com.sciencepixel.config

import com.sciencepixel.domain.RssSource
import com.sciencepixel.repository.RssSourceRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val rssSourceRepository: RssSourceRepository
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        if (rssSourceRepository.count() == 0L) {
            println("🌱 Initializing RSS Sources logic configuration in DB...")
            
            val initialSources = listOf(
                RssSource(url = "https://www.wired.com/feed/rss", title = "Wired", category = "Tech"),
                RssSource(url = "https://www.theverge.com/rss/index.xml", title = "The Verge", category = "Tech"),
                RssSource(url = "https://www.engadget.com/rss.xml", title = "Engadget", category = "Tech"),
                RssSource(url = "https://www.sciencedaily.com/rss/all.xml", title = "Science Daily", category = "Science"),
                RssSource(url = "http://www.nature.com/nature.rss", title = "Nature", category = "Science"),
                RssSource(url = "http://rss.sciam.com/ScientificAmerican-Global", title = "Scientific American", category = "Science"),
                RssSource(url = "https://news.hada.io/rss/news", title = "GeekNews", category = "Tech"),
                RssSource(url = "https://zdnet.co.kr/feed", title = "ZDNet Korea", category = "Tech"),
                RssSource(url = "https://www.itworld.co.kr/news/rss", title = "IT World", category = "Tech"),
                RssSource(url = "https://rss.etnews.com/Section901.xml", title = "ET News", category = "Tech"),
                RssSource(url = "https://tech.kakao.com/feed/", title = "Kakao Tech", category = "Tech"),
                RssSource(url = "https://news.ycombinator.com/rss", title = "Hacker News", category = "Tech")
            )
            
            rssSourceRepository.saveAll(initialSources)
            println("✅ Seeded ${initialSources.size} RSS sources.")
        }
    }
}
