package com.sakurafubuki.yume.core.data.openlist

import com.sakurafubuki.yume.core.data.di.DefaultHttpClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OpenListModule {
    @Binds
    @Singleton
    internal abstract fun bindsOpenListApi(impl: OpenListApiImpl): OpenListApi

    companion object {
        @Provides
        @Singleton
        fun provideOpenListOkHttpClient(@DefaultHttpClient default: OkHttpClient): OkHttpClient = default
    }
}
