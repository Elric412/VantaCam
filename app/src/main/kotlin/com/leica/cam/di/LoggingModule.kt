package com.leica.cam.di

import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.logging.AndroidLeicaLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {
    @Binds
    @Singleton
    abstract fun bindLeicaLogger(impl: AndroidLeicaLogger): LeicaLogger
}
