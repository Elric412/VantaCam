package com.leica.cam.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AssetsModule {
    @Provides
    @Singleton
    @Named("assetBytes")
    fun provideAssetBytesLoader(
        @ApplicationContext ctx: Context,
    ): @JvmSuppressWildcards Function1<String, ByteBuffer> = { path ->
        ctx.assets.open(path).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes); rewind()
            }
        }
    }
}