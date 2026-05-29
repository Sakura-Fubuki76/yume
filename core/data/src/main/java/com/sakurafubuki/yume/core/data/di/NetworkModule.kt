package com.sakurafubuki.yume.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Qualifier for the default shared OkHttpClient instance.
 * Use this for general HTTP operations that don't need custom headers per-request.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultHttpClient

/**
 * Qualifier for an OkHttpClient base builder that can be further customized
 * (e.g., adding auth interceptors for WebDAV streams).
 * The returned builder is a NEW instance each time, but shares the same
 * connection pool and thread pool as the default client.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HttpClientBuilder

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamingHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Shared connection pool used by all OkHttpClients in the app.
     * Centralizing this ensures connections are reused across components
     * instead of each OkHttpClient creating its own pool (which wastes sockets + threads).
     */
    private val sharedConnectionPool = ConnectionPool(
        maxIdleConnections = 25,
        keepAliveDuration = 30,
        timeUnit = TimeUnit.MINUTES,
    )

    @Provides
    @Singleton
    @DefaultHttpClient
    fun provideDefaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(sharedConnectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Provides
    @Singleton
    @StreamingHttpClient
    fun provideStreamingOkHttpClient(
        @DefaultHttpClient defaultClient: OkHttpClient,
    ): OkHttpClient = defaultClient.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequestsPerHost = 30
            },
        )
        .build()

    @Provides
    @HttpClientBuilder
    fun provideHttpClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(sharedConnectionPool)
        .followRedirects(true)
        .followSslRedirects(true)
}
