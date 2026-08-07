package com.sakurafubuki.yume.core.data.webdav

import com.sakurafubuki.yume.core.data.di.DefaultHttpClient
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import javax.inject.Inject
import okhttp3.OkHttpClient

interface SardineFactory {
    fun create(): Sardine
}

class SardineFactoryImpl @Inject constructor(
    @DefaultHttpClient private val sharedOkHttpClient: OkHttpClient,
) : SardineFactory {
    override fun create(): Sardine = OkHttpSardine(sharedOkHttpClient)
}
