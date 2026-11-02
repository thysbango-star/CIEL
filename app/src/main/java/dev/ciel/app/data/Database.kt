package dev.ciel.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** "user" or "ciel" */
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val providerName: String? = null,
    val offline: Boolean = false,
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val category: String,
    val content: String,
    val tags: String,
    val source: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val kind: String,
    val status: String,
    val report: String?,
    val errors: String,
    val verified: Boolean,
    val startedAt: Long,
    val finishedAt: Long?,
)

@Entity(tableName = "action_log", indices = [Index("taskId")])
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val action: String,
    val risk: String,
    val approved: Boolean?,
    val outcome: String,
    val timestamp: Long,
)

@Entity(tableName = "game_profiles")
data class GameProfileEntity(@PrimaryKey val packageName: String, val json: String, val updatedAt: Long)

@Entity(tableName = "reminders")
data class ReminderEntity(@PrimaryKey val id: String, val text: String, val triggerAt: Long)

/** One row per conversation for the Chats list. */
data class ConversationSummary(val conversationId: String, val lastAt: Long, val count: Int, val title: String?)

@Dao
interface MessageDao {
    @Query(
        "SELECT conversationId, MAX(createdAt) AS lastAt, COUNT(*) AS count, " +
            "(SELECT m2.text FROM messages AS m2 WHERE m2.conversationId = messages.conversationId AND m2.role = 'user' ORDER BY m2.id ASC LIMIT 1) AS title " +
            "FROM messages GROUP BY conversationId ORDER BY lastAt DESC"
    )
    fun conversations(): Flow<List<ConversationSummary>>
    @Query("SELECT conversationId FROM messages ORDER BY id DESC LIMIT 1")
    suspend fun latestConversationId(): String?
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id ASC")
    fun observe(cid: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id DESC LIMIT :limit")
    suspend fun recent(cid: String, limit: Int): List<MessageEntity>
    @Insert suspend fun insert(m: MessageEntity): Long
    @Query("DELETE FROM messages WHERE conversationId = :cid") suspend fun clear(cid: String)
    @Query("DELETE FROM messages") suspend fun clearAll()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun observe(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories") suspend fun all(): List<MemoryEntity>
    @Upsert suspend fun upsert(m: MemoryEntity)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM memories WHERE category = :category") suspend fun deleteCategory(category: String)
    @Query("DELETE FROM memories") suspend fun deleteAll()
}

@Dao
interface TaskDao {
    @Upsert suspend fun upsert(t: TaskEntity)
    @Query("SELECT * FROM tasks ORDER BY startedAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<TaskEntity>
    @Query("SELECT * FROM tasks ORDER BY startedAt DESC LIMIT 100") fun observe(): Flow<List<TaskEntity>>
    @Insert suspend fun log(a: ActionLogEntity)
    @Query("SELECT * FROM action_log ORDER BY id DESC LIMIT :limit") fun observeLog(limit: Int): Flow<List<ActionLogEntity>>
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM action_log") suspend fun clearLog()
}

@Dao
interface GameProfileDao {
    @Query("SELECT * FROM game_profiles WHERE packageName = :pkg") suspend fun get(pkg: String): GameProfileEntity?
    @Query("SELECT * FROM game_profiles ORDER BY updatedAt DESC") suspend fun all(): List<GameProfileEntity>
    @Query("SELECT * FROM game_profiles ORDER BY updatedAt DESC") fun observe(): Flow<List<GameProfileEntity>>
    @Upsert suspend fun upsert(e: GameProfileEntity)
    @Query("DELETE FROM game_profiles WHERE packageName = :pkg") suspend fun delete(pkg: String)
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: ReminderEntity)
    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC") suspend fun all(): List<ReminderEntity>
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun delete(id: String): Int
    @Query("DELETE FROM reminders WHERE triggerAt < :now") suspend fun deleteBefore(now: Long)
}

@Database(
    entities = [MessageEntity::class, MemoryEntity::class, TaskEntity::class, ActionLogEntity::class, GameProfileEntity::class, ReminderEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CielDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao
    abstract fun tasks(): TaskDao
    abstract fun games(): GameProfileDao
    abstract fun reminders(): ReminderDao

    companion object {
        fun create(context: Context): CielDatabase =
            Room.databaseBuilder(context, CielDatabase::class.java, "ciel.db").build()
    }
}
