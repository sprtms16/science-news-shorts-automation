package com.sciencepixel.batch

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.sciencepixel.domain.NewsItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.springframework.batch.item.ItemReader
import java.io.ByteArrayInputStream
import java.util.LinkedList
import java.util.concurrent.TimeUnit

open class RssItemReader(
    private val sources: List<com.sciencepixel.domain.RssSource>,
    private val contentProviderService: com.sciencepixel.service.ContentProviderService,
    private val maxItems: Int = 10
) : ItemReader<NewsItem> {
    private val items = LinkedList<NewsItem>()
    private var initialized = false

    override fun read(): NewsItem? {
        if (!initialized) {
            fetchFeeds()
            initialized = true
        }
        return items.poll()
    }

    private fun fetchFeeds() {
        val allItems = mutableListOf<NewsItem>()
        val seenLinks = mutableSetOf<String>()

        sources.forEach { source ->
            try {
                processSource(source, allItems, seenLinks)
            } catch (e: Exception) {
                println("❌ Global Source Error (${source.title}): ${e.message}")
            }
        }

        allItems.shuffle()
        items.addAll(allItems.take(maxItems))
        println("✅ Batch Feed Fetched: ${items.size} unique items (Limit: $maxItems) from ${sources.size} sources.")
    }

    private fun processSource(source: com.sciencepixel.domain.RssSource, allItems: MutableList<NewsItem>, seenLinks: MutableSet<String>) {
        val fetchedItems = contentProviderService.fetchContent(source)
        
        fetchedItems.forEach { item ->
             if (item.link.isNotEmpty() && item.link !in seenLinks) {
                 seenLinks.add(item.link)
                 allItems.add(item)
             }
        }
    }
}
