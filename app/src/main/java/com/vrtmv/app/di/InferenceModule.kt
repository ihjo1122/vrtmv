package com.vrtmv.app.di

import com.vrtmv.app.data.inference.InferenceEngine
import com.vrtmv.app.data.inference.LiteRtLmEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 추론 엔진 의존성 주입 모듈.
 * LiteRT-LM(LiteRtLmEngine) 단일 엔진 제공.
 */
@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {

    @Provides
    @Singleton
    fun provideInferenceEngine(impl: LiteRtLmEngine): InferenceEngine = impl
}
