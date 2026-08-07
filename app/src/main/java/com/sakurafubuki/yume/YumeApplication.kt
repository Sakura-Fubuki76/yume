package com.sakurafubuki.yume

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.sakurafubuki.yume.BuildConfig
import com.sakurafubuki.yume.core.cache.ImageCacheManager
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.di.ApplicationScope
import com.sakurafubuki.yume.core.data.repository.MoovIndexCache
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.data.repository.SpriteSheetCache
import com.sakurafubuki.yume.crash.CrashActivity
import com.sakurafubuki.yume.crash.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class YumeApplication :
    Application(),
    SingletonImageLoader.Factory {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var appImageLoaderFactory: AppImageLoaderFactory

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Volatile
    private var runtimeImageLoader: ImageLoader? = null

    override fun onCreate() {
        super.onCreate()
        Logger.isDebug = BuildConfig.DEBUG
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))
        runtimeImageLoader = appImageLoaderFactory.create()
        ImageCacheManager.registerGlobalImageLoaderRebuilder(::rebuildGlobalImageLoader)
        SpriteSheetCache.init(cacheDir)
        MoovIndexCache.init(cacheDir)
        runCacheExpiryCleanup()
    }

    private fun runCacheExpiryCleanup() {
        applicationScope.launch {
            val preferences = preferencesRepository.applicationPreferences.value
            val expiry = preferences.imageCacheExpiry
            if (preferences.imageCloudDiskCacheEnabled && expiry.millis != null) {
                ImageCacheManager.cleanExpiredCloudImageCache(this@YumeApplication, expiry)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = runtimeImageLoader ?: appImageLoaderFactory.create().also { runtimeImageLoader = it }

    @OptIn(DelicateCoilApi::class)
    private fun rebuildGlobalImageLoader(diskCacheSizeMb: Int) {
        val previousLoader = runtimeImageLoader
        runtimeImageLoader = appImageLoaderFactory.create(diskCacheSizeMb = diskCacheSizeMb)
        SingletonImageLoader.reset()
        previousLoader?.shutdown()
    }
}
