package dev.triplex.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * A coroutine scope that lives as long as the process.
 *
 * Work that must outlive whatever screen happened to start it — recording a
 * call run, in particular — belongs here rather than in a `viewModelScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        // SupervisorJob: one failed collector must not take down the others.
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
