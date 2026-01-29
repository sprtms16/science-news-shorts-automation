package com.sciencepixel.batch

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.sciencepixel.domain.NewsItem
import org.springframework.batch.item.ItemReader
import java.net.URL
import java.util.LinkedList

open class RssItemReader(
    private val feedUrls: List<String>,
    private val maxItems: Int = 10
) : ItemReader<NewsItem> {
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
        val allItems = mutableListOf<NewsItem>()
        val seenLinks = mutableSetOf<String>()

        feedUrls.forEach { urlStr ->
            try {
                val url = URL(urlStr)
                val connection = url.openConnection()
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val stream = connection.getInputStream()
                val feed = SyndFeedInput().build(XmlReader(stream))
                
                // Fetch top 3 from each feed
                feed.entries.take(3).forEach { entry -> 
                    val link = entry.link ?: ""
                    if (link.isNotEmpty() && link !in seenLinks) {
                        seenLinks.add(link)
                        allItems.add(NewsItem(
                            title = entry.title,
                            summary = entry.description?.value ?: entry.title,
                            link = link
                        ))
                    }
                }
            } catch (e: Exception) {
                println("❌ RSS Fetch Error ($urlStr): ${e.message}")
            }
        }

        // Shuffle for diversity and take only up to maxItems
        allItems.shuffle()
        items.addAll(allItems.take(maxItems))
        println("✅ RSS Feed Fetched: ${items.size} unique items (Limit: $maxItems) from ${feedUrls.size} sources.")
    }
}
