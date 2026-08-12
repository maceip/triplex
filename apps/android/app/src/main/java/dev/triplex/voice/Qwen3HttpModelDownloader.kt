package dev.triplex.voice

import dev.triplex.BuildConfig
import dev.triplex.data.repository.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-oriented HTTP fetch of the Qwen3 LiteRT bundle into [targetDir].
 *
 * Uses a dedicated client (not the gateway-scoped one) with long timeouts for
 * multi-hundred-MB artifacts. Release builds should not call this;
 * [Qwen3ModelStore] gates on [BuildConfig.DEBUG].
 */
@Singleton
class Qwen3HttpModelDownloader @Inject constructor() {
    suspend fun download(
        targetDir: File,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.Error("HTTP model download is debug-only")
        }
        targetDir.mkdirs()
        val base = BuildConfig.QWEN3_MODEL_BASE_URL.trimEnd('/')
        val artifacts = Qwen3ModelFiles.ARTIFACTS
        var completed = artifacts.count { (basename, _) ->
            val f = File(targetDir, basename)
            f.isFile && f.length() > 0L
        }
        if (completed == artifacts.size) {
            onProgress(1f)
            return@withContext Result.Success(Unit)
        }

        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }.use { client ->
            try {
                for ((index, artifact) in artifacts.withIndex()) {
                    val (basename, remoteRelative) = artifact
                    val dest = File(targetDir, basename)
                    if (dest.isFile && dest.length() > 0L) {
                        report(completed, artifacts.size, onProgress)
                        continue
                    }
                    val url = "$base/$remoteRelative"
                    val tmp = File(targetDir, "$basename.partial")
                    tmp.delete()
                    Timber.i(
                        "Downloading Qwen3 model %s (%d/%d)",
                        basename,
                        index + 1,
                        artifacts.size,
                    )
                    client.prepareGet(url).execute { response ->
                        if (response.status.value !in 200..299) {
                            error("HTTP ${response.status.value} for $basename")
                        }
                        val total = response.contentLength() ?: -1L
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(DEFAULT_BUFFER)
                        var written = 0L
                        tmp.outputStream().use { out ->
                            while (!channel.isClosedForRead) {
                                val read = channel.readAvailable(buffer, 0, buffer.size)
                                if (read < 0) break
                                if (read == 0) continue
                                out.write(buffer, 0, read)
                                written += read
                                if (total > 0L) {
                                    val fileFrac = written.toFloat() / total.toFloat()
                                    val overall =
                                        (completed + fileFrac.coerceIn(0f, 1f)) / artifacts.size
                                    onProgress(overall.coerceIn(0f, 1f))
                                }
                            }
                        }
                        if (written <= 0L) error("Empty download for $basename")
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    completed += 1
                    report(completed, artifacts.size, onProgress)
                }
                if (!Qwen3ModelFiles.isComplete(targetDir)) {
                    return@withContext Result.Error(
                        "Download finished but required files are missing",
                    )
                }
                onProgress(1f)
                Result.Success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Qwen3 model download failed")
                Result.Error("Voice model download failed: ${e.message}", e)
            }
        }
    }

    private fun report(completed: Int, total: Int, onProgress: (Float) -> Unit) {
        onProgress((completed.toFloat() / total.toFloat()).coerceIn(0f, 1f))
    }

    private companion object {
        const val DEFAULT_BUFFER = 64 * 1024
        const val REQUEST_TIMEOUT_MS = 60L * 60L * 1_000L // 1 hour per file
    }
}
