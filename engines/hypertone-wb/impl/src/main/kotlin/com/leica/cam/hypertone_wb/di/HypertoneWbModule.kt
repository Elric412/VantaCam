package com.leica.cam.hypertone_wb.di

import com.leica.cam.common.logging.LeicaLogger
import com.leica.cam.gpu_compute.GpuBackend
import com.leica.cam.hardware.contracts.TrueColourHardwareSensor
import com.leica.cam.hypertone_wb.pipeline.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HypertoneWbModule {

    @Provides
    @Singleton
    fun provideIlluminantClassifier(): IlluminantClassifier = object : IlluminantClassifier {
        override fun classify(kelvin: Float): com.leica.cam.hypertone_wb.api.IlluminantClass {
            return when {
                kelvin < 3200f -> com.leica.cam.hypertone_wb.api.IlluminantClass.TUNGSTEN_WARM
                kelvin < 4200f -> com.leica.cam.hypertone_wb.api.IlluminantClass.LED_NEUTRAL
                kelvin < 5500f -> com.leica.cam.hypertone_wb.api.IlluminantClass.FLUORESCENT
                kelvin < 6500f -> com.leica.cam.hypertone_wb.api.IlluminantClass.DAYLIGHT_DIRECT
                else -> com.leica.cam.hypertone_wb.api.IlluminantClass.DAYLIGHT_OVERCAST
            }
        }
    }

    @Provides @Singleton fun providePartitionedCTSensor(hw: TrueColourHardwareSensor, cls: IlluminantClassifier) = PartitionedCTSensor(hw, cls)
    @Provides @Singleton fun provideMultiModalIlluminantFusion() = MultiModalIlluminantFusion()
    @Provides @Singleton fun provideMixedLightSpatialWbEngine(estimators: IlluminantEstimators, converter: KelvinToCcmConverter) = MixedLightSpatialWbEngine(estimators, converter)
    @Provides @Singleton fun provideSkinZoneWbGuard() = SkinZoneWbGuard()
    @Provides @Singleton fun provideKelvinToCcmConverter() = KelvinToCcmConverter()
    @Provides @Singleton fun provideWbMemoryStore(): WbMemoryStore = InMemoryWbMemoryStore()
    @Provides @Singleton fun provideWbTemporalMemory(store: WbMemoryStore) = WbTemporalMemory(store)
    @Provides @Singleton fun provideIlluminantEstimators(
        @Named("wb_global_predictor") global: IlluminantPredictor,
        @Named("wb_local_predictor") local: IlluminantPredictor,
        @Named("wb_semantic_predictor") semantic: IlluminantPredictor,
    ) = IlluminantEstimators(global, local, semantic)

    @Provides @Named("wb_global_predictor") fun provideGlobalPredictor(): IlluminantPredictor = HeuristicIlluminantPredictor(250f, 0f, 0.58f)
    @Provides @Named("wb_local_predictor") fun provideLocalPredictor(): IlluminantPredictor = HeuristicIlluminantPredictor(-120f, 6f, 0.52f)
    @Provides @Named("wb_semantic_predictor") fun provideSemanticPredictor(): IlluminantPredictor = HeuristicIlluminantPredictor(80f, -4f, 0.56f)

    @Provides
    @Singleton
    fun provideHyperToneWB2Engine(
        sensor: PartitionedCTSensor,
        fusion: MultiModalIlluminantFusion,
        spatial: MixedLightSpatialWbEngine,
        temporal: WbTemporalMemory,
        guard: SkinZoneWbGuard,
        gpu: GpuBackend,
        logger: LeicaLogger,
    ) = HyperToneWB2Engine(sensor, fusion, spatial, temporal, guard, gpu, logger)

    @Provides @Named("hypertone_wb_module") fun provideModuleName(): String = "hypertone-wb"
}
