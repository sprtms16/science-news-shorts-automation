package com.sciencepixel.batch

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.sciencepixel.domain.NewsItem
import org.springframework.batch.item.ItemReader
import java.net.URL
import java.util.LinkedList

class RssItemReader(private val feedUrl: String) : ItemReader<NewsItem> {
    private val items = LinkedList<NewsItem>()
    private var initialized = false

    override fun read(): NewsItem? {
        if (!initialized) {
            fetchFeed()
            initialized = true
        }
        return items.poll()
    }

    private fun fetchFeed() {
        try {
            val url = URL(feedUrl)
            val connection = url.openConnection()
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            
            val stream = connection.getInputStream()
            val feed = SyndFeedInput().build(XmlReader(stream))
            
            feed.entries.take(3).forEach { entry -> // 최신 3개만
                items.add(NewsItem(
                    title = entry.title,
                    summary = entry.description?.value ?: entry.title,
                    link = entry.link
                ))
            }
        } catch (e: Exception) {
            println("❌ RSS Fetch Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
