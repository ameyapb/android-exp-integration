package com.ameyapb.androidexp.di

import com.ameyapb.androidexp.BuildConfig
import com.ameyapb.androidexp.data.gemini.GeminiClient
import com.ameyapb.androidexp.data.gemini.GeminiClientImpl
import com.ameyapb.androidexp.data.gemini.GeminiRepository
import com.ameyapb.androidexp.data.gemini.GeminiRepositoryImpl
import com.ameyapb.androidexp.data.notification.GeminiNotifier
import com.ameyapb.androidexp.data.notification.GeminiNotifierImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiApiKey

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindGeminiClient(impl: GeminiClientImpl): GeminiClient

    @Binds
    @Singleton
    abstract fun bindGeminiRepository(impl: GeminiRepositoryImpl): GeminiRepository

    @Binds
    @Singleton
    abstract fun bindGeminiNotifier(impl: GeminiNotifierImpl): GeminiNotifier

    companion object {
        @Provides
        @Singleton
        @GeminiApiKey
        fun provideGeminiApiKey(): String {
            check(BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                "GEMINI_API_KEY is not set. Add it to android-app/local.properties " +
                    "(see local.properties.example)."
            }
            return BuildConfig.GEMINI_API_KEY
        }
    }
}
