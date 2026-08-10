package dev.triplex.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.triplex.data.local.AgentConfigStore
import dev.triplex.data.local.DataStoreAgentConfigStore
import dev.triplex.voice.LocalVoicePromptStore
import dev.triplex.voice.VoiceProfileReadiness
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAgentConfigStore(store: DataStoreAgentConfigStore): AgentConfigStore

    companion object {
        /**
         * Local clone readiness: on-device x-vector + Qwen models + verify.
         * An unreachable gateway must not gate CLONED anymore.
         */
        @Provides
        @Singleton
        fun provideVoiceProfileReadiness(
            promptStore: LocalVoicePromptStore,
        ): VoiceProfileReadiness = VoiceProfileReadiness {
            promptStore.synthesisReady()
        }
    }
}
