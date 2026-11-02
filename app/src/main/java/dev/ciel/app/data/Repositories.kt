package dev.ciel.app.data

import dev.ciel.game.GameProfile
import dev.ciel.game.GameProfileCodec
import dev.ciel.game.GameProfileRepository
import dev.ciel.memory.MemoryCategory
import dev.ciel.memory.MemoryItem
import dev.ciel.memory.MemoryRepository
import dev.ciel.task.ActionRecord
import dev.ciel.task.TaskHistoryStore
import dev.ciel.task.TaskState
import dev.ciel.task.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMemoryRepository(private val dao: MemoryDao) : MemoryRepository {
    override fun observeAll(): Flow<List<MemoryItem>> = dao.observe().map { list -> list.map { it.toItem() } }
    override suspend fun all() = dao.all().map { it.toItem() }
    override suspend fun upsert(item: MemoryItem) = dao.upsert(
        MemoryEntity(item.id, item.category.name, item.content, item.tags.joinToString("\u001F"), item.source, item.pinned, item.createdAt, item.updatedAt, item.expiresAt))
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteCategory(category: MemoryCategory) = dao.deleteCategory(category.name)
    override suspend fun deleteAll() = dao.deleteAll()

    private fun MemoryEntity.toItem() = MemoryItem(
        id = id,
        category = runCatching { MemoryCategory.valueOf(category) }.getOrDefault(MemoryCategory.PREFERENCE),
        content = content, tags = tags.split("\u001F").filter { it.isNotBlank() }, source = source, pinned = pinned,
        createdAt = createdAt, updatedAt = updatedAt, expiresAt = expiresAt,
    )
}

class RoomGameProfileRepository(private val dao: GameProfileDao) : GameProfileRepository {
    private fun decode(json: String) = runCatching { GameProfileCodec.decode(json, trustPolicy = true) }.getOrNull()
    override suspend fun get(packageName: String) = dao.get(packageName)?.let { decode(it.json) }
    override suspend fun save(profile: GameProfile) = dao.upsert(GameProfileEntity(profile.packageName, GameProfileCodec.encode(profile), profile.updatedAt))
    override suspend fun all() = dao.all().mapNotNull { decode(it.json) }
    override suspend fun delete(packageName: String) = dao.delete(packageName)
    fun observe() = dao.observe().map { list -> list.mapNotNull { decode(it.json) } }
}

class RoomTaskHistory(private val dao: TaskDao) : TaskHistoryStore {
    override suspend fun save(task: TaskState) = dao.upsert(
        TaskEntity(task.id, task.objective, task.kind, task.status.name, task.report, task.errors.joinToString("\n"), task.verified, task.startedAt, task.finishedAt))
    override suspend fun recent(limit: Int) = dao.recent(limit).map {
        TaskState(it.id, it.objective, it.kind, runCatching { TaskStatus.valueOf(it.status) }.getOrDefault(TaskStatus.FAILED),
            report = it.report, errors = it.errors.split("\n").filter { e -> e.isNotBlank() }, verified = it.verified,
            startedAt = it.startedAt, finishedAt = it.finishedAt)
    }
    suspend fun log(r: ActionRecord) = dao.log(ActionLogEntity(0, r.taskId, r.action, r.risk.name, r.approved, r.outcome, r.timestamp))
}
