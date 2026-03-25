package com.vrtmv.app.di

import com.vrtmv.app.data.inference.GeminiNanoEngine
import com.vrtmv.app.data.inference.InferenceEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 추론 엔진 의존성 주입 모듈.
 * GeminiNanoEngine(온디바이스 LLM)을 InferenceEngine 인터페이스에 바인딩.
 * 테스트 시 MockInferenceEngine으로 교체 가능.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InferenceModule {

    @Binds
    @Singleton
    abstract fun bindInferenceEngine(impl: GeminiNanoEngine): InferenceEngine
}
