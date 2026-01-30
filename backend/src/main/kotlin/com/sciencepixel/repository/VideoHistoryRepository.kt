import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import org.springframework.data.mongodb.repository.Aggregation
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface VideoHistoryRepository : MongoRepository<VideoHistory, String> {
    fun findByLink(link: String): VideoHistory?
    fun findByStatus(status: VideoStatus): List<VideoHistory>
    fun findByStatusIn(statuses: Collection<VideoStatus>): List<VideoHistory>
    fun findByStatusNot(status: VideoStatus): List<VideoHistory>

    @Aggregation(pipeline = [
        "{ \$group: { _id: '\$link', count: { \$sum: 1 }, docs: { \$push: '\$\$ROOT' } } }",
        "{ \$match: { count: { \$gt: 1 } } }"
    ])
    fun findDuplicateLinks(): List<DuplicateLinkGroup>
}

data class DuplicateLinkGroup(val _id: String, val docs: List<VideoHistory>)
