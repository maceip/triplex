package dev.triplex.data.remote

import dev.triplex.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

object HttpClientFactory {
    fun create(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }

            // Voice preparation and cloned synthesis are model-bound and can
            // take tens of seconds; the default 15 s request timeout cuts
            // them off. Connect/socket stay short so a dead gateway still
            // fails fast.
            install(HttpTimeout) {
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 180_000
            }

            if (BuildConfig.DEBUG) {
                install(Logging) {
                    level = LogLevel.INFO
                }
            }

            defaultRequest {
                url(BuildConfig.GATEWAY_URL)
            }
        }
    }
}
