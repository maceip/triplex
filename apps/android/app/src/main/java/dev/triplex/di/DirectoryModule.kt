package dev.triplex.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.triplex.data.telecom.AndroidDialerSetupRepository
import dev.triplex.data.telecom.AndroidTelecomAccountsRepository
import dev.triplex.data.telecom.DialerSetupRepository
import dev.triplex.data.telecom.TelecomAccountsRepository
import dev.triplex.dialer.ContentResolverDirectoryDataSource
import dev.triplex.dialer.DialerDirectoryRepository
import dev.triplex.dialer.DirectoryDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DirectoryModule {

    @Binds
    @Singleton
    abstract fun bindDirectoryDataSource(
        source: ContentResolverDirectoryDataSource,
    ): DirectoryDataSource

    @Binds
    @Singleton
    abstract fun bindTelecomAccountsRepository(
        repository: AndroidTelecomAccountsRepository,
    ): TelecomAccountsRepository

    @Binds
    @Singleton
    abstract fun bindDialerSetupRepository(
        repository: AndroidDialerSetupRepository,
    ): DialerSetupRepository

    companion object {
        /**
         * One directory for the whole process (reskin.md §2.4).
         *
         * Built by hand, like `CallSessionRepository`, so the class keeps a
         * plain constructor a JVM test can call. The scope is process-wide
         * because the content observers must keep the call log fresh whether or
         * not a keypad happens to be composed — the in-call surface resolves
         * caller identity from the same instance.
         */
        @Provides
        @Singleton
        fun provideDialerDirectoryRepository(
            source: DirectoryDataSource,
        ): DialerDirectoryRepository = DialerDirectoryRepository(
            source = source,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
