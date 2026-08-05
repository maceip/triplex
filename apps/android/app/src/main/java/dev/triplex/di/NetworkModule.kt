package dev.triplex.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.triplex.data.remote.GatewayApi
import dev.triplex.data.remote.HttpClientFactory
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClientFactory.create()
    }

    @Provides
    @Singleton
    fun provideGatewayApi(client: HttpClient): GatewayApi {
        return GatewayApi(client)
    }
}
