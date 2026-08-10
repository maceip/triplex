package dev.triplex.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.triplex.data.local.db.AgentRunDao
import dev.triplex.data.local.db.TriplexDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TriplexDatabase =
        Room.databaseBuilder(context, TriplexDatabase::class.java, TriplexDatabase.NAME)
            .build()

    @Provides
    @Singleton
    fun provideAgentRunDao(database: TriplexDatabase): AgentRunDao = database.agentRunDao()
}
