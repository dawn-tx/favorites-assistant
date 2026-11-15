package com.favkit.tablet.di

import android.app.Application
import com.favkit.tablet.core.StorageProbe
import com.favkit.tablet.data.AppSettings
import com.favkit.tablet.data.FavoritesRepository
import com.favkit.tablet.data.TagsRepository
import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.media.MediaCache
import com.favkit.tablet.media.TabletPlayer
import com.favkit.tablet.net.SyncApi
import com.favkit.tablet.net.SyncGate
import com.favkit.tablet.sync.Connectivity
import com.favkit.tablet.sync.SyncManager

/**
 * 轻量依赖容器：全部 lazy，冷启动只走一次对象构造，不触达磁盘与网络。
 */
class AppGraph private constructor(private val app: Application) {

    val settings: AppSettings by lazy { AppSettings(app) }
    val db: AppDatabase by lazy { AppDatabase.build(app) }
    val tags: TagsRepository by lazy { TagsRepository(db) }
    val favorites: FavoritesRepository by lazy { FavoritesRepository(db, settings) }
    val connectivity: Connectivity by lazy { Connectivity(app) }
    val media: MediaCache by lazy { MediaCache(app, settings) }
    val player: TabletPlayer by lazy { TabletPlayer(media) }
    val storage: StorageProbe by lazy { StorageProbe(app, db, media) }

    val gate: SyncGate by lazy { SyncGate() }
    val api: SyncApi by lazy { SyncApi(settings, gate) }
    val sync: SyncManager by lazy { SyncManager(app, db) }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun of(app: Application): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(app).also { instance = it } }
    }
}
