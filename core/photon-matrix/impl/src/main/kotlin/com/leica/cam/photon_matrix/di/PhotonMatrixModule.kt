package com.leica.cam.photon_matrix.di

import com.leica.cam.photon_matrix.IPhotonMatrixAssembler
import com.leica.cam.photon_matrix.IPhotonMatrixIngestor
import com.leica.cam.photon_matrix.PhotonMatrixAssembler
import com.leica.cam.photon_matrix.PhotonMatrixIngestor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PhotonMatrixModule {

    @Binds
    @Singleton
    abstract fun bindPhotonMatrixIngestor(
        impl: PhotonMatrixIngestor,
    ): IPhotonMatrixIngestor

    @Binds
    @Singleton
    abstract fun bindPhotonMatrixAssembler(
        impl: PhotonMatrixAssembler,
    ): IPhotonMatrixAssembler
}
