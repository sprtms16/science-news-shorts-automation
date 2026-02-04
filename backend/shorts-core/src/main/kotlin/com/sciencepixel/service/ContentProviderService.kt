package com.sciencepixel.service

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.RssSource
import com.sciencepixel.domain.SourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.io.ByteArrayInputStream

@Service
class ContentProviderService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchContent(source: RssSource): List<NewsItem> {
        return try {
            when (source.type) {
                SourceType.REDDIT_JSON -> fetchReddit(source)
                SourceType.WIKIPEDIA_ON_THIS_DAY -> fetchWikipediaOnThisDay(source)
                SourceType.RSS -> fetchRss(source)
                SourceType.STOCK_NEWS -> fetchRss(source) // Usually RSS for now
            }
        } catch (e: Exception) {
            println("❌ Content Fetch Error [${source.type}] ${source.title}: ${e.message}")
            emptyList()
        }
    }

    private fun fetchReddit(source: RssSource): List<NewsItem> {
        println("👽 Fetching Reddit: ${source.url}")
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)") // Reddit requires User-Agent
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val children = json.getJSONObject("data").getJSONArray("children")
            
            val items = mutableListOf<NewsItem>()
            for (i in 0 until children.length()) {
                val post = children.getJSONObject(i).getJSONObject("data")
                val title = post.getString("title")
                val selfText = post.optString("selftext", "")
                val url = post.getString("url")
                val author = post.getString("author")
                
                // Stickied posts (announcements) often useless for content
                if (post.optBoolean("stickied", false)) continue

                val content = if (selfText.isNotBlank()) selfText else title
                items.add(NewsItem(
                    title = title,
                    summary = content.take(500), // Truncate summary
                    link = "https://www.reddit.com$url", // Some URLs are relative
                    sourceName = "Reddit (r/${post.getString("subreddit")}) by u/$author"
                ))
            }
            return items.take(10)
        }
    }

    private fun fetchWikipediaOnThisDay(source: RssSource): List<NewsItem> {
        val today = LocalDate.now()
        val month = today.monthValue
        val day = today.dayOfMonth
        val url = "https://en.wikipedia.org/api/rest_v1/feed/onthisday/events/$month/$day"
        
        println("📜 Fetching History: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ScienceNewsShortsBot/1.0 (mailto:admin@sciencepixel.com)")
            .header("Accept", "application/json; charset=utf-8")
            .build()
        
        var body: String? = null
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("❌ Wikipedia API Error: ${response.code} - ${response.message}")
                    return emptyList()
                }
                
                body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val events = json.getJSONArray("events")
                println("📜 Wikipedia: Found ${events.length()} events.")
                
                val items = mutableListOf<NewsItem>()
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val text = event.getString("text")
                    val year = event.optInt("year", 0)
                    
                    val pages = event.optJSONArray("pages")
                    val firstPage = if (pages != null && pages.length() > 0) pages.getJSONObject(0) else null
                    // Fix: Check for empty string specifically
                    var link = firstPage?.optString("content_urls.desktop.page", null)
                    val titleString = firstPage?.optString("title", "Historical Event") ?: "Historical Event"

                    if (link.isNullOrBlank()) {
                        // Fallback: Construct URL from title or use main page
                        link = if (titleString != "Historical Event") {
                            "https://en.wikipedia.org/wiki/" + titleString.replace(" ", "_")
                        } else {
                            "https://en.wikipedia.org/wiki/Portal:History"
                        }
                    }

                    // Log first item for debug
                    if (i == 0) println("📜 First Event: $year - $titleString ($link)")

                    items.add(NewsItem(
                        title = "$year: $titleString",
                        summary = text,
                        link = link,
                        sourceName = "Wikipedia On This Day"
                    ))
                }
                return items.take(10)
            }
        } catch (e: Exception) {
            println("❌ Content Fetch Error [WIKIPEDIA_ON_THIS_DAY]: ${e.message}")
            if (body != null) {
                 println("   Response Body Preview: ${body?.take(200)}")
            }
            return emptyList()
        }
    }

    private fun fetchRss(source: RssSource): List<NewsItem> {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val xml = response.body?.string() ?: return emptyList()
            
            // Basic Rome parsing (simplified)
            val input = SyndFeedInput()
            input.isAllowDoctypes = false
            try {
                // Pre-clean XML if needed (simple removal of DOCTYPE for safety)
                val cleanXml = xml.replace(Regex("<!DOCTYPE[^>]*>"), "")
                val feed = input.build(XmlReader(ByteArrayInputStream(cleanXml.toByteArray())))

                return feed.entries.take(10).map { entry ->
                   NewsItem(
                       title = entry.title ?: "No Title",
                       summary = entry.description?.value ?: entry.title ?: "",
                       link = entry.link ?: "",
                       sourceName = source.title
                   )
                }
            } catch (e: Exception) {
                println("❌ RSS Parse Error [${source.title}] (${source.url}): ${e.message}")
                return emptyList()
            }
        }
    }
}
