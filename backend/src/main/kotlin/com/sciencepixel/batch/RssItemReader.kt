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
    private val feedUrls: List<String>,
    private val maxItems: Int = 10
) : ItemReader<NewsItem> {
    private val items = LinkedList<NewsItem>()
    private var initialized = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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

        feedUrls.forEach { urlStr ->
            try {
                processUrl(urlStr, allItems, seenLinks)
            } catch (e: Exception) {
                println("❌ RSS Global Error ($urlStr): ${e.message}")
            }
        }

        allItems.shuffle()
        items.addAll(allItems.take(maxItems))
        println("✅ RSS Feed Fetched: ${items.size} unique items (Limit: $maxItems) from ${feedUrls.size} sources.")
    }

    private fun processUrl(urlStr: String, allItems: MutableList<NewsItem>, seenLinks: MutableSet<String>, depth: Int = 0) {
        if (depth > 1) return // Prevent infinite discovery loops

        println("📡 Fetching: $urlStr")
        val request = Request.Builder()
            .url(urlStr)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "application/rss+xml, application/xml, text/xml, text/html")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("❌ HTTP Error ($urlStr): ${response.code}")
                return
            }

            val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
            val bodyBytes = response.body?.bytes() ?: return

            if (contentType.contains("html") || Jsoup.parse(String(bodyBytes)).select("link[rel=alternate]").isNotEmpty()) {
                // Try RSS Discovery
                val doc = Jsoup.parse(String(bodyBytes), urlStr)
                val rssLink = doc.select("link[rel=alternate][type*=xml], link[rel=alternate][type*=rss], link[rel=alternate][type*=atom]")
                    .firstOrNull()?.attr("abs:href")

                if (rssLink != null && rssLink != urlStr) {
                    println("🔍 Discovered RSS link: $rssLink from $urlStr")
                    processUrl(rssLink, allItems, seenLinks, depth + 1)
                    return
                }
            }

            // Attempt XML/RSS Parsing
            try {
                // Pre-process XML: Rome can be picky about some common malformed XML or DOCTYPEs
                var xmlContent = String(bodyBytes)
                
                // Remove DOCTYPE if it causes issues (some feeds include it which Rome/Parser might block)
                if (xmlContent.contains("<!DOCTYPE", ignoreCase = true)) {
                    xmlContent = xmlContent.replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
                }

                val input = SyndFeedInput()
                input.isAllowDoctypes = false // Security
                
                val feed = input.build(XmlReader(ByteArrayInputStream(xmlContent.toByteArray())))
                
                feed.entries.take(5).forEach { entry ->
                    val link = entry.link ?: ""
                    if (link.isNotEmpty() && link !in seenLinks) {
                        seenLinks.add(link)
                        allItems.add(NewsItem(
                            title = entry.title ?: "Untitled",
                            summary = entry.description?.value ?: entry.title ?: "",
                            link = link
                        ))
                    }
                }
            } catch (e: Exception) {
                println("❌ RSS Parse Error ($urlStr): ${e.message}")
                // If it looks like HTML error page, we already logged it
            }
        }
    }
}
