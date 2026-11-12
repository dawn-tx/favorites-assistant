package com.favkit.tablet.data

import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.data.db.FavoriteTagRow
import com.favkit.tablet.data.db.TagRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TagsRepository(db: AppDatabase) {

    private val tagsDao = db.tags()
    private val refDao = db.refs()

    fun observeTags(): Flow<List<Tag>> = tagsDao.observeUsage().map { rows ->
        rows.map { Tag(it.id, it.name, it.isPreset, it.usageCount) }
    }

    /**
     * 首次使用时播种常用标签。放在标签真正被读取时调用，而不是 Application.onCreate，
     * 这样冷启动不会被磁盘写拖慢。
     */
    suspend fun ensurePresets() {
        if (tagsDao.count() > 0) return
        tagsDao.insertAll(PRESET_TAG_NAMES.map { TagRow(name = it, isPreset = true) })
    }

    /** 面板内联新建标签：同名直接复用，不产生重复项 */
    suspend fun getOrCreate(name: String): Tag {
        val clean = name.trim().take(24)
        require(clean.isNotEmpty()) { "标签名不能为空" }
        tagsDao.byName(clean)?.let { return it.toTag() }
        tagsDao.insert(TagRow(name = clean))
        val saved = tagsDao.byName(clean) ?: error("标签写入失败")
        return saved.toTag()
    }

    /** 重命名保留原 id 与既有关联；若目标名已存在则合并两个标签 */
    suspend fun rename(id: Long, name: String) {
        val clean = name.trim().take(24)
        require(clean.isNotEmpty()) { "标签名不能为空" }
        val existing = tagsDao.byName(clean)
        if (existing != null) {
            if (existing.id == id) return
            refDao.reparent(from = id, to = existing.id)
            refDao.clearForTag(id)
            tagsDao.delete(id)
            return
        }
        tagsDao.rename(id, clean)
    }

    suspend fun delete(id: Long) {
        refDao.clearForTag(id)
        tagsDao.delete(id)
    }

    suspend fun tagIdsFor(favoriteId: String): List<Long> = refDao.tagIdsFor(favoriteId)

    private fun TagRow.toTag() = Tag(id, name, isPreset, 0)

    companion object {
        val PRESET_TAG_NAMES = listOf("教程", "行业", "会议", "灵感", "待看", "技术", "产品", "设计")
    }
}
