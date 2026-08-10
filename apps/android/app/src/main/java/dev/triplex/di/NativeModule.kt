package dev.triplex.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.triplex.nativebridge.runtime.NativeRuntime
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NativeModule {

    @Provides
    @Singleton
    fun provideNativeRuntime(): NativeRuntime = NativeRuntime
}
