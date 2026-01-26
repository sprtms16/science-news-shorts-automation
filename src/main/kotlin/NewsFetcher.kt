import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.net.URL

data class NewsData(val title: String, val link: String, val summary: String)

class NewsFetcher {
    // PDF mentions: https://www.sciencedaily.com/rss/top_news.xml
    // and https://www.nasa.gov/rss/dyn/breaking_news.rss (URL in PDF seems slightly different, using standard one)
    private val sources = listOf(
        "https://www.sciencedaily.com/rss/top_news.xml",
        "https://www.nasa.gov/rss/dyn/breaking_news.rss"
    )

    fun fetchLatestNews(): List<NewsData> {
        val newsList = mutableListOf<NewsData>()
        
        for (url in sources) {
            try {
                val feedUrl = URL(url)
                val input = SyndFeedInput()
                val feed = input.build(XmlReader(feedUrl))
                
                // Get top 3 news from each source
                feed.entries.take(3).forEach { entry ->
                    val title = entry.title
                    val link = entry.link
                    // Description can be in different fields depending on RSS version
                    val description = entry.description?.value ?: ""
                    
                    newsList.add(NewsData(title, link, description))
                }
            } catch (e: Exception) {
                println("Error fetching news from $url: ${e.message}")
            }
        }
        return newsList
    }
}
